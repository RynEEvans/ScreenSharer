package com.screensharer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val projectionRequestCode = 41
    private var receiverSurface: Surface? = null
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (packageManager.hasSystemFeature("android.software.leanback")) {
            showReceiver()
        } else {
            showHome()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequestCode && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            showSenderStarted()
        }
    }

    override fun onDestroy() {
        ScreenReceiver.stop()
        super.onDestroy()
    }

    private fun showHome() {
        val content = column()
        content.addView(title("ScreenSharer"))
        content.addView(button("Share this phone") { showSender() })
        content.addView(button("Watch a phone") { showReceiver() })
        setContentView(content)
    }

    private fun showSender() {
        val content = column()
        content.addView(title("Share this phone"))
        content.addView(text("Connect both devices to the same Wi-Fi network. The TV will ask for this phone's address."))
        content.addView(text("Phone address: ${localIpAddress() ?: "Unavailable; connect to Wi-Fi"}"))
        statusText = text("")
        content.addView(statusText)
        content.addView(button("Start sharing") {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), projectionRequestCode)
        })
        content.addView(button("Stop sharing") {
            stopService(Intent(this, ScreenShareService::class.java))
            statusText.text = "Sharing stopped"
        })
        content.addView(button("Back") { showHome() })
        setContentView(content)
    }

    private fun showSenderStarted() {
        showSender()
        statusText.text = "Waiting for the TV to connect on port ${ScreenShareService.PORT}."
    }

    private fun showReceiver() {
        val content = column()
        content.addView(title("Watch a phone"))
        content.addView(text("Enter the phone's Wi-Fi address shown in ScreenSharer, then connect."))
        val address = EditText(this).apply {
            hint = "Phone IP address"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        content.addView(address)
        statusText = text("Waiting to connect")
        content.addView(statusText)
        val preview = SurfaceView(this)
        preview.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                receiverSurface = holder.surface
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                receiverSurface = holder.surface
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                receiverSurface = null
                ScreenReceiver.stop()
            }
        })
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        content.addView(button("Connect") {
            val ip = address.text.toString().trim()
            val surface = receiverSurface
            if (ip.isBlank() || surface == null) {
                statusText.text = "Enter a phone address and wait for the screen to initialize."
            } else {
                statusText.text = "Connecting..."
                ScreenReceiver.connect(ip, ScreenShareService.PORT, surface) { message ->
                    runOnUiThread { statusText.text = message }
                }
            }
        })
        content.addView(button("Disconnect") {
            ScreenReceiver.stop()
            statusText.text = "Disconnected"
        })
        if (!packageManager.hasSystemFeature("android.software.leanback")) {
            content.addView(button("Back") { showHome() })
        }
        setContentView(content)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(32, 24, 32, 24)
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 28f
        setPadding(0, 0, 0, 20)
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, 8, 0, 8)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun localIpAddress(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val addresses = mutableListOf<String>()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val candidates = networkInterface.inetAddresses
            while (candidates.hasMoreElements()) {
                val address = candidates.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address && address.isSiteLocalAddress) {
                    addresses.add(address.hostAddress ?: continue)
                }
            }
        }
        addresses.firstOrNull { it.startsWith("192.168.") }
            ?: addresses.firstOrNull { it.startsWith("10.") }
            ?: addresses.firstOrNull()
    } catch (_: Exception) {
        null
    }
}