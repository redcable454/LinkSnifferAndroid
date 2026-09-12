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

    private val adHostFragments = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.",
        "adskeeper.",
        "adsterra.",
        "propellerads.",
        "popads.",
        "popcash.",
        "exoclick.",
        "trafficjunky.",
        "juicyads.",
        "clickadu.",
        "hilltopads.",
        "onclicka.",
        "richads.",
        "monetag.",
        "pushground.",
        "ad-maven.",
        "admaven.",
        "revcontent.",
        "taboola.",
        "outbrain."
    )

    private fun isAdUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val full = url.lowercase()

        if (adHostFragments.any { host.contains(it) }) return true

        val suspiciousPath = listOf(
            "/popunder", "/popup", "/interstitial", "/vast/", "/vpaid/",
            "/ads/", "/adserver/", "/banner/", "adclick", "ad_redirect",
            "trackingpixel", "clickunder"
        )
        return suspiciousPath.any { full.contains(it) }
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", null)


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
        val p = url.lowercase()
        return when {
            Regex("""(^|[^a-z0-9])m3u8([^a-z0-9]|$)|\.m3u8(?:[?#&]|$)""").containsMatchIn(p) -> MimeTypes.APPLICATION_M3U8
            Regex("""(^|[^a-z0-9])mpd([^a-z0-9]|$)|\.mpd(?:[?#&]|$)""").containsMatchIn(p) -> MimeTypes.APPLICATION_MPD
            Regex("""\.mp4(?:[?#&]|$)""").containsMatchIn(p) -> MimeTypes.VIDEO_MP4
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

    inner class DetectorBridge {
        @JavascriptInterface
        fun report(url: String?) {
            if (!url.isNullOrBlank()) deliverIfAllowed(url)
        }
    }

    private fun installDetectorHooks(view: WebView) {
        val js = """
            (function() {
              if (window.__teleclubDetectorInstalled) return;
              window.__teleclubDetectorInstalled = true;

              function report(u) {
                try {
                  if (!u) return;
                  var s = String(u);
                  if (window.TeleclubDetector) window.TeleclubDetector.report(s);
                } catch(e) {}
              }

              function reportVideo(video) {
                try {
                  report(video.currentSrc || video.src || "");
                  var sources = video.querySelectorAll('source');
                  for (var i = 0; i < sources.length; i++) {
                    report(sources[i].src || sources[i].getAttribute('src') || "");
                  }
                } catch(e) {}
              }

              function bindVideo(video) {
                try {
                  if (!video || video.__teleclubBound) return;
                  video.__teleclubBound = true;

                  ['play','playing','loadedmetadata','canplay','click','touchend'].forEach(function(ev) {
                    video.addEventListener(ev, function() { reportVideo(video); }, true);
                  });

                  reportVideo(video);

                  try {
                    new MutationObserver(function() { reportVideo(video); })
                      .observe(video, {attributes:true, childList:true, subtree:true, attributeFilter:['src']});
                  } catch(e) {}
                } catch(e) {}
              }

              function scanVideos() {
                try {
                  var videos = document.querySelectorAll('video');
                  for (var i = 0; i < videos.length; i++) bindVideo(videos[i]);
                } catch(e) {}
              }

              document.addEventListener('play', function(e) {
                try {
                  var v = e.target && e.target.tagName === 'VIDEO' ? e.target : null;
                  if (v) reportVideo(v);
                } catch(e) {}
              }, true);

              document.addEventListener('click', function(e) {
                try {
                  var v = e.target && e.target.closest ? e.target.closest('video') : null;
                  if (v) reportVideo(v);
                } catch(e) {}
              }, true);

              try {
                var oldFetch = window.fetch;
                if (oldFetch) {
                  window.fetch = function() {
                    try {
                      var x = arguments[0];
                      report(typeof x === 'string' ? x : (x && x.url ? x.url : ''));
                    } catch(e) {}
                    return oldFetch.apply(this, arguments).then(function(r) {
                      try { report(r && r.url ? r.url : ''); } catch(e) {}
                      return r;
                    });
                  };
                }
              } catch(e) {}

              try {
                var oldOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  try { report(url); } catch(e) {}
                  return oldOpen.apply(this, arguments);
                };
              } catch(e) {}

              try {
                new MutationObserver(function(mutations) {
                  scanVideos();
                  for (var i = 0; i < mutations.length; i++) {
                    var nodes = mutations[i].addedNodes || [];
                    for (var j = 0; j < nodes.length; j++) {
                      var n = nodes[j];
                      if (n && n.tagName === 'VIDEO') bindVideo(n);
                      if (n && n.querySelectorAll) {
                        var vs = n.querySelectorAll('video');
                        for (var k = 0; k < vs.length; k++) bindVideo(vs[k]);
                      }
                    }
                  }
                }).observe(document.documentElement || document, {childList:true, subtree:true});
              } catch(e) {}

              scanVideos();
              setInterval(scanVideos, 1500);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
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
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                status.text = "Popup bloqueado"
                return false
            }
        }
        webView.removeJavascriptInterface("TeleclubDetector")
        webView.addJavascriptInterface(DetectorBridge(), "TeleclubDetector")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (isAdUrl(u)) {
                    status.text = "Redirección publicitaria bloqueada"
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                installDetectorHooks(view)
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                if (!delivered) deliverIfAllowed(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installDetectorHooks(view)
                scanDomForVideo(view)
                view.postDelayed({ if (!delivered) scanDomForVideo(view) }, 1200)
                view.postDelayed({ if (!delivered) scanDomForVideo(view) }, 3000)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && request.method == "GET") {
                    val u = request.url.toString()
                    if (isAdUrl(u)) {
                        return blockedResponse()
                    }
                    if (!delivered) {
                        deliverIfAllowed(u)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        status.text = "Cargando página con bloqueador y buscando video..."
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

                var sources = document.querySelectorAll('video source, source');
                for (var j = 0; j < sources.length; j++) {
                  if (sources[j].src) urls.push(sources[j].src);
                }

                try {
                  var entries = performance.getEntriesByType('resource') || [];
                  for (var k = 0; k < entries.length; k++) {
                    if (entries[k] && entries[k].name) urls.push(entries[k].name);
                  }
                } catch(e) {}

                for (var n = 0; n < urls.length; n++) {
                  try {
                    if (window.TeleclubDetector) window.TeleclubDetector.report(String(urls[n]));
                  } catch(e) {}
                }
                return String(urls.length);
              } catch (e) {
                return "0";
              }
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
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
