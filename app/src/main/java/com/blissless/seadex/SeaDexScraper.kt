package com.blissless.seadex

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SeaDexScraper {

    fun getMagnetUrl(context: Context, anilistId: String): List<String> {
        Log.d("SeaDexDebug", "Starting scrape for Anilist ID: $anilistId")

        val nyaaLinks = fetchNyaaLinksFromReleasesMoe(context, anilistId)
        if (nyaaLinks.isEmpty()) throw Exception("No Nyaa links found on releases.moe")

        Log.d("SeaDexDebug", "Found ${nyaaLinks.size} Nyaa links. Fetching magnets...")

        val magnets = mutableListOf<String>()
        for (url in nyaaLinks) {
            try {
                magnets.addAll(fetchMagnetsFromNyaa(url))
            } catch (_: Exception) {
                // Skip failed pages
            }
        }
        return magnets.distinct()
    }

    private fun fetchNyaaLinksFromReleasesMoe(context: Context, anilistId: String): List<String> {
        val url = "https://releases.moe/$anilistId"
        Log.d("SeaDexDebug", "WebView Target URL: $url")

        var jsResult: String? = null
        val latch = CountDownLatch(1)
        var hasStartedPolling = false

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.d("SeaDexDebug", "Page loading started: $url")
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        Log.e("SeaDexDebug", "WebView Error: ${error?.description}")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("SeaDexDebug", "Page finished loading: $url")

                        // Prevent multiple polling loops if onPageFinished fires multiple times
                        if (hasStartedPolling) return
                        hasStartedPolling = true

                        val handler = Handler(Looper.getMainLooper())
                        var attempts = 0
                        val maxAttempts = 20

                        val pollRunnable = object : Runnable {
                            override fun run() {
                                attempts++
                                view?.evaluateJavascript("(function() { return Array.from(document.querySelectorAll('a')).some(a => (a.href || a.dataset.href || '').includes('nyaa.si/view')); })();") { res ->

                                    val isReady = res?.removeSurrounding("\"")?.toBoolean() ?: false
                                    Log.d("SeaDexDebug", "Polling for links (Attempt $attempts). Ready? $isReady")

                                    if (isReady) {
                                        val jsCode = """
                                            (function() {
                                                return JSON.stringify(Array.from(document.querySelectorAll('a'))
                                                    .map(e => e.getAttribute('data-href') || e.href || '')
                                                    .filter(u => u.includes('nyaa.si/view'))
                                                );
                                            })()
                                        """.trimIndent()

                                        view?.evaluateJavascript(jsCode) { result ->
                                            jsResult = result
                                            Log.d("SeaDexDebug", "Raw JS Result: $jsResult")
                                            latch.countDown()
                                        }
                                    } else if (attempts < maxAttempts) {
                                        handler.postDelayed(this, 500)
                                    } else {
                                        latch.countDown()
                                    }
                                }
                            }
                        }
                        handler.postDelayed(pollRunnable, 500)
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                Log.e("SeaDexDebug", "WebView creation failed", e)
                latch.countDown()
            }
        }

        latch.await(15, TimeUnit.SECONDS)

        if (jsResult.isNullOrEmpty() || jsResult == "null") {
            throw Exception("releases.moe took too long to load Nyaa links.")
        }

        val unescapedResult = jsResult.replace("\\/", "/")
        Log.d("SeaDexDebug", "Unescaped Result: $unescapedResult")

        // SIMPLIFIED REGEX: Just look for the exact nyaa URL pattern
        val regex = Regex("https?://nyaa\\.si/view/\\d+")
        val links = regex.findAll(unescapedResult).map { it.value }.distinct().toList()
        Log.d("SeaDexDebug", "Extracted Links: $links")

        if (links.isEmpty()) {
            throw Exception("Found no Nyaa links on releases.moe.")
        }

        return links
    }

    private fun fetchMagnetsFromNyaa(url: String): List<String> {
        Log.d("SeaDexDebug", "Fetching magnets from Nyaa: $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("SeaDexDebug", "Nyaa HTTP Error: ${connection.responseCode}")
                return emptyList()
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val magnets = mutableListOf<String>()

            val regex = Regex("href=\"(magnet:\\?xt=urn:btih:[^\"]+)\"")
            regex.findAll(html).forEach {
                magnets.add(it.groupValues[1])
            }
            return magnets.distinct()
        } finally {
            connection.disconnect()
        }
    }
}