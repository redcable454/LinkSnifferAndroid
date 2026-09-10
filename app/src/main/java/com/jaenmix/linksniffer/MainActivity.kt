package com.jaenmix.linksniffer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    data class AppItem(val label: String, val packageName: String) {
        override fun toString(): String = "$label ($packageName)"
    }

    private lateinit var appSpinner: Spinner
    private lateinit var selectedPackageText: TextView
    private lateinit var statusText: TextView
    private lateinit var mediaOnly: CheckBox
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val shown = mutableListOf<String>()
    private val apps = mutableListOf<AppItem>()
    private var selectedPackage: String? = null
    private var packetCount: Long = 0

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            packetCount = 0
            updateStatus()
            Toast.makeText(this, "Captura iniciada. Reproduce el contenido dentro de la app seleccionada.", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "Estado: captura no iniciada"
            stopService(Intent(this, CaptureListenerService::class.java))
            Toast.makeText(this, "PCAPdroid no autorizó la captura.", Toast.LENGTH_LONG).show()
        }
    }

    private val stopLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        stopService(Intent(this, CaptureListenerService::class.java))
        statusText.text = "Estado: detenido | Paquetes recibidos: $packetCount"
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureListenerService.ACTION_NEW_ITEM -> refresh()
                CaptureListenerService.ACTION_PACKET_COUNT -> {
                    packetCount = intent.getLongExtra("count", packetCount)
                    updateStatus()
                }
                CaptureListenerService.ACTION_CAPTURE_ERROR ->
                    statusText.text = "Error de captura: ${intent.getStringExtra("error") ?: "desconocido"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appSpinner = findViewById(R.id.appSpinner)
        selectedPackageText = findViewById(R.id.selectedPackageText)
        statusText = findViewById(R.id.statusText)
        mediaOnly = findViewById(R.id.mediaOnly)
        list = findViewById(R.id.resultsList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        list.adapter = adapter

        loadLaunchableApps()
        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedPackage = apps.getOrNull(position)?.packageName
                selectedPackageText.text = "Paquete: ${selectedPackage ?: "ninguno"}"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedPackage = null
                selectedPackageText.text = "Paquete: ninguno"
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopCapture() }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            CaptureStore.clear(this)
            packetCount = 0
            refresh()
            statusText.text = "Estado: detenido | Paquetes recibidos: 0"
        }
        mediaOnly.setOnCheckedChangeListener { _, _ -> refresh() }
        list.setOnItemClickListener { _, _, position, _ -> copyResult(shown[position]) }
        requestNotificationPermission()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CaptureListenerService.ACTION_NEW_ITEM)
            addAction(CaptureListenerService.ACTION_PACKET_COUNT)
            addAction(CaptureListenerService.ACTION_CAPTURE_ERROR)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    private fun updateStatus() {
        statusText.text = "Estado: capturando ${selectedPackage ?: ""} | Paquetes: $packetCount"
    }

    private fun copyResult(display: String) {
        if (display.startsWith("══") || display.startsWith("Sin ") || display.startsWith("Captura ")) return
        val value = display.substringBefore("  ×").substringBefore(" — ")
            .removePrefix("host://").removePrefix("dns://").removePrefix("sni://")
            .removePrefix("httphost://").removePrefix("path://")
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Link Sniffer", value))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun loadLaunchableApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") packageManager.queryIntentActivities(intent, 0)
        }
        apps.clear()
        apps.addAll(resolved.map { info ->
            AppItem(info.loadLabel(packageManager)?.toString()?.ifBlank { info.activityInfo.packageName }
                ?: info.activityInfo.packageName, info.activityInfo.packageName)
        }.filter { it.packageName != packageName && it.packageName != "com.emanuelef.remote_capture" }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() })
        appSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        if (apps.isNotEmpty()) {
            selectedPackage = apps.first().packageName
            selectedPackageText.text = "Paquete: ${apps.first().packageName}"
        }
    }

    private fun startCapture() {
        val targetPackage = selectedPackage
        if (targetPackage.isNullOrBlank()) {
            Toast.makeText(this, "Selecciona una app primero.", Toast.LENGTH_LONG).show(); return
        }
        if (!isPcapdroidInstalled()) {
            Toast.makeText(this, "Instala PCAPdroid primero.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.emanuelef.remote_capture"))) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture"))) }
            return
        }

        CaptureStore.clear(this); packetCount = 0; refresh()
        ContextCompat.startForegroundService(this, Intent(this, CaptureListenerService::class.java))

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl")
            putExtra("action", "start")
            putExtra("pcap_dump_mode", "udp_exporter")
            putExtra("collector_ip_address", "127.0.0.1")
            putExtra("collector_host", "127.0.0.1")
            putExtra("collector_port", CaptureListenerService.PORT)
            putExtra("app_filter", targetPackage)
            putExtra("full_payload", true)
            putExtra("snaplen", 65535)
            putExtra("ip_mode", "both")
        }
        captureLauncher.launch(intent)
    }

    private fun stopCapture() {
        if (isPcapdroidInstalled()) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl")
                putExtra("action", "stop")
            }
            runCatching { stopLauncher.launch(intent) }.onFailure {
                stopService(Intent(this, CaptureListenerService::class.java))
                statusText.text = "Estado: detenido | Paquetes recibidos: $packetCount"
            }
        } else {
            stopService(Intent(this, CaptureListenerService::class.java))
            statusText.text = "Estado: detenido | Paquetes recibidos: $packetCount"
        }
    }

    private fun refresh() {
        val entries = CaptureStore.readCounts(this)
        val mediaRegex = Regex("(?i)(\\.m3u8(?:[?&#/]|$)|\\.mpd(?:[?&#/]|$)|\\.m4s(?:[?&#/]|$)|\\.ts(?:[?&#/]|$))")
        val media = entries.filter { mediaRegex.containsMatchIn(it.first) }
        val hints = entries.filter { it.first.startsWith("hint://") }

        shown.clear()
        shown += "══ ENLACES MULTIMEDIA DETECTADOS ══"
        if (media.isEmpty()) shown += "Sin enlaces multimedia visibles"
        else shown += media.sortedByDescending { it.second }.map { (value, count) -> "$value  ×$count" }

        if (hints.isNotEmpty()) {
            shown += "══ INDICIOS DE STREAMING ══"
            shown += hints.map { (value, count) -> "${value.removePrefix("hint://")}  ×$count" }
        }

        if (!mediaOnly.isChecked) {
            val serverCounts = linkedMapOf<String, Int>()
            entries.filter {
                it.first.startsWith("sni://") || it.first.startsWith("dns://") ||
                    it.first.startsWith("host://") || it.first.startsWith("httphost://")
            }.forEach { (value, count) ->
                val host = value.substringAfter("://").lowercase()
                if (!isAuxiliaryHost(host)) {
                    val label = when {
                        value.startsWith("sni://") -> "$host — HTTPS/SNI"
                        value.startsWith("httphost://") -> "$host — HTTP/HOST"
                        value.startsWith("dns://") -> "$host — DNS"
                        else -> "$host — HOST"
                    }
                    serverCounts[label] = (serverCounts[label] ?: 0) + count
                }
            }

            shown += "══ POSIBLES SERVIDORES MULTIMEDIA ══"
            val likely = serverCounts.entries
                .filter { isLikelyMediaServer(it.key.substringBefore(" — ")) }
                .sortedByDescending { it.value }
            if (likely.isEmpty()) shown += "Sin servidor multimedia identificable todavía"
            else shown += likely.take(20).map { (label, count) -> "$label  ×$count" }

            shown += "══ SERVIDORES RELEVANTES ══"
            if (serverCounts.isEmpty()) shown += "Sin servidores identificados"
            else shown += serverCounts.entries.sortedByDescending { it.value }
                .take(40).map { (label, count) -> "$label  ×$count" }

            val flowCounts = linkedMapOf<String, Int>()
            entries.filter { it.first.startsWith("flow://") }.forEach { (value, count) ->
                val grouped = groupFlow(value)
                if (grouped != null) flowCounts[grouped] = (flowCounts[grouped] ?: 0) + count
            }

            shown += "══ CONEXIONES RELEVANTES ══"
            if (flowCounts.isEmpty()) shown += "Sin conexiones relevantes"
            else shown += flowCounts.entries.sortedByDescending { it.value }
                .take(40)
                .map { (label, count) -> "$label — $count eventos" }
        }
        adapter.notifyDataSetChanged()
    }

    private fun isAuxiliaryHost(host: String): Boolean {
        val h = host.lowercase()
        return AUXILIARY_HOST_PARTS.any { h.contains(it) }
    }

    private fun isLikelyMediaServer(host: String): Boolean {
        val h = host.lowercase()
        return MEDIA_HOST_HINTS.any { h.contains(it) } ||
            (!isAuxiliaryHost(h) && (h.contains("video") || h.contains("media") || h.contains("stream") || h.contains("cdn") || h.contains("play") || h.contains("movie")))
    }

    private fun groupFlow(value: String): String? {
        val match = Regex("^flow://(TCP|UDP) (.+):(\\d+) -> (.+):(\\d+)$").find(value)
            ?: return null
        val proto = match.groupValues[1]
        val aHost = match.groupValues[2]
        val aPort = match.groupValues[3].toIntOrNull() ?: 0
        val bHost = match.groupValues[4]
        val bPort = match.groupValues[5].toIntOrNull() ?: 0

        if (aPort == 53 || bPort == 53 || aPort == 853 || bPort == 853) return null

        val knownPorts = setOf(80, 443, 8080, 8443)
        val (host, port) = when {
            aPort in knownPorts && bPort !in knownPorts -> aHost to aPort
            bPort in knownPorts && aPort !in knownPorts -> bHost to bPort
            aPort in 1..32767 && bPort > 32767 -> aHost to aPort
            bPort in 1..32767 && aPort > 32767 -> bHost to bPort
            else -> if ("$aHost:$aPort" <= "$bHost:$bPort") aHost to aPort else bHost to bPort
        }
        val service = when (port) {
            443, 8443 -> "HTTPS"
            80, 8080 -> "HTTP"
            else -> "puerto $port"
        }
        return "$host:$port — $proto/$service"
    }

    private fun isPcapdroidInstalled(): Boolean = try { packageManager.getPackageInfo("com.emanuelef.remote_capture", 0); true }
    catch (_: PackageManager.NameNotFoundException) { false }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private val AUXILIARY_HOST_PARTS = listOf(
            "facebook.com", "googleapis.com", "google.com", "gstatic.com", "firebase", "crashlytics",
            "doubleclick", "app-measurement", "googlesyndication", "google-analytics", "cloudflare-dns"
        )
        private val MEDIA_HOST_HINTS = listOf(
            "m3u8", "dash", "hls", "manifest", "vod", "edge", "akamai", "fastly", "bunny", "cloudfront",
            "jwplayer", "player", "stream", "video", "media", "cdn"
        )
    }
}
