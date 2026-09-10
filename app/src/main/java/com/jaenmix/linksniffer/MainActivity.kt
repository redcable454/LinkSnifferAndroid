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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var packageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var mediaOnly: CheckBox
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val shown = mutableListOf<String>()

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            statusText.text = "Estado: capturando"
            Toast.makeText(this, "Captura iniciada. Ahora abre la app objetivo.", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "Estado: permiso/captura no iniciada"
            stopService(Intent(this, CaptureListenerService::class.java))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        packageInput = findViewById(R.id.packageInput)
        statusText = findViewById(R.id.statusText)
        mediaOnly = findViewById(R.id.mediaOnly)
        list = findViewById(R.id.resultsList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        list.adapter = adapter

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
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun startCapture() {
        if (!isPcapdroidInstalled()) {
            Toast.makeText(this, "Instala PCAPdroid primero.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.emanuelef.remote_capture")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture")))
            }
            return
        }

        ContextCompat.startForegroundService(this, Intent(this, CaptureListenerService::class.java))

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl")
            putExtra("action", "start")
            putExtra("pcap_dump_mode", "udp_exporter")
            putExtra("collector_host", "127.0.0.1")
            putExtra("collector_port", CaptureListenerService.PORT)
            val targetPackage = packageInput.text.toString().trim()
            if (targetPackage.isNotEmpty()) putExtra("app_filter", targetPackage)
        }
        captureLauncher.launch(intent)
    }

    private fun stopCapture() {
        if (isPcapdroidInstalled()) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl")
                putExtra("action", "stop")
            }
            runCatching { startActivity(intent) }
        }
        stopService(Intent(this, CaptureListenerService::class.java))
        statusText.text = "Estado: detenido"
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
