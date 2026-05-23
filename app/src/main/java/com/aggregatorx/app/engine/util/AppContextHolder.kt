package com.aggregatorx.app.engine.util

import android.content.Context

/**
 * Holds the application Context so engine singletons (e.g. WebViewFetcher)
 * can access it without needing constructor injection.
 * Initialised once in AggregatorXApp.onCreate().
 */
object AppContextHolder {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
