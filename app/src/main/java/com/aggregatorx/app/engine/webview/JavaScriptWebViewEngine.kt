package com.aggregatorx.app.engine.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebView Engine for JavaScript-heavy providers.
 *
 * Handles providers that require:
 * - JavaScript execution to render search results
 * - Dynamic content loading (AJAX, fetch calls)
 * - Client-side pagination or infinite scroll
 * - Complex DOM manipulation
 *
 * Returns rendered HTML after JS execution completes.
 */
class JavaScriptWebViewEngine(private val webView: WebView) {

    private var isPageReady = false
    private var pageContent = ""
    private val readySignal = CompletableFuture<String>()

    init {
        configureWebView()
    }

    /**
     * Configure WebView for JavaScript execution and result extraction.
     */
    private fun configureWebView() {
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                
                // FIX 1: Set a modern desktop User-Agent string to bypass bot filters and mobile restrictions
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            // Enable clean cookie management for session handling and bypass mechanisms
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    /**
     * Extract rendered page content via JavaScript.
     */
    private fun extractPageContent(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                const html = document.documentElement.outerHTML;
                window.ContentExtractor.onContentReady(html);
                return true;
            })();
            """.trimIndent()
        ) { _ -> }
    }

    /**
     * Load a URL and wait safely for JavaScript and AJAX data pipelines to render fully.
     */
    suspend fun loadUrlWithJavaScript(
        url: String,
        query: String? = null,
        timeoutMs: Long = 12000
    ): String {
        return suspendCancellableCoroutine { continuation ->
            var isResumed = false

            fun safeResume(html: String) {
                if (!isResumed) {
                    isResumed = true
                    continuation.resume(html)
                }
            }

            // Fallback timeout to return whatever structure is currently available if things run too slow
            val timeoutRunnable = Runnable {
                webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                    val html = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                    safeResume(html)
                }
            }
            webView.postDelayed(timeoutRunnable, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    // FIX 2: Instead of a single immediate signal trap, wait a robust 3.5 seconds 
                    // after page structure loads to let AJAX scripts populate result arrays
                    view.postDelayed({
                        view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                            val html = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                            webView.removeCallbacks(timeoutRunnable)
                            safeResume(html)
                        }
                    }, 3500)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView.loadUrl(url)
        }
    }

    /**
     * Inject search query via JavaScript and poll dynamically until results load.
     */
    suspend fun injectSearchAndWait(
        searchSelector: String,
        submitSelector: String,
        query: String,
        resultSelector: String,
        timeoutMs: Long = 15000
    ): String {
        return suspendCancellableCoroutine { continuation ->
            var isResumed = false

            fun safeResume(html: String) {
                if (!isResumed) {
                    isResumed = true
                    continuation.resume(html)
                }
            }

            val js = """
            (function() {
                const searchInput = document.querySelector('$searchSelector');
                if (searchInput) {
                    searchInput.value = '$query';
                    searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                    searchInput.dispatchEvent(new Event('change', { bubbles: true }));
                }

                const submitBtn = document.querySelector('$submitSelector');
                if (submitBtn) {
                    submitBtn.click();
                }
            })();
            """.trimIndent()

            // Inject the entry keys and submit action click
            webView.evaluateJavascript(js, null)

            // Continual Polling: Regularly check the DOM every 500ms to see if target elements arrived
            val startTime = System.currentTimeMillis()
            var checkRunnable: Runnable? = null
            
            checkRunnable = Runnable {
                webView.evaluateJavascript(
                    """
                    (function() {
                        const results = document.querySelectorAll('$resultSelector');
                        return results.length > 0;
                    })();
                    """.trimIndent()
                ) { result ->
                    val found = result?.toBoolean() ?: false
                    if (found || (System.currentTimeMillis() - startTime) >= (timeoutMs - 1000)) {
                        webView.evaluateJavascript("document.documentElement.outerHTML") { finalHtml ->
                            val html = finalHtml?.trim('"')?.replace("\\\"", "\"") ?: ""
                            safeResume(html)
                        }
                    } else {
                        checkRunnable?.let { webView.postDelayed(it, 500) }
                    }
                }
            }

            webView.postDelayed(checkRunnable, 1500)
        }
    }

    /**
     * Extract all visible URLs from current page (for result links).
     */
    suspend fun extractAllLinks(selector: String = "a[href]"): List<String> {
        return suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(
                """
                (function() {
                    const links = Array.from(document.querySelectorAll('$selector'))
                        .map(a => a.href)
                        .filter(href => href && href.startsWith('http'));
                    return JSON.stringify(links);
                })();
                """.trimIndent()
            ) { result ->
                try {
                    val json = result?.trim('"')?.replace("\\\"", "\"") ?: "[]"
                    val links = kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
                    continuation.resume(links)
                } catch (e: Exception) {
                    continuation.resume(emptyList())
                }
            }
        }
    }

    /**
     * Handle scrolling for infinite-scroll providers safely without double-resume crashes.
     */
    suspend fun scrollToBottom(scrollCount: Int = 3) {
        repeat(scrollCount) {
            suspendCancellableCoroutine<Unit> { continuation ->
                // FIX 3: Fire the script but do NOT resume inside the direct callback loop
                webView.evaluateJavascript("window.scrollBy(0, window.innerHeight); true;", null)
                
                // Allow exactly 1500ms for layout processing and asset rendering before advancing the loop
                webView.postDelayed({
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }, 1500)
            }
        }
    }

    /**
     * JS Interface callback for backward compatibility preservation.
     */
    private fun onJSContentReady(html: String) {
        pageContent = html
        if (!readySignal.isDone) {
            readySignal.complete(html)
        }
    }

    fun reset() {
        isPageReady = false
        pageContent = ""
    }

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    inner class JSInterface(
        private val onContentReady: (String) -> Unit
    ) {
        @android.webkit.JavascriptInterface
        fun onContentReady(html: String) {
            onContentReady.invoke(html)
        }
    }
}
