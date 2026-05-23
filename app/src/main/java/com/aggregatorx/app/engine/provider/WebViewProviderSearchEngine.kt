package com.aggregatorx.app.engine.provider

import android.content.Context
import android.util.Log
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.scraper.ScrapingEngine.Companion.MAX_PAGES
import com.aggregatorx.app.engine.scraper.ScrapingEngine.Companion.TARGET_RESULTS_PER_PROVIDER
import com.aggregatorx.app.engine.scraper.WebViewFetcher
import com.aggregatorx.app.engine.webview.JavaScriptWebViewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * WebView-backed provider search engine.
 *
 * Used by ScrapingEngine as the JS-site fallback when plain Jsoup returns
 * fewer than MIN_RESULTS_THRESHOLD results.  Automatically walks pages until
 * TARGET_RESULTS_PER_PROVIDER results are collected or MAX_PAGES is reached.
 */
class WebViewProviderSearchEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebViewProviderSearch"
    }

    // ── Primary entry point ───────────────────────────────────────────────────

    /**
     * Search [provider] for [query] using the system WebView.
     * Walks up to [MAX_PAGES] pages to collect [TARGET_RESULTS_PER_PROVIDER] results.
     */
    suspend fun searchWithWebView(
        provider: Provider,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.Main) {
        val allResults = mutableListOf<SearchResult>()
        val seenUrls   = mutableSetOf<String>()

        for (page in 0 until MAX_PAGES) {
            if (allResults.size >= TARGET_RESULTS_PER_PROVIDER) break
            try {
                val url  = buildSearchUrl(provider, query, page)
                val html = WebViewFetcher.fetch(url, query, timeoutMs = 18_000L) ?: break
                val page_results = parseWebViewResults(html, provider)
                var added = 0
                page_results.forEach { r -> if (seenUrls.add(r.url)) { allResults.add(r); added++ } }
                if (added == 0) break   // no new results → end of pages
            } catch (e: Exception) {
                Log.w(TAG, "Page $page failed for ${provider.name}: ${e.message}")
                if (page == 0) break
            }
        }

        Log.d(TAG, "WebView collected ${allResults.size} results for ${provider.name}")
        allResults
    }

    /**
     * JS-injection search: loads the provider homepage, injects the query into
     * the search field, waits for results, then returns parsed results.
     */
    suspend fun searchWithJSInjection(
        provider: Provider,
        query: String,
        searchInputSelector: String,
        submitButtonSelector: String,
        resultSelector: String
    ): List<SearchResult> = withContext(Dispatchers.Main) {
        try {
            val engine = JavaScriptWebViewEngine()
            val html = engine.injectSearchAndWait(
                searchSelector = searchInputSelector,
                submitSelector = submitButtonSelector,
                query          = query,
                resultSelector = resultSelector,
                timeoutMs      = 18_000L
            )
            parseWebViewResults(html, provider)
        } catch (e: Exception) {
            Log.e(TAG, "JS injection failed for ${provider.name}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Infinite-scroll search: loads the search URL then scrolls [scrollIterations]
     * times to trigger lazy-loaded content before extracting results.
     */
    suspend fun searchWithInfiniteScroll(
        provider: Provider,
        query: String,
        scrollIterations: Int = 5
    ): List<SearchResult> = withContext(Dispatchers.Main) {
        try {
            val engine = JavaScriptWebViewEngine()
            val url    = buildSearchUrl(provider, query, 0)
            engine.loadUrlWithJavaScript(url, query, timeoutMs = 12_000L)
            engine.scrollToBottom(scrollIterations)
            val html = engine.loadUrlWithJavaScript(url, timeoutMs = 5_000L)
            parseWebViewResults(html, provider)
        } catch (e: Exception) {
            Log.e(TAG, "Infinite scroll failed for ${provider.name}: ${e.message}")
            emptyList()
        }
    }

    // ── HTML parsing ──────────────────────────────────────────────────────────

    fun parseWebViewResults(html: String, provider: Provider): List<SearchResult> {
        if (html.isBlank()) return emptyList()
        return try {
            val doc     = Jsoup.parse(html, provider.baseUrl)
            val results = mutableListOf<SearchResult>()

            // Ordered selector cascade — most specific first
            val candidates = doc.select(
                "tr:has(a), .result-item, .search-result, .torrent-box, .play-row, " +
                "[class*='item']:has(a), [class*='result']:has(a), [class*='card']:has(a), " +
                "article:has(a), li:has(a)"
            ).ifEmpty {
                doc.select(".result, .results, #results, .search-results")
                    .firstOrNull()?.select("tr, div[class*='item'], div[class*='row'], li, a")
                    ?: doc.select("a[href]")
            }

            val junkWords = setOf(
                "home","login","register","sign up","faq","about","contact",
                "privacy","terms","logout","index","menu","search","back","next","prev"
            )

            candidates.forEach { el ->
                try {
                    val anchor = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: return@forEach
                    val title  = anchor.text().trim().ifEmpty {
                        el.selectFirst("h1,h2,h3,h4,.title,.name")?.text()?.trim() ?: ""
                    }
                    var url = anchor.absUrl("href").ifEmpty { anchor.attr("href") }
                    if (url.startsWith("/")) url = provider.baseUrl.trimEnd('/') + url

                    val isJunk = title.lowercase() in junkWords
                        || url.contains(".css") || url.contains(".js")
                        || url.startsWith("#") || url.isEmpty()
                        || title.length < 3

                    if (!isJunk) {
                        val thumb = el.selectFirst("img[src]")?.absUrl("src")
                        val desc  = el.selectFirst("p,.description,.summary")?.text()
                        results.add(SearchResult(
                            title          = title,
                            url            = url,
                            thumbnailUrl   = thumb,
                            description    = desc,
                            quality        = el.selectFirst("[class*='quality'],[class*='resolution']")?.text(),
                            providerId     = provider.id,
                            providerName   = provider.name,
                            relevanceScore = 0.8f
                        ))
                    }
                } catch (_: Exception) {}
            }

            Log.d(TAG, "Parsed ${results.size} results from WebView HTML for ${provider.name}")
            results.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed for ${provider.name}: ${e.message}")
            emptyList()
        }
    }

    // ── URL building ──────────────────────────────────────────────────────────

    private fun buildSearchUrl(provider: Provider, query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val base = provider.searchPattern
            .replace("{query}", encoded)
            .replace("{QUERY}", encoded)
            .replace("{baseUrl}", provider.baseUrl.trimEnd('/'))
            .let { if (it.startsWith("http")) it else "${provider.baseUrl.trimEnd('/')}/$it" }
        return if (page > 0) {
            when {
                base.contains("page=")  -> base.replace(Regex("page=\\d+"), "page=${page + 1}")
                base.contains("?")      -> "$base&page=${page + 1}"
                base.endsWith("/")      -> "${base}page/${page + 1}"
                else                    -> "$base/page/${page + 1}"
            }
        } else base
    }
}
