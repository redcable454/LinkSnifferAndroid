package site.teleclub.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {
    private lateinit var inputUrl: EditText
    private lateinit var inputHosts: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var delivered = false
    private var allowedHosts: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(8, 19, 31))
        }

        root.addView(TextView(this).apply {
            text = "Teleclub TV · Demo"
            setTextColor(Color.WHITE)
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Página autorizada → detecta video → reproductor nativo"
            setTextColor(Color.LTGRAY)
        })

        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Página autorizada", "Enlace directo")
            )
        }
        root.addView(modeSpinner, LinearLayout.LayoutParams(-1, dp(48)))

        inputUrl = EditText(this).apply {
            hint = "https://tu-dominio.com/pelicula o .../playlist.m3u8"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        root.addView(inputUrl, LinearLayout.LayoutParams(-1, dp(52)))

        inputHosts = EditText(this).apply {
            hint = "CDN autorizado opcional: cdn.tudominio.com"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        root.addView(inputHosts, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(
            Button(this).apply {
                text = "PROBAR"
                setOnClickListener { startPlayback() }
            },
            LinearLayout.LayoutParams(-1, dp(50))
        )

        status = TextView(this).apply {
            text = "Listo. Usa una fuente propia o autorizada."
            setTextColor(Color.rgb(126, 220, 160))
        }
        root.addView(status)

        val stage = FrameLayout(this)
        webView = WebView(this).apply { visibility = View.GONE }
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }

        stage.addView(webView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Solo fuentes propias o autorizadas. No evade DRM, tokens ni protecciones de terceros."
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        })

        setContentView(root)
    }

    private fun startPlayback() {
        val url = inputUrl.text.toString().trim()
        if (url.isBlank()) {
            status.text = "Escribe una URL."
            return
        }

        delivered = false
        releasePlayer()
        webView.stopLoading()

        if (modeSpinner.selectedItemPosition == 1) {
            playDirect(url)
        } else {
            openAuthorizedPage(url)
        }
    }

    private fun mediaMime(url: String): String? {
        val p = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            p.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            p.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    private fun hostAllowed(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host in allowedHosts
    }

    private fun deliverIfAllowed(url: String): Boolean {
        if (delivered) return false
        val mime = mediaMime(url) ?: return false
        if (!hostAllowed(url)) return false

        delivered = true
        runOnUiThread {
            status.text = "Video detectado. Abriendo reproductor..."
            playDirect(url, mime)
        }
        return true
    }

    private fun playDirect(url: String, forcedMime: String? = null) {
        val mime = forcedMime ?: mediaMime(url) ?: run {
            status.text = "Usa .m3u8, .mpd o .mp4."
            return
        }

        webView.stopLoading()
        webView.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .build()

        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(item)
            it.prepare()
            it.playWhenReady = true
        }

        status.text = "Reproduciendo dentro de Teleclub con Media3 / ExoPlayer."
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openAuthorizedPage(pageUrl: String) {
        val pageHost = Uri.parse(pageUrl).host?.lowercase()
        if (pageHost.isNullOrBlank()) {
            status.text = "URL no válida."
            return
        }

        val hosts = mutableSetOf(pageHost)
        inputHosts.text.toString()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .forEach { hosts.add(it) }
        allowedHosts = hosts

        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase()
                return host == null || host !in allowedHosts
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                scanDomForVideo(view)
                view.postDelayed({ if (!delivered) scanDomForVideo(view) }, 1200)
                view.postDelayed({ if (!delivered) scanDomForVideo(view) }, 3000)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!delivered && request != null && request.method == "GET") {
                    deliverIfAllowed(request.url.toString())
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        status.text = "Cargando página autorizada y buscando video..."
        webView.loadUrl(pageUrl)
    }

    private fun scanDomForVideo(view: WebView) {
        val js = """
            (function() {
              try {
                var urls = [];
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  if (videos[i].currentSrc) urls.push(videos[i].currentSrc);
                  if (videos[i].src) urls.push(videos[i].src);
                }
                var sources = document.querySelectorAll('video source, source[type*="video"], source[type*="mpegurl"], source[type*="dash"]');
                for (var j = 0; j < sources.length; j++) {
                  if (sources[j].src) urls.push(sources[j].src);
                }
                for (var k = 0; k < urls.length; k++) {
                  if (/\\.(m3u8|mpd|mp4)(\\?|$)/i.test(urls[k])) return urls[k];
                }
                return "";
              } catch (e) {
                return "";
              }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            if (delivered || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            val candidate = try {
                org.json.JSONTokener(raw).nextValue() as? String
            } catch (_: Exception) {
                null
            }
            if (!candidate.isNullOrBlank()) {
                deliverIfAllowed(candidate)
            }
        }
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        releasePlayer()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
