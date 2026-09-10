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
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CaptureListenerService : Service() {
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Link Sniffer")
                .setContentText("Escuchando paquetes de PCAPdroid")
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
                // Igual que PCAPReceiver oficial: escuchar el puerto en todas las interfaces.
                DatagramSocket(PORT).use { s ->
                    socket = s
                    val buf = ByteArray(65535)
                    while (running.get()) {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        val len = packet.length
                        val count = packetCount.incrementAndGet()
                        broadcastPacketCount(count)

                        // PCAPdroid UDP exporter envía primero cabecera PCAP de 24 bytes.
                        if (isPcapHeader(buf, len)) continue
                        if (len <= PCAP_RECORD_HEADER_SIZE) continue

                        // Cada datagrama posterior empieza con pcaprec_hdr_s (16 bytes).
                        analyze(buf, PCAP_RECORD_HEADER_SIZE, len - PCAP_RECORD_HEADER_SIZE)
                    }
                }
            } catch (e: Exception) {
                if (e !is SocketException && running.get()) {
                    sendBroadcast(Intent(ACTION_CAPTURE_ERROR).setPackage(packageName).putExtra("error", e.message ?: "Error UDP"))
                }
            } finally {
                socket = null
                running.set(false)
            }
        }.apply { name = "LinkSnifferUdp"; start() }
    }

    private fun isPcapHeader(bytes: ByteArray, len: Int): Boolean {
        if (len != 24 || len < 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = bytes[2].toInt() and 0xff
        val b3 = bytes[3].toInt() and 0xff
        return (b0 == 0xd4 && b1 == 0xc3 && b2 == 0xb2 && b3 == 0xa1) ||
            (b0 == 0xa1 && b1 == 0xb2 && b2 == 0xc3 && b3 == 0xd4)
    }

    private fun analyze(bytes: ByteArray, offset: Int, len: Int) {
        if (len <= 0) return
        val end = (offset + len).coerceAtMost(bytes.size)
        val text = buildString(end - offset) {
            for (i in offset until end) {
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

    private fun broadcastPacketCount(count: Long) {
        sendBroadcast(Intent(ACTION_PACKET_COUNT).setPackage(packageName).putExtra("count", count))
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
        const val ACTION_PACKET_COUNT = "com.jaenmix.linksniffer.PACKET_COUNT"
        const val ACTION_CAPTURE_ERROR = "com.jaenmix.linksniffer.CAPTURE_ERROR"
        private const val PCAP_RECORD_HEADER_SIZE = 16
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 7001

        private val URL_REGEX = Regex("https?://[^\\s<>\\\"']+", setOf(RegexOption.IGNORE_CASE))
        private val HOST_REGEX = Regex("(?i)(?<![A-Za-z0-9_-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|tv|pe|io|app|cloud|live|stream|cdn|xyz|co|me)(?![A-Za-z0-9_-])")
    }
}
