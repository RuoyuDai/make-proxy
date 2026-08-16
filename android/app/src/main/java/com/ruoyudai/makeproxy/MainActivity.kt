package com.ruoyudai.makeproxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN_CONSENT = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button

    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        bindEdit(R.id.editServer, prefs.getString("server_addr", ""))
        bindEdit(R.id.editPort, prefs.getString("server_port", "57071"))
        bindEdit(R.id.editUsername, prefs.getString("username", ""))
        bindEdit(R.id.editPassword, prefs.getString("password", ""))
        bindEdit(R.id.editLocalPort, prefs.getString("local_port", "7070"))

        statusView = findViewById(R.id.textStatus)
        toggleButton = findViewById(R.id.buttonToggle)
        findViewById<TextView>(R.id.textVersion).text = "version: ${BuildConfig.VERSION_NAME}"
        toggleButton.setOnClickListener {
            if (ProxyVpnService.isRunning) {
                stopVpn()
            } else {
                saveConfig()
                startVpnWithConsent()
            }
            refreshStatus()
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusPoller)
        refreshLog()
        DiagLog.listener = { runOnUiThread { refreshLog() } }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
        DiagLog.listener = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_CONSENT && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun bindEdit(id: Int, value: String?) {
        findViewById<EditText>(id).setText(value ?: "")
    }

    private fun saveConfig() {
        getSharedPreferences("config", Context.MODE_PRIVATE).edit()
            .putString("server_addr", findViewById<EditText>(R.id.editServer).text.toString().trim())
            .putString("server_port", findViewById<EditText>(R.id.editPort).text.toString().trim())
            .putString("username", findViewById<EditText>(R.id.editUsername).text.toString().trim())
            .putString("password", findViewById<EditText>(R.id.editPassword).text.toString())
            .putString("local_port", findViewById<EditText>(R.id.editLocalPort).text.toString().trim())
            .apply()
    }

    private fun startVpnWithConsent() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN_CONSENT)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, ProxyVpnService::class.java)
        startForegroundService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, ProxyVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
    }

    private fun refreshLog() {
        findViewById<TextView>(R.id.textLog).text =
            DiagLog.snapshot().take(20).joinToString("\n")
    }

    private fun refreshStatus() {
        statusView.text = "status: ${ProxyVpnService.status}"
        toggleButton.text = if (ProxyVpnService.isRunning) "Stop" else "Start"
    }
}
