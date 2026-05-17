package com.aggregatorx.app.engine.provider

import android.content.Context
import android.webkit.WebView
import android.util.Log
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.webview.JavaScriptWebViewEngine
import org.jsoup.Jsoup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WebView-based Provider Search Engine for JavaScript-heavy sites.
 *
 * Used as fallback when standard HTTP scraping fails on providers that:
 * - Render content dynamically with JavaScript
 * - Load results via AJAX/Fetch
 * - Use client-side frameworks (React, Vue, Angular, etc.)
 * - Have complex JavaScript-driven UIs
 */
class WebViewProviderSearchEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebViewProviderSearch"
    }

    /**
     * Search a provider using WebView to execute JavaScript.
     * Returns rendered HTML that can be parsed for results.
     */
    suspend fun searchWithWebView(
        provider: Provider,
        query: String
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            val baseUrl = provider.baseUrl
            Log.d(TAG, "Starting WebView search on ${provider.name} for query: $query")

            // Load the provider's search page
            val html = engine.loadUrlWithJavaScript(
                url = buildSearchUrl(provider, query),
                query = query,
                timeoutMs = 12000
            )

            Log.d(TAG, "WebView loaded HTML (${html.length} chars) from ${provider.name}")
            html

        } catch (e: Exception) {
            Log.e(TAG, "WebView search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Search using JavaScript injection - for search forms that need manual population.
     */
    suspend fun searchWithJSInjection(
        provider: Provider,
        query: String,
        searchInputSelector: String,
        submitButtonSelector: String,
        resultSelector: String
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting JS injection search on ${provider.name}")

            // Load the search page
            engine.loadUrlWithJavaScript(provider.baseUrl, query, 8000)

            // Inject search query and submit
            val renderedHtml = engine.injectSearchAndWait(
                searchSelector = searchInputSelector,
                submitSelector = submitButtonSelector,
                query = query,
                resultSelector = resultSelector,
                timeoutMs = 15000
            )

            Log.d(TAG, "JS injection completed for ${provider.name}")
            renderedHtml

        } catch (e: Exception) {
            Log.e(TAG, "JS injection search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Search with infinite scroll support - scrolls to load more results.
     */
    suspend fun searchWithInfiniteScroll(
        provider: Provider,
        query: String,
        scrollIterations: Int = 3
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting infinite scroll search on ${provider.name}")

            // Load initial search results
            engine.loadUrlWithJavaScript(
                buildSearchUrl(provider, query),
                query,
                10000
            )

            // Scroll to trigger loading more results
            repeat(scrollIterations) { iteration ->
                Log.d(TAG, "Scroll iteration ${iteration + 1}/$scrollIterations for ${provider.name}")
                engine.scrollToBottom(1)
            }

            // Extract all links after scrolling
            val links = engine.extractAllLinks("a[href*='${provider.searchPattern.takeWhile { it != '?' }}']")
            Log.d(TAG, "Extracted ${links.size} links from ${provider.name} after scrolling")

            // FIX: Wait asynchronously for the HTML layout extraction to fully finish
            val finalHtml = suspendCancellableCoroutine<String> { continuation ->
                webView.evaluateJavascript("document.documentElement.outerHTML") { result: String? ->
                    val html = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                    continuation.resume(html)
                }
            }
            
            finalHtml

        } catch (e: Exception) {
            Log.e(TAG, "Infinite scroll search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Build search URL for the provider.
     */
    private fun buildSearchUrl(provider: Provider, query: String): String {
        return provider.searchPattern
            .replace("{query}", query)
            .replace("{QUERY}", query)
            .let { url ->
                if (url.startsWith("http")) url else provider.baseUrl + url
            }
    }

    /**
     * Parse rendered HTML to extract search results.
     */
    fun parseWebViewResults(
        html: String,
        provider: Provider
    ): List<SearchResult> {
        return try {
            val doc = Jsoup.parse(html, provider.baseUrl)
            val results = mutableListOf<SearchResult>()

            // FIX: Instead of relying on just ".result", look for standard table rows or lists first
            var resultElements = doc.select("tr:has(a), .result-item, .search-result, .torrent-box, .play-row, [class*='item']:has(a)")

            // Fallback: If rows aren't found, check if we accidentally selected a single giant parent wrapper box
            if (resultElements.isEmpty()) {
                val wrappers = doc.select(".result, .results, #results, .search-results")
                if (wrappers.size == 1) {
                    // Dive inside the wrapper and grab all individual rows/links instead of treating it as 1 item
                    resultElements = wrappers.first()?.select("tr, div[class*='item'], div[class*='row'], li, a") ?: doc.select("a")
                } else if (wrappers.size > 1) {
                    resultElements = wrappers
                }
            }

            // Ultimate fallback: Just scan every raw link on the page if nothing else matched
            if (resultElements.isEmpty()) {
                resultElements = doc.select("a")
            }

            resultElements.forEach { element ->
                try {
                    val anchor = if (element.tagName() == "a") element else element.selectFirst("a")

                    val title = anchor?.text() ?: ""
                    var url = anchor?.attr("href") ?: ""
                    val quality = element.selectFirst("[class*='quality'], [class*='resolution']")?.text() ?: "auto"

                    // Clean relative paths (e.g. "/download/123" -> "https://site.com/download/123")
                    if (url.startsWith("/")) {
                        url = provider.baseUrl.trimEnd('/') + url
                    }

                    // Strict filter to drop non-result site menus (Login, FAQ, Home, etc.)
                    val junkWords = listOf("home", "login", "register", "sign up", "faq", "about", "contact", "privacy", "terms", "logout", "index")
                    val isJunk = junkWords.any { title.equals(it, ignoreCase = true) } || url.contains(".css") || url.contains(".js") || url.startsWith("#")

                    if (url.isNotEmpty() && title.isNotEmpty() && !isJunk && title.length > 3) {
                        results.add(
                            SearchResult(
                                title = title,
                                url = url,
                                quality = quality,
                                providerId = provider.id,
                                providerName = provider.name,
                                relevanceScore = 0.8f
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse result element: ${e.message}")
                }
            }

            Log.d(TAG, "Parsed ${results.size} results from WebView HTML")
            results

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebView results: ${e.message}", e)
            emptyList()
        }
    }
}
