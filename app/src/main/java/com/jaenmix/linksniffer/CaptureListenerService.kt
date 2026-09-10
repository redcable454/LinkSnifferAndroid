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
                .setContentText("Analizando tráfico visible de PCAPdroid")
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
                DatagramSocket(PORT).use { s ->
                    socket = s
                    val buf = ByteArray(65535)
                    while (running.get()) {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        val len = packet.length
                        val count = packetCount.incrementAndGet()
                        broadcastPacketCount(count)

                        if (isPcapHeader(buf, len)) continue
                        if (len <= PCAP_RECORD_HEADER_SIZE) continue

                        analyzePacket(buf, PCAP_RECORD_HEADER_SIZE, len - PCAP_RECORD_HEADER_SIZE)
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
        val b0 = u8(bytes, 0); val b1 = u8(bytes, 1); val b2 = u8(bytes, 2); val b3 = u8(bytes, 3)
        return (b0 == 0xd4 && b1 == 0xc3 && b2 == 0xb2 && b3 == 0xa1) ||
            (b0 == 0xa1 && b1 == 0xb2 && b2 == 0xc3 && b3 == 0xd4)
    }

    private fun analyzePacket(bytes: ByteArray, offset: Int, len: Int) {
        if (len <= 0 || offset >= bytes.size) return
        val end = (offset + len).coerceAtMost(bytes.size)
        val version = u8(bytes, offset) ushr 4
        when (version) {
            4 -> analyzeIpv4(bytes, offset, end)
            6 -> analyzeIpv6(bytes, offset, end)
        }
        scanVisibleText(bytes, offset, end)
    }

    private fun analyzeIpv4(b: ByteArray, o: Int, end: Int) {
        if (o + 20 > end) return
        val ihl = (u8(b, o) and 0x0f) * 4
        if (ihl < 20 || o + ihl > end) return
        val proto = u8(b, o + 9)
        val src = ipv4(b, o + 12)
        val dst = ipv4(b, o + 16)
        val l4 = o + ihl
        analyzeTransport(b, l4, end, proto, src, dst)
    }

    private fun analyzeIpv6(b: ByteArray, o: Int, end: Int) {
        if (o + 40 > end) return
        val proto = u8(b, o + 6)
        val src = runCatching { InetAddress.getByAddress(b.copyOfRange(o + 8, o + 24)).hostAddress }.getOrNull() ?: return
        val dst = runCatching { InetAddress.getByAddress(b.copyOfRange(o + 24, o + 40)).hostAddress }.getOrNull() ?: return
        analyzeTransport(b, o + 40, end, proto, src, dst)
    }

    private fun analyzeTransport(b: ByteArray, l4: Int, end: Int, proto: Int, src: String, dst: String) {
        if (l4 + 4 > end) return
        val srcPort = u16(b, l4)
        val dstPort = u16(b, l4 + 2)
        val protoName = when (proto) { 6 -> "TCP"; 17 -> "UDP"; else -> "IP-$proto" }
        addItem("flow://$protoName $src:$srcPort -> $dst:$dstPort")

        val payloadOffset = when (proto) {
            6 -> {
                if (l4 + 20 > end) return
                val tcpHeader = ((u8(b, l4 + 12) ushr 4) and 0x0f) * 4
                (l4 + tcpHeader).coerceAtMost(end)
            }
            17 -> (l4 + 8).coerceAtMost(end)
            else -> return
        }

        if (proto == 17 && (srcPort == 53 || dstPort == 53)) {
            parseDnsQuery(b, payloadOffset, end)?.let { addItem("dns://$it") }
        }
        if (proto == 6 && (srcPort == 443 || dstPort == 443)) {
            parseTlsSni(b, payloadOffset, end)?.let { addItem("sni://$it") }
        }
    }

    private fun parseDnsQuery(b: ByteArray, start: Int, end: Int): String? {
        if (start + 12 >= end) return null
        var p = start + 12
        val labels = mutableListOf<String>()
        while (p < end) {
            val n = u8(b, p++)
            if (n == 0) break
            if (n > 63 || p + n > end) return null
            val label = String(b, p, n, Charsets.US_ASCII)
            if (!label.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            labels.add(label)
            p += n
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")?.lowercase()
    }

    private fun parseTlsSni(b: ByteArray, start: Int, end: Int): String? {
        if (start + 5 >= end || u8(b, start) != 0x16) return null
        var p = start + 5
        if (p + 4 >= end || u8(b, p) != 0x01) return null
        p += 4
        if (p + 34 > end) return null
        p += 34
        if (p >= end) return null
        val sessionLen = u8(b, p); p += 1 + sessionLen
        if (p + 2 > end) return null
        val cipherLen = u16(b, p); p += 2 + cipherLen
        if (p >= end) return null
        val compLen = u8(b, p); p += 1 + compLen
        if (p + 2 > end) return null
        val extTotal = u16(b, p); p += 2
        val extEnd = (p + extTotal).coerceAtMost(end)
        while (p + 4 <= extEnd) {
            val type = u16(b, p); val size = u16(b, p + 2); p += 4
            if (p + size > extEnd) return null
            if (type == 0 && size >= 5) {
                var q = p + 2
                while (q + 3 <= p + size) {
                    val nameType = u8(b, q)
                    val nameLen = u16(b, q + 1)
                    q += 3
                    if (q + nameLen > p + size) break
                    if (nameType == 0) {
                        val host = String(b, q, nameLen, Charsets.US_ASCII).trim().lowercase()
                        if (host.matches(Regex("[a-z0-9.-]{1,253}"))) return host
                    }
                    q += nameLen
                }
            }
            p += size
        }
        return null
    }

    private fun scanVisibleText(bytes: ByteArray, offset: Int, end: Int) {
        val text = buildString(end - offset) {
            for (i in offset until end) {
                val v = u8(bytes, i)
                append(if (v in 32..126) v.toChar() else ' ')
            }
        }
        URL_REGEX.findAll(text).forEach { m ->
            val cleaned = m.value.trimEnd('.', ',', ';', ')', ']', '}', '\'', '"')
            if (cleaned.length <= 2048) addItem(cleaned)
        }
        HOST_REGEX.findAll(text).forEach { m ->
            val host = m.value.lowercase()
            if (host.length <= 253 && !host.matches(Regex("^\\d+(\\.\\d+){3}$"))) addItem("host://$host")
        }
    }

    private fun addItem(value: String) {
        CaptureStore.add(this, value)
        sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName).putExtra("value", value))
    }

    private fun broadcastPacketCount(count: Long) {
        sendBroadcast(Intent(ACTION_PACKET_COUNT).setPackage(packageName).putExtra("count", count))
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun ipv4(b: ByteArray, i: Int) = "${u8(b,i)}.${u8(b,i+1)}.${u8(b,i+2)}.${u8(b,i+3)}"

    override fun onDestroy() {
        running.set(false)
        socket?.close()
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
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
