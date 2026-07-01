package com.blissless.seadex

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

object SeaDexScraper {

    private const val API =
        "https://releases.moe/api/collections/entries/records"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Public trackers SeaDex doesn't include in its records.
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce"
    )

    fun getMagnetUrl(context: Context, anilistId: String): List<String> {
        val url = "$API?filter=alID%3D" +
                URLEncoder.encode(anilistId, "UTF-8") +
                "&expand=trs&perPage=500&skipTotal=true"
        val json = fetchJson(url)

        val items = json.optJSONArray("items") ?: return emptyList()
        if (items.length() == 0) return emptyList()

        val entry = items.getJSONObject(0)
        val trs = entry.optJSONObject("expand")
            ?.optJSONArray("trs") ?: return emptyList()

        val magnets = mutableListOf<String>()
        for (i in 0 until trs.length()) {
            val t = trs.getJSONObject(i)
            val hash = t.optString("infoHash").trim()
            // Private-tracker entries (e.g. AnimeBytes) have infoHash="<redacted>"
            if (hash.length != 40) continue

            val group = t.optString("releaseGroup", "")
            val sb = StringBuilder("magnet:?xt=urn:btih:")
                .append(hash.lowercase())
            if (group.isNotEmpty()) {
                sb.append("&dn=").append(URLEncoder.encode(group, "UTF-8"))
            }
            for (tr in TRACKERS) {
                sb.append("&tr=").append(URLEncoder.encode(tr, "UTF-8"))
            }
            magnets.add(sb.toString())
        }
        return magnets.distinct()
    }

    private fun fetchJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("SeaDex API HTTP ${conn.responseCode}")
            }
            return JSONObject(
                conn.inputStream.bufferedReader().use { it.readText() }
            )
        } finally {
            conn.disconnect()
        }
    }
}