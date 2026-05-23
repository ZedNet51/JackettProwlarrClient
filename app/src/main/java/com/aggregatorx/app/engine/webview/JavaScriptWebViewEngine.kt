package com.aggregatorx.app.engine.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.aggregatorx.app.engine.util.AppContextHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android WebView engine for JS-heavy / SPA sites.
 *
 * Replaces any Playwright dependency — runs entirely on the device using the
 * system WebView (Chromium-based on all modern Android including Snapdragon S24).
 *
 * All public methods are suspend functions safe to call from IO coroutines;
 * they internally dispatch WebView operations to the Main thread.
 */
@SuppressLint("SetJavaScriptEnabled")
class JavaScriptWebViewEngine(private val existingWebView: WebView? = null) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Core load ─────────────────────────────────────────────────────────────

    /**
     * Load [url], wait for JS to settle, return full rendered HTML.
     * Optionally injects [query] into the first search input found.
     */
    suspend fun loadUrlWithJavaScript(
        url: String,
        query: String? = null,
        timeoutMs: Long = 15_000L
    ): String = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val ctx = AppContextHolder.get()
                    ?: existingWebView?.context
                    ?: run { if (cont.isActive) cont.resume(""); return@post }

                val wv = existingWebView ?: WebView(ctx)
                var resumed = false

                fun done(html: String) {
                    if (!resumed) {
                        resumed = true
                        if (existingWebView == null) { wv.stopLoading(); wv.destroy() }
                        if (cont.isActive) cont.resume(html)
                    }
                }

                cont.invokeOnCancellation { done("") }
                configureWebView(wv)

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        view.postDelayed({
                            if (query != null) {
                                injectSearchAndCapture(view, query, 4_000) { done(it) }
                            } else {
                                captureHtml(view) { done(it) }
                            }
                        }, 2_500)
                    }
                    override fun onReceivedError(
                        view: WebView, req: WebResourceRequest, err: WebResourceError
                    ) { if (req.isForMainFrame) captureHtml(view) { done(it) } }
                }
                wv.loadUrl(url)
            }
        }
    } ?: ""

    // ── Search injection ──────────────────────────────────────────────────────

    /**
     * Inject [query] into the search field identified by [searchSelector],
     * click [submitSelector], wait for [resultSelector] to appear, return HTML.
     */
    suspend fun injectSearchAndWait(
        searchSelector: String,
        submitSelector: String,
        query: String,
        resultSelector: String,
        timeoutMs: Long = 18_000L
    ): String = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val wv = existingWebView ?: return@post
                var resumed = false
                fun done(html: String) {
                    if (!resumed) { resumed = true; if (cont.isActive) cont.resume(html) }
                }
                cont.invokeOnCancellation { done("") }

                val escaped = query.replace("'", "\\'")
                val js = """
                    (function(){
                        var inp = document.querySelector('$searchSelector');
                        if(inp){
                            inp.value='$escaped';
                            inp.dispatchEvent(new Event('input',{bubbles:true}));
                            inp.dispatchEvent(new Event('change',{bubbles:true}));
                        }
                        var btn = document.querySelector('$submitSelector');
                        if(btn) btn.click();
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)

                val start = System.currentTimeMillis()
                val check = object : Runnable {
                    override fun run() {
                        wv.evaluateJavascript(
                            "(function(){ return document.querySelectorAll('$resultSelector').length; })()"
                        ) { res ->
                            val count = res?.trim('"')?.toIntOrNull() ?: 0
                            val elapsed = System.currentTimeMillis() - start
                            if (count > 0 || elapsed >= timeoutMs - 1_000) {
                                captureHtml(wv) { done(it) }
                            } else {
                                wv.postDelayed(this, 600)
                            }
                        }
                    }
                }
                wv.postDelayed(check, 1_500)
            }
        }
    } ?: ""

    // ── Scroll to load more ───────────────────────────────────────────────────

    suspend fun scrollToBottom(scrollCount: Int = 3) {
        repeat(scrollCount) {
            suspendCancellableCoroutine<Unit> { cont ->
                mainHandler.post {
                    val wv = existingWebView ?: run { if (cont.isActive) cont.resume(Unit); return@post }
                    wv.evaluateJavascript("window.scrollBy(0, window.innerHeight * 2); true;", null)
                    wv.postDelayed({ if (cont.isActive) cont.resume(Unit) }, 1_800)
                }
            }
        }
    }

    // ── Link extraction ───────────────────────────────────────────────────────

    suspend fun extractAllLinks(selector: String = "a[href]"): List<String> =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val wv = existingWebView ?: run { if (cont.isActive) cont.resume(emptyList()); return@post }
                wv.evaluateJavascript("""
                    (function(){
                        return JSON.stringify(
                            Array.from(document.querySelectorAll('$selector'))
                                .map(a=>a.href)
                                .filter(h=>h&&h.startsWith('http'))
                        );
                    })()
                """.trimIndent()) { raw ->
                    val json = raw?.unescapeJs() ?: "[]"
                    val links = try {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
                    } catch (_: Exception) { emptyList() }
                    if (cont.isActive) cont.resume(links)
                }
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun destroy() {
        mainHandler.post {
            existingWebView?.stopLoading()
            existingWebView?.destroy()
        }
    }

    fun reset() {
        mainHandler.post { existingWebView?.clearHistory() }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            setSupportZoom(false)
            // Snapdragon S24 / Android 14 Chrome UA — passes most bot checks
            userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S928B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.clearCache(true)
        wv.clearHistory()
    }

    private fun captureHtml(view: WebView, callback: (String) -> Unit) {
        view.evaluateJavascript(
            "(function(){ return document.documentElement.outerHTML; })()"
        ) { raw -> callback(raw?.unescapeJs() ?: "") }
    }

    private fun injectSearchAndCapture(
        view: WebView,
        query: String,
        waitMs: Long,
        callback: (String) -> Unit
    ) {
        val escaped = query.replace("'", "\\'")
        val js = """
            (function(){
                var selectors = [
                    'input[type="search"]','input[type="text"][name*="q"]',
                    'input[name*="query"]','input[placeholder*="search" i]',
                    'input[type="text"]'
                ];
                var inp = null;
                for(var s of selectors){ inp = document.querySelector(s); if(inp) break; }
                if(inp){
                    inp.value='$escaped';
                    inp.dispatchEvent(new Event('input',{bubbles:true}));
                    inp.dispatchEvent(new Event('change',{bubbles:true}));
                    var form = inp.closest('form');
                    if(form){ form.submit(); return 'form'; }
                    var btn = document.querySelector(
                        'button[type="submit"],input[type="submit"],.search-btn,.btn-search,[class*="search"][class*="btn"]'
                    );
                    if(btn){ btn.click(); return 'btn'; }
                }
                return 'none';
            })()
        """.trimIndent()

        view.evaluateJavascript(js) { result ->
            val action = result?.trim('"') ?: "none"
            val delay  = if (action == "none") 500L else waitMs
            view.postDelayed({ captureHtml(view, callback) }, delay)
        }
    }

    private fun String.unescapeJs(): String =
        trim('"')
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
}
