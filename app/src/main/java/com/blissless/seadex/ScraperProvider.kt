package com.blissless.seadex

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray

class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.seadex.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        when (uriMatcher.match(uri)) {
            CODE_SCRAPES -> {
                val anilistId = uri.getQueryParameter("anilistId")
                val cursor = MatrixCursor(arrayOf("data"))

                if (anilistId == null) {
                    cursor.addRow(arrayOf("{\"error\":\"No anilistId provided\"}"))
                    return cursor
                }

                try {
                    val magnets = SeaDexScraper.getMagnetUrl(context!!, anilistId)
                    if (magnets.isEmpty()) {
                        cursor.addRow(arrayOf("{\"error\":\"No magnets found on SeaDex.\"}"))
                    } else {
                        val jsonArray = JSONArray()
                        for (m in magnets) jsonArray.put(m)
                        cursor.addRow(arrayOf(jsonArray.toString()))
                    }
                } catch (e: Exception) {
                    cursor.addRow(arrayOf("{\"error\":\"Scraping failed: ${e.message}\"}"))
                }
                return cursor
            }
        }
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}