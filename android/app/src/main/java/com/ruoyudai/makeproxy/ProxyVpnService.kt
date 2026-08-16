package com.ruoyudai.makeproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import tun2proxy.Tun2proxy

/**
 * VpnService-based global proxy. Captures all device traffic on a TUN
 * interface and feeds it to the local SOCKS5 server (LocalProxyServer),
 * which tunnels it to the make-proxy server. TCP goes through the tunnel;
 * DNS is answered via DNS-over-TCP through the tunnel; other UDP (QUIC)
 * is dropped so apps fall back to TCP.
 */
class ProxyVpnService : VpnService() {

    companion object {
        @Volatile
        var status: String = "stopped"
            private set

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var server: LocalProxyServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val serverAddr = prefs.getString("server_addr", "") ?: ""
        val serverPort = prefs.getString("server_port", "57071")?.toIntOrNull() ?: 57071
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val localPort = prefs.getString("local_port", "7070")?.toIntOrNull() ?: 7070

        if (serverAddr.isEmpty() || username.isEmpty() || password.isEmpty()) {
            status = "config incomplete"
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(2, buildNotification("MakeProxy VPN is running"))

        // Local SOCKS5 endpoint consumed by the tun2socks engine.
        server = LocalProxyServer(localPort, serverAddr, serverPort, username, password,
            onError = { err ->
                status = err
                DiagLog.add("ERR $err")
            },
            onConnectionChange = { count ->
                status = if (count > 0) {
                    "VPN: $count connection(s) via $serverAddr"
                } else {
                    "VPN running -> $serverAddr:$serverPort"
                }
            }
        )
        server?.start()

        val tun = Builder()
            .setSession("MakeProxy")
            .addAddress("26.26.26.1", 30)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .addDisallowedApplication(packageName)
            .establish()

        if (tun == null) {
            status = "failed to establish VPN"
            DiagLog.add(status)
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        vpnInterface = tun

        // Ownership of the raw fd moves to the tun2proxy engine.
        val fd = tun.detachFd()
        isRunning = true
        status = "VPN running -> $serverAddr:$serverPort"
        DiagLog.add("VPN established")

        Thread {
            try {
                Tun2proxy.start(fd.toLong(), "127.0.0.1:$localPort")
            } catch (e: Exception) {
                DiagLog.add("tun2proxy error: ${e.message}")
                status = "tun2proxy error: ${e.message}"
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun stopVpn() {
        try {
            Tun2proxy.stop()
        } catch (_: Exception) {
        }
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        server?.stop()
        server = null
        isRunning = false
        status = "stopped"
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "proxy_vpn"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, "MakeProxy VPN", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("MakeProxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }
}
