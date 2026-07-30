package com.ruoyudai.makeproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Foreground service that keeps the local proxy alive in the background. */
class ProxyService : Service() {

    companion object {
        @Volatile
        var status: String = "stopped"
            private set

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var server: LocalProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val serverAddr = prefs.getString("server_addr", "") ?: ""
        val serverPort = prefs.getString("server_port", "7071")?.toIntOrNull() ?: 7071
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val localPort = prefs.getString("local_port", "7070")?.toIntOrNull() ?: 7070

        if (serverAddr.isEmpty() || username.isEmpty() || password.isEmpty()) {
            status = "config incomplete"
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, buildNotification("local proxy is starting"))

        server = LocalProxyServer(localPort, serverAddr, serverPort, username, password,
            onError = { err -> status = err },
            onConnectionChange = { count ->
                status = if (count > 0) {
                    "$count connection(s) active on 127.0.0.1:$localPort"
                } else {
                    "listening on 127.0.0.1:$localPort -> $serverAddr:$serverPort"
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(1, buildNotification(status))
            }
        )
        server?.start()
        isRunning = true
        status = "listening on 127.0.0.1:$localPort -> $serverAddr:$serverPort"
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        status = "stopped"
        super.onDestroy()
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = "proxy"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "MakeProxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("MakeProxy")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }
}
