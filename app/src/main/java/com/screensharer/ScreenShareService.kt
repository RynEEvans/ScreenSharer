package com.screensharer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenShareService : Service() {
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var encoderInputSurface: Surface? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (running.compareAndSet(false, true)) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val resultData = intent?.readResultData()
            if (resultData == null) {
                stopSelf()
            } else {
                Thread({ startStreaming(resultCode, resultData) }, "screen-share").start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStreaming(resultCode: Int, resultData: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val capture = projection ?: throw IllegalStateException("Screen capture permission was not granted")
            capture.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, null)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val scale = minOf(1920f / maxOf(metrics.widthPixels, metrics.heightPixels), 1f)
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) / 2 * 2
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) / 2 * 2
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = videoEncoder
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoEncoder.createInputSurface()
            encoderInputSurface = inputSurface
            videoEncoder.start()
            virtualDisplay = capture.createVirtualDisplay(
                "ScreenSharer",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            serverSocket = ServerSocket(PORT)
            val socket = serverSocket?.accept() ?: return
            clientSocket = socket
            socket.tcpNoDelay = true
            streamEncodedVideo(videoEncoder, socket, width, height)
        } catch (_: Exception) {
            stopSelf()
        } finally {
            stopStreaming()
            stopSelf()
        }
    }

    private fun streamEncodedVideo(codec: MediaCodec, socket: Socket, width: Int, height: Int) {
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val info = MediaCodec.BufferInfo()
        var sentHeader = false
        while (running.get()) {
            when (val index = codec.dequeueOutputBuffer(info, 100_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val csd0 = format.getByteBuffer("csd-0")?.copyRemaining() ?: ByteArray(0)
                    val csd1 = format.getByteBuffer("csd-1")?.copyRemaining() ?: ByteArray(0)
                    if (csd0.isEmpty() && csd1.isEmpty()) throw IllegalStateException("Encoder did not provide H.264 configuration")
                    output.writeInt(STREAM_MAGIC)
                    output.writeInt(width)
                    output.writeInt(height)
                    output.writeInt(csd0.size)
                    output.write(csd0)
                    output.writeInt(csd1.size)
                    output.write(csd1)
                    output.flush()
                    sentHeader = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    try {
                        if (sentHeader && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = codec.getOutputBuffer(index) ?: continue
                            val frame = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(frame)
                            output.writeInt(frame.size)
                            output.writeLong(info.presentationTimeUs)
                            output.writeInt(info.flags)
                            output.write(frame)
                            output.flush()
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        output.writeInt(-1)
        output.flush()
    }

    private fun stopStreaming() {
        if (!running.getAndSet(false)) return
        try { clientSocket?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { encoderInputSurface?.release() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
        clientSocket = null
        serverSocket = null
        virtualDisplay = null
        encoderInputSurface = null
        projection = null
        encoder = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("ScreenSharer is active")
            .setContentText("Waiting for a TV on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopIntent).build())
            .build()
    }

    private fun Intent.readResultData(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(EXTRA_RESULT_DATA)
    }

    private fun ByteBuffer.copyRemaining(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    companion object {
        const val PORT = 45821
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val STREAM_MAGIC = 0x53534852
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL_ID = "screen_share"
        private const val ACTION_STOP = "com.screensharer.STOP"
    }
}