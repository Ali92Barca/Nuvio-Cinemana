package com.nuvio.tv.core.cinemana

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Local Stremio-compatible bridge for Cinemana. This is a device-local service:
 * its URL must never be persisted in AddonPreferences or sent to addon sync.
 */
class CinemanaLocalServer(context: Context) : NanoHTTPD("127.0.0.1", PORT) {
    @Suppress("unused") private val applicationContext = context.applicationContext
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun serve(session: IHTTPSession): Response {
        return try {
            val path = session.uri.orEmpty().trimEnd('/')
            val query = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
            val payload = when {
                path == "/manifest.json" -> manifest()
                path.startsWith("/catalog/") -> catalog(path, query)
                path.startsWith("/meta/") -> meta(path)
                path.startsWith("/stream/") -> streams(path)
                path.startsWith("/subtitles/") -> JsonObject().apply { add("subtitles", JsonArray()) }
                path == "/" -> JsonObject().apply { addProperty("status", "ok"); addProperty("service", "Cinemana") }
                else -> return jsonResponse(JsonObject().apply { addProperty("error", "Not found") }, Response.Status.NOT_FOUND)
            }
            jsonResponse(payload, Response.Status.OK)
        } catch (e: Exception) {
            Log.w(TAG, "Cinemana request failed: ${session.uri}", e)
            jsonResponse(JsonObject().apply {
                addProperty("error", "Cinemana request failed")
                addProperty("message", e.message ?: "Unknown error")
            }, Response.Status.INTERNAL_ERROR)
        }
    }

    private fun manifest() = JsonObject().apply {
        addProperty("id", "org.local.cinemana-nuvio")
        addProperty("version", "1.0.0")
        addProperty("name", "Cinemana")
        addProperty("description", "Native Cinemana catalog and playback integration")
        add("resources", gson.toJsonTree(listOf("catalog", "meta", "stream", "subtitles")))
        add("types", gson.toJsonTree(listOf("movie", "series")))
        add("idPrefixes", gson.toJsonTree(listOf("cinemana:", "cinemana-series:")))
        val catalogs = JsonArray()
        catalogs.add(catalogDescriptor("movie", "cinemana_search", "Cinemana • بحث أفلام", true))
        catalogs.add(catalogDescriptor("series", "cinemana_series_search", "Cinemana • بحث مسلسلات", true))
        CATEGORIES.forEach { (id, name, _) ->
            catalogs.add(catalogDescriptor("movie", id, "Cinemana • $name", false))
            catalogs.add(catalogDescriptor("series", "${id}_series", "Cinemana • $name • مسلسلات", false))
        }
        add("catalogs", catalogs)
    }

