package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.aggregatorx.app.engine.util.AppContextHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lightweight WebView fetcher that can be called from any coroutine.
 * Must be invoked on the Main thread (WebView requirement); callers should
 * wrap with withContext(Dispatchers.Main) or use the provided [fetch] helper.
 *
 * Designed for the Snapdragon S24 (ARM64) — no Playwright, pure Android WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebViewFetcher {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Load [url], optionally type [query] into the first visible search input,
     * wait for the page to settle, then return the full rendered HTML.
     *
     * @param url        Page to load
     * @param query      If non-null, injected into the search field after load
     * @param timeoutMs  Hard deadline; returns whatever HTML is available at expiry
     */
    suspend fun fetch(
        url: String,
        query: String? = null,
        timeoutMs: Long = 18_000L
    ): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val context = AppContextHolder.get() ?: run {
                    if (cont.isActive) cont.resume(null)
                    return@post
                }

                val webView = WebView(context)
                var resumed = false

                fun safeResume(html: String?) {
                    if (!resumed) {
                        resumed = true
                        webView.stopLoading()
                        webView.destroy()
                        if (cont.isActive) cont.resume(html)
                    }
                }

                cont.invokeOnCancellation { safeResume(null) }

                webView.settings.apply {
                    javaScriptEnabled        = true
                    domStorageEnabled        = true
                    databaseEnabled          = true
                    cacheMode                = WebSettings.LOAD_NO_CACHE
                    mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString          = "Mozilla/5.0 (Linux; Android 14; SM-S928B) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
                    loadWithOverviewMode     = true
                    useWideViewPort          = true
                    setSupportZoom(false)
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        // Wait 3 s for JS to render, then optionally inject query
                        view.postDelayed({
                            if (query != null) {
                                injectQueryAndExtract(view, query) { html -> safeResume(html) }
                            } else {
                                extractHtml(view) { html -> safeResume(html) }
                            }
                        }, 3_000)
                    }

                    override fun onReceivedError(
                        view: WebView, request: WebResourceRequest, error: WebResourceError
                    ) {
                        if (request.isForMainFrame) safeResume(null)
                    }
                }

                webView.loadUrl(url)
            }
        }
    }

    // ── JS helpers ────────────────────────────────────────────────────────────

    private fun extractHtml(view: WebView, callback: (String?) -> Unit) {
        view.evaluateJavascript(
            "(function(){ return document.documentElement.outerHTML; })()"
        ) { raw ->
            callback(raw?.unescapeJs())
        }
    }

    private fun injectQueryAndExtract(view: WebView, query: String, callback: (String?) -> Unit) {
        val escaped = query.replace("'", "\\'")
        val js = """
            (function(){
                var inputs = document.querySelectorAll(
                    'input[type="search"], input[type="text"], input[name*="q"], input[name*="query"], input[placeholder*="search" i]'
                );
                var inp = inputs[0];
                if(inp){
                    inp.value = '$escaped';
                    inp.dispatchEvent(new Event('input',{bubbles:true}));
                    inp.dispatchEvent(new Event('change',{bubbles:true}));
                    var form = inp.closest('form');
                    if(form){ form.submit(); return 'submitted'; }
                    var btn = document.querySelector('button[type="submit"], input[type="submit"], .search-btn, .btn-search');
                    if(btn){ btn.click(); return 'clicked'; }
                }
                return 'no-input';
            })()
        """.trimIndent()

        view.evaluateJavascript(js) { result ->
            val action = result?.trim('"') ?: "no-input"
            if (action == "no-input") {
                // No search form found — just return current HTML
                extractHtml(view, callback)
            } else {
                // Wait for results to load after form submit / button click
                view.postDelayed({
                    extractHtml(view, callback)
                }, 4_000)
            }
        }
    }

    /** Unescape a JSON string value returned by evaluateJavascript. */
    private fun String.unescapeJs(): String =
        this.trim('"')
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
}
