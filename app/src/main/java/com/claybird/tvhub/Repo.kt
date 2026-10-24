package com.claybird.tvhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Kind(val label: String) { Live("Live TV"), Movies("Movies"), Series("Shows") }

/**
 * Everything the app knows about the provider: login (kept only on this device), the
 * downloaded catalogs (cached on disk so the app opens instantly), and favorites.
 */
object Repo {
    private lateinit var appContext: Context
    private const val PREFS = "hub"
    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000

    var creds: Credentials? = null
        private set
    var api: XtreamApi? = null
        private set

    var liveCategories: List<Category> = emptyList(); private set
    var live: List<Channel> = emptyList(); private set
    var movieCategories: List<Category> = emptyList(); private set
    var movies: List<Movie> = emptyList(); private set
    var showCategories: List<Category> = emptyList(); private set
    var shows: List<Show> = emptyList(); private set

    /** Shown on screen while loading. */
    var status by mutableStateOf("")
    var loaded by mutableStateOf(false)

    private val favorites = HashSet<String>()
    var favoritesVersion by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = p.getString("server", null)
        val user = p.getString("user", null)
        val pass = p.getString("pass", null)
        if (server != null && user != null && pass != null) setCreds(Credentials(server, user, pass))
        favorites += p.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    private fun setCreds(c: Credentials) {
        creds = c
        api = XtreamApi(c)
    }

    val hasLogin: Boolean get() = creds != null

    fun saveLogin(c: Credentials) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", c.server).putString("user", c.username).putString("pass", c.password).apply()
        setCreds(c)
        clearCache()
        loaded = false
    }

    fun logout() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("server").remove("user").remove("pass").apply()
        creds = null
        api = null
        clearCache()
        live = emptyList(); liveCategories = emptyList()
        movies = emptyList(); movieCategories = emptyList()
        shows = emptyList(); showCategories = emptyList()
        loaded = false
    }

    // ---- favorites ----
    fun isFavorite(kind: Kind, id: Int) = favorites.contains("${kind.name}:$id")

    fun toggleFavorite(kind: Kind, id: Int) {
        val key = "${kind.name}:$id"
        if (!favorites.remove(key)) favorites.add(key)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", HashSet(favorites)).apply()
        favoritesVersion++
    }

    // ---- cache ----
    private fun cacheFile(name: String) = File(appContext.filesDir, "cache_$name.json")

    private fun clearCache() {
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("cache_") }?.forEach { it.delete() }
    }

    private fun cached(name: String, force: Boolean, load: () -> String): String {
        val f = cacheFile(name)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MAX_AGE_MS) {
            return f.readText()
        }
        return try {
            load().also { f.writeText(it) }
        } catch (e: Exception) {
            // Offline or the panel is down: an old copy is better than nothing.
            if (f.exists()) f.readText() else throw e
        }
    }

    /** Downloads (or reads from cache) every catalog. Returns an error message or null on success. */
    suspend fun refresh(force: Boolean): String? = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext "Not logged in"
        try {
            status = "Loading live categories..."
            liveCategories = XtreamApi.parseCategories(cached("live_cat", force) { api.raw("get_live_categories") })
            status = "Loading live channels..."
            live = XtreamApi.parseChannels(cached("live", force) { api.raw("get_live_streams") })
            status = "Loading movies..."
            movieCategories = XtreamApi.parseCategories(cached("vod_cat", force) { api.raw("get_vod_categories") })
            movies = XtreamApi.parseMovies(cached("vod", force) { api.raw("get_vod_streams") })
            status = "Loading shows..."
            showCategories = XtreamApi.parseCategories(cached("series_cat", force) { api.raw("get_series_categories") })
            shows = XtreamApi.parseShows(cached("series", force) { api.raw("get_series") })
            status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
            loaded = true
            null
        } catch (e: Exception) {
            status = "Couldn't load: ${e.message}"
            e.message ?: "Unknown error"
        }
    }

    // ---- guide snippets, fetched on demand and kept for a few minutes ----
    private val epgCache = HashMap<Int, Pair<Long, List<EpgEntry>>>()

    suspend fun epg(streamId: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epgCache[streamId]?.let { if (now - it.first < 5 * 60_000L) return it.second }
        val list = withContext(Dispatchers.IO) { runCatching { api?.shortEpg(streamId) }.getOrNull() ?: emptyList() }
        epgCache[streamId] = now to list
        return list
    }

    // ---- what the player should play ----
    class PlayItem(val title: String, val url: String, val altUrl: String?, val isLive: Boolean, val streamId: Int = 0)

    /** The playlist the player zaps through with Up/Down while watching live TV. */
    var zapList: List<Channel> = emptyList()
    var zapIndex: Int = 0

    fun playItemFor(c: Channel): PlayItem {
        val a = api!!
        return PlayItem(c.name, a.liveUrl(c.id), a.liveTsUrl(c.id), true, c.id)
    }
}