    private fun catalogDescriptor(type: String, id: String, name: String, searchable: Boolean) =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("id", id)
            addProperty("name", name)
            if (searchable) add("extra", gson.toJsonTree(listOf(mapOf("name" to "search", "isRequired" to false))))
        }

    private fun catalog(path: String, query: Map<String, String>): JsonObject {
        val parts = path.removePrefix("/catalog/").split('/')
        if (parts.size < 2) return JsonObject().apply { add("metas", JsonArray()) }
        val type = parts[0]
        val catalogId = parts[1].removeSuffix(".json")
        val skip = query["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val search = query["search"].orEmpty().trim()
        val items = when {
            catalogId == "cinemana_search" || catalogId == "cinemana_series_search" ->
                if (search.isBlank()) emptyList() else searchItems(search, if (type == "series") "series" else "movies")
            else -> {
                val category = CATEGORIES.firstOrNull { it.first == catalogId.removeSuffix("_series") }
                if (category == null) emptyList() else categoryItems(category.third, skip, if (type == "series" || catalogId.endsWith("_series")) "2" else "1")
            }
        }
        val metas = JsonArray()
        items.distinctBy { text(it, "nb", "id") }.forEach { item ->
            val id = digits(text(item, "nb", "id"))
            if (id.isBlank()) return@forEach
            val series = type == "series" || catalogId.endsWith("_series") || digits(text(item, "rootSeries")).isNotBlank()
            val meta = makeMeta(item, if (series) "series" else "movie", id)
            if (meta != null) metas.add(meta)
        }
        return JsonObject().apply { add("metas", metas) }
    }

    private fun meta(path: String): JsonObject {
        val id = path.removePrefix("/meta/").substringAfter('/', "").substringBefore('/').removeSuffix(".json")
        val isSeries = id.startsWith("cinemana-series:")
        val rawId = digits(id.removePrefix("cinemana-series:").removePrefix("cinemana:"))
        if (rawId.isBlank()) return JsonObject().apply { add("meta", null) }
        val item = getJson("/api/android/allVideoInfo/id/${enc(rawId)}").let(::extractItems).firstOrNull()
            ?: return JsonObject().apply { add("meta", null) }
        val result = makeMeta(item, if (isSeries) "series" else "movie", rawId) ?: return JsonObject().apply { add("meta", null) }
        if (isSeries) {
            val episodes = runCatching { extractItems(getJson("/api/android/videoSeason/id/${enc(rawId)}")) }.getOrDefault(emptyList())
            val videos = JsonArray()
            episodes.forEachIndexed { index, episode ->
                val episodeId = digits(text(episode, "nb", "id"))
                if (episodeId.isBlank()) return@forEachIndexed
                val video = JsonObject().apply {
                    addProperty("id", "cinemana:$episodeId")
                    addProperty("title", text(episode, "en_title", "ar_title", "other_title", "title").ifBlank { "Episode ${index + 1}" })
                    addProperty("season", text(episode, "season").toIntOrNull() ?: 1)
                    addProperty("episode", text(episode, "episodeNummer").toIntOrNull() ?: index + 1)
                    text(episode, "mDate", "releaseDate").takeIf(String::isNotBlank)?.let { addProperty("released", it) }
                    text(episode, "description", "en_description", "ar_description").takeIf(String::isNotBlank)?.let { addProperty("overview", it) }
                    imageUrl(episode)?.let { addProperty("thumbnail", it) }
                }
                videos.add(video)
            }
            result.add("videos", videos)
        }
        return JsonObject().apply { add("meta", result) }
    }

    private fun streams(path: String): JsonObject {
        val id = path.removePrefix("/stream/").substringAfter('/', "").substringBefore('/').removeSuffix(".json")
        val rawId = digits(id.removePrefix("cinemana-series:").removePrefix("cinemana:"))
        val array = JsonArray()
        if (rawId.isNotBlank()) {
            val versions = extractItems(getJson("/api/android/transcoddedFiles/id/${enc(rawId)}"))
            versions.forEach { version ->
                val url = text(version, "videoUrl")
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@forEach
                array.add(JsonObject().apply {
                    addProperty("name", "Cinemana")
                    addProperty("title", "${text(version, "resolution").ifBlank { text(version, "transcoddedFileName").ifBlank { "Video" } }} · ${text(version, "container").ifBlank { "MP4" }}")
                    addProperty("url", url)
                    add("behaviorHints", JsonObject().apply { addProperty("notWebReady", false) })
                })
            }
        }
        return JsonObject().apply { add("streams", array) }
    }

    private fun categoryItems(categoryId: String, skip: Int, videoKind: String): List<JsonObject> {
        val params = "categoryID=${enc(categoryId)}&orderby=desc&videoKind=${enc(videoKind)}&offset=${enc(skip.toString())}&level=0"
        return extractItems(getJson("/api/android/videosByCategory?$params"))
    }

    private fun searchItems(query: String, type: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var previousSignature = ""
        for (page in 0 until MAX_SEARCH_PAGES) {
            val params = "level=0&videoTitle=${enc(query)}&staffTitle=&page=$page&year=1900%2C2035&type=${enc(type)}"
            val items = extractItems(getJson("/api/android/AdvancedSearch?$params"))
            if (items.isEmpty()) break
            val signature = items.joinToString(",") { text(it, "nb", "id") }
            if (signature.isBlank() || signature == previousSignature) break
            previousSignature = signature
            var added = 0
            items.forEach { item ->
                val id = digits(text(item, "nb", "id"))
                if (id.isNotBlank() && seen.add(id)) { all.add(item); added++ }
            }
            if (added == 0) break
        }
        return all
    }

    private fun makeMeta(item: JsonObject, type: String, fallbackId: String): JsonObject? {
        val videoId = digits(text(item, "nb", "id").ifBlank { fallbackId })
        if (videoId.isBlank()) return null
        val root = digits(text(item, "rootSeries")).takeIf { it.isNotBlank() && it != "0" } ?: videoId
        val name = text(item, "en_title", "ar_title", "other_title", "title").ifBlank { "Cinemana $videoId" }
        return JsonObject().apply {
            addProperty("id", if (type == "series") "cinemana-series:$root" else "cinemana:$videoId")
            addProperty("type", type)
            addProperty("name", name)
            imageUrl(item)?.let { addProperty("poster", it) }
            text(item, "year").toIntOrNull()?.let { addProperty("year", it); addProperty("releaseInfo", it.toString()) }
            text(item, "description", "en_description", "ar_description", "en_content", "ar_content").takeIf(String::isNotBlank)?.let { addProperty("description", it) }
            val categories = item.getAsJsonArray("categories")
            if (categories != null) {
                val genres = JsonArray()
                categories.forEach { cat -> if (cat.isJsonObject) text(cat.asJsonObject, "en_title", "ar_title").takeIf(String::isNotBlank)?.let(genres::add) }
                if (genres.size() > 0) add("genres", genres)
            } else {
                text(item, "category_en_title", "category_ar_title").takeIf(String::isNotBlank)?.let { add("genres", gson.toJsonTree(listOf(it))) }
            }
            text(item, "stars").takeIf(String::isNotBlank)?.let { addProperty("imdbRating", it) }
            text(item, "language_lighttigerNb").takeIf(String::isNotBlank)?.let { addProperty("language", it) }
            text(item, "imdbUrlRef").takeIf(String::isNotBlank)?.let { url ->
                add("links", gson.toJsonTree(listOf(mapOf("name" to "IMDb", "category" to "imdb", "url" to url))))
            }
        }
    }

    private fun getJson(path: String): JsonElement {
        val request = Request.Builder().url("$BASE$path").header("Accept", "application/json")
            .header("User-Agent", "CinemanaNuvioNative/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Cinemana returned HTTP ${response.code}")
            return gson.fromJson(response.body?.string().orEmpty(), JsonElement::class.java)
                ?: error("Empty response from Cinemana")
        }
    }

    private fun extractItems(data: JsonElement): List<JsonObject> {
        if (data.isJsonArray) return data.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        if (!data.isJsonObject) return emptyList()
        val obj = data.asJsonObject
        listOf("info", "results", "videos", "data", "items").forEach { key ->
            val value = obj.get(key)
            if (value != null && value.isJsonArray) return value.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        }
        return emptyList()
    }

    private fun imageUrl(item: JsonObject): String? {
        val value = text(item, "imgObjUrl", "imgMediumThumbObjUrl", "imgThumbObjUrl")
        if (value.isBlank()) return null
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "$BASE/${value.trimStart('/')}"
    }

    private fun text(obj: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = obj.get(key) ?: return@forEach
            if (value.isJsonPrimitive) {
                val text = runCatching { value.asString.trim() }.getOrDefault("")
                if (text.isNotBlank() && text != "null") return text
            }
        }
        return ""
    }

    private fun digits(value: String) = value.trim().takeIf { it.matches(Regex("\\d+")) }.orEmpty()
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun jsonResponse(body: JsonElement, status: Response.IStatus): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body))

    companion object {
        private const val TAG = "CinemanaLocalServer"
        private const val PORT = 7000
        private const val BASE = "https://cinemana.shabakaty.com"
        private const val MAX_SEARCH_PAGES = 60
        private val CATEGORIES = listOf(
            Triple("cinemana_action", "أكشن", "97,84"),
            Triple("cinemana_thriller", "إثارة", "80"),
            Triple("cinemana_crime", "جريمة", "60"),
            Triple("cinemana_western", "حياة الغرب", "89"),
            Triple("cinemana_scifi", "خيال علمي", "78"),
            Triple("cinemana_fantasy", "خيالي", "67"),
            Triple("cinemana_drama", "دراما", "62"),
            Triple("cinemana_animation", "رسوم متحركة", "57"),
            Triple("cinemana_horror", "رعب", "70"),
            Triple("cinemana_romance", "رومانسي", "77"),
            Triple("cinemana_sport", "رياضي", "79"),
            Triple("cinemana_mystery", "غموض", "76"),
            Triple("cinemana_comedy", "كوميدي", "59"),
            Triple("cinemana_adventure", "مغامرة", "56"),
            Triple("cinemana_documentary", "وثائقي", "61")
        )
    }
}
