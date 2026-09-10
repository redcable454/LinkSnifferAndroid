package com.jaenmix.linksniffer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class CaptureListenerService : Service() {
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Link Sniffer")
                .setContentText("Escuchando tráfico local de PCAPdroid")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()
        )
        startListener()
    }

    private fun startListener() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            try {
                DatagramSocket(PORT, InetAddress.getByName("127.0.0.1")).use { s ->
                    socket = s
                    val buf = ByteArray(65535)
                    while (running.get()) {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        analyze(packet.data, packet.length)
                    }
                }
            } catch (_: Exception) {
            } finally {
                socket = null
                running.set(false)
            }
        }.apply { name = "LinkSnifferUdp"; start() }
    }

    private fun analyze(bytes: ByteArray, len: Int) {
        val text = buildString(len) {
            for (i in 0 until len) {
                val v = bytes[i].toInt() and 0xff
                append(if (v in 32..126) v.toChar() else ' ')
            }
        }

        val candidates = linkedSetOf<String>()
        URL_REGEX.findAll(text).forEach { m ->
            val cleaned = m.value.trimEnd('.', ',', ';', ')', ']', '}', '\'', '"')
            if (cleaned.length <= 2048) candidates.add(cleaned)
        }
        HOST_REGEX.findAll(text).forEach { m ->
            val host = m.value.lowercase()
            if (host.length <= 253 && !host.matches(Regex("^\\d+(\\.\\d+){3}$"))) {
                candidates.add("host://$host")
            }
        }

        for (candidate in candidates) {
            CaptureStore.add(this, candidate)
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName).putExtra("value", candidate))
        }
    }

    override fun onDestroy() {
        running.set(false)
        socket?.close()
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Captura local", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val PORT = 5123
        const val ACTION_NEW_ITEM = "com.jaenmix.linksniffer.NEW_ITEM"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 7001

        private val URL_REGEX = Regex("https?://[^\\s<>\\\"']+", setOf(RegexOption.IGNORE_CASE))
        private val HOST_REGEX = Regex("(?i)(?<![A-Za-z0-9_-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|tv|pe|io|app|cloud|live|stream|cdn|xyz|co|me)(?![A-Za-z0-9_-])")
    }
}
