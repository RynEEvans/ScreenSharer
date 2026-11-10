package com.screensharer

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object ScreenReceiver {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null

    fun connect(host: String, port: Int, surface: Surface, onStatus: (String) -> Unit) {
        stop()
        executor.execute {
            var decoder: MediaCodec? = null
            try {
                val connection = Socket()
                socket = connection
                connection.connect(InetSocketAddress(host, port), 8_000)
                connection.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(connection.getInputStream()))
                if (input.readInt() != STREAM_MAGIC) throw IllegalStateException("That device did not send a ScreenSharer stream")
                val width = input.readInt()
                val height = input.readInt()
                val csd0 = input.readBytesChecked()
                val csd1 = input.readBytesChecked()
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                if (csd0.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                if (csd1.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder.configure(format, surface, null, 0)
                decoder.start()
                onStatus("Connected: ${width}x$height")
                receiveFrames(input, decoder)
                onStatus("Stream ended")
            } catch (error: Exception) {
                onStatus("Connection failed: ${error.message ?: "check the address and Wi-Fi"}")
            } finally {
                try { decoder?.stop() } catch (_: Exception) { }
                try { decoder?.release() } catch (_: Exception) { }
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun receiveFrames(input: DataInputStream, decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = input.readInt()
            if (size < 0) return
            require(size <= MAX_FRAME_BYTES) { "Received an invalid frame" }
            val timestamp = input.readLong()
            val flags = input.readInt()
            val frame = ByteArray(size)
            input.readFully(frame)
            var inputIndex = decoder.dequeueInputBuffer(10_000)
            while (inputIndex < 0) inputIndex = decoder.dequeueInputBuffer(10_000)
            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: throw IllegalStateException("Decoder input buffer unavailable")
            inputBuffer.clear()
            inputBuffer.put(frame)
            decoder.queueInputBuffer(inputIndex, 0, size, timestamp, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)

            var outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
            while (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
                outputIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        }
    }

    private fun DataInputStream.readBytesChecked(): ByteArray {
        val length = readInt()
        require(length in 0..MAX_CONFIG_BYTES) { "Received invalid video configuration" }
        return ByteArray(length).also { readFully(it) }
    }

    private const val STREAM_MAGIC = 0x53534852
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 1024 * 1024
}