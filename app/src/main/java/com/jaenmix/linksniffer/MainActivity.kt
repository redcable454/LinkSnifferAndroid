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

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            statusText.text = "Estado: capturando ${selectedPackage ?: ""}"
            Toast.makeText(this, "Captura iniciada. Abre y usa la app seleccionada.", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "Estado: captura no iniciada"
            stopService(Intent(this, CaptureListenerService::class.java))
            Toast.makeText(this, "PCAPdroid no autorizó la captura.", Toast.LENGTH_LONG).show()
        }
    }

    private val stopLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        stopService(Intent(this, CaptureListenerService::class.java))
        statusText.text = "Estado: detenido"
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
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
            refresh()
        }
        mediaOnly.setOnCheckedChangeListener { _, _ -> refresh() }
        list.setOnItemClickListener { _, _, position, _ ->
            val value = shown[position].removePrefix("host://")
            val cb = getSystemService(ClipboardManager::class.java)
            cb.setPrimaryClip(ClipData.newPlainText("Link Sniffer", value))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
        requestNotificationPermission()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(CaptureListenerService.ACTION_NEW_ITEM), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    private fun loadLaunchableApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        apps.clear()
        apps.addAll(
            resolved
                .map { info ->
                    AppItem(
                        info.loadLabel(packageManager)?.toString()?.ifBlank { info.activityInfo.packageName }
                            ?: info.activityInfo.packageName,
                        info.activityInfo.packageName
                    )
                }
                .filter { it.packageName != packageName && it.packageName != "com.emanuelef.remote_capture" }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        )

        appSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        if (apps.isNotEmpty()) {
            selectedPackage = apps.first().packageName
            selectedPackageText.text = "Paquete: ${apps.first().packageName}"
        }
    }

    private fun startCapture() {
        val targetPackage = selectedPackage
        if (targetPackage.isNullOrBlank()) {
            Toast.makeText(this, "Selecciona una app primero.", Toast.LENGTH_LONG).show()
            return
        }

        if (!isPcapdroidInstalled()) {
            Toast.makeText(this, "Instala PCAPdroid primero.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.emanuelef.remote_capture")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture")))
            }
            return
        }

        CaptureStore.clear(this)
        refresh()
        ContextCompat.startForegroundService(this, Intent(this, CaptureListenerService::class.java))

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl")
            putExtra("action", "start")
            putExtra("pcap_dump_mode", "udp_exporter")
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
            runCatching { stopLauncher.launch(intent) }
                .onFailure {
                    stopService(Intent(this, CaptureListenerService::class.java))
                    statusText.text = "Estado: detenido"
                }
        } else {
            stopService(Intent(this, CaptureListenerService::class.java))
            statusText.text = "Estado: detenido"
        }
    }

    private fun refresh() {
        val media = Regex("(?i)(\\.m3u8(?:[?&#]|$)|\\.mpd(?:[?&#]|$)|\\.m4s(?:[?&#]|$)|\\.ts(?:[?&#]|$))")
        shown.clear()
        shown.addAll(CaptureStore.read(this).filter { value -> !mediaOnly.isChecked || media.containsMatchIn(value) })
        adapter.notifyDataSetChanged()
    }

    private fun isPcapdroidInstalled(): Boolean = try {
        packageManager.getPackageInfo("com.emanuelef.remote_capture", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
