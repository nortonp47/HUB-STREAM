package com.claybird.tvhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Something you watched: a channel, a movie, or an episode, with where you stopped. */
data class HistoryItem(
    val key: String, val playlistId: String, val kind: String, val itemId: String, val title: String,
    val icon: String, val ext: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long,
)

enum class Kind(val label: String) { Live("Live TV"), Movies("Movies"), Series("Shows") }

data class Playlist(val id: String, val name: String, val server: String, val username: String, val password: String)

/**
 * Everything the app knows about the provider(s): saved playlists (several allowed, one active),
 * the downloaded catalogs of the active one (cached on disk), and favorites. Playlists and favorites
 * sync through the HUB account when signed in.
 */
object Repo {
    private lateinit var appContext: Context
    private const val PREFS = "hub"
    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var activeId by mutableStateOf("")
        private set

    val active: Playlist? get() = playlists.firstOrNull { it.id == activeId }
    val creds: Credentials? get() = active?.let { Credentials(it.server, it.username, it.password) }
    val api: XtreamApi? get() = creds?.let { c -> apiFor(c) }
    private var apiCache: Pair<Credentials, XtreamApi>? = null
    private fun apiFor(c: Credentials): XtreamApi {
        apiCache?.let { if (it.first == c) return it.second }
        return XtreamApi(c).also { apiCache = c to it }
    }

    val hasLogin: Boolean get() = active != null

    var liveCategories: List<Category> = emptyList(); private set
    var live: List<Channel> = emptyList(); private set
    var movieCategories: List<Category> = emptyList(); private set
    var movies: List<Movie> = emptyList(); private set
    var showCategories: List<Category> = emptyList(); private set
    var shows: List<Show> = emptyList(); private set

    var status by mutableStateOf("")
    var loaded by mutableStateOf(false)

    var history by mutableStateOf<List<HistoryItem>>(emptyList())
        private set
    var serverZone: String? = null
        private set

    private val favorites = HashSet<String>()
    var favoritesVersion by mutableStateOf(0)
        private set

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val p = prefs()
        val json = p.getString("playlists", null)
        if (json != null) {
            val arr = JSONArray(json)
            playlists = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Playlist(o.getString("id"), o.getString("name"), o.getString("server"), o.getString("username"), o.getString("password"))
            }
            activeId = p.getString("active", playlists.firstOrNull()?.id ?: "") ?: ""
            favorites += p.getStringSet("favorites2", emptySet()) ?: emptySet()
        } else {
            // From the single-login versions: turn the old login and favorites into the first playlist.
            val server = p.getString("server", null)
            val user = p.getString("user", null)
            val pass = p.getString("pass", null)
            if (server != null && user != null && pass != null) {
                val pl = Playlist(UUID.randomUUID().toString(), hostOf(server), server, user, pass)
                playlists = listOf(pl)
                activeId = pl.id
                (p.getStringSet("favorites", emptySet()) ?: emptySet()).forEach { favorites += "${pl.id}|$it" }
                appContext.filesDir.listFiles()?.filter { it.name.startsWith("cache_") }?.forEach { it.delete() }
                persist()
            }
        }
        if (active == null && playlists.isNotEmpty()) activeId = playlists.first().id
        prefs().getString("history", null)?.let { j ->
            runCatching {
                val arr = JSONArray(j)
                history = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    HistoryItem(o.getString("key"), o.optString("pl"), o.getString("kind"), o.getString("id"), o.getString("title"),
                        o.optString("icon"), o.optString("ext"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
                }
            }
        }
    }

    private fun persistHistory() {
        val arr = JSONArray()
        history.take(200).forEach {
            arr.put(JSONObject().put("key", it.key).put("pl", it.playlistId).put("kind", it.kind).put("id", it.itemId)
                .put("title", it.title).put("icon", it.icon).put("ext", it.ext).put("pos", it.positionMs)
                .put("dur", it.durationMs).put("at", it.updatedAt))
        }
        prefs().edit().putString("history", arr.toString()).apply()
    }

    /** Remember that you watched something (and how far). [push] also sends it to your account. */
    fun recordPlay(kind: String, itemId: String, title: String, icon: String, ext: String, positionMs: Long, durationMs: Long, push: Boolean) {
        if (activeId.isEmpty()) return
        val key = "$activeId|$kind:$itemId"
        val item = HistoryItem(key, activeId, kind, itemId, title, icon, ext, positionMs, durationMs, System.currentTimeMillis())
        history = (listOf(item) + history.filter { it.key != key }).take(200)
        persistHistory()
        if (push && Cloud.signedIn) io.launch { runCatching { Cloud.pushHistory(listOf(item)) } }
    }

    fun historyFor(vararg kinds: String): List<HistoryItem> =
        history.filter { it.playlistId == activeId && it.kind in kinds }

    private fun persist() {
        val arr = JSONArray()
        playlists.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("server", it.server)
                .put("username", it.username).put("password", it.password))
        }
        prefs().edit()
            .putString("playlists", arr.toString())
            .putString("active", activeId)
            .putStringSet("favorites2", HashSet(favorites))
            .apply()
    }

    private fun hostOf(server: String): String =
        runCatching { java.net.URI(if (server.contains("://")) server else "http://$server").host }.getOrNull() ?: server

    private fun resetLists() {
        live = emptyList(); liveCategories = emptyList()
        movies = emptyList(); movieCategories = emptyList()
        shows = emptyList(); showCategories = emptyList()
        loaded = false
        status = ""
        serverZone = null
        Epg.clear(deleteFile = false)
    }

    // ---- playlists ----

    fun addPlaylist(name: String, c: Credentials) {
        val finalName = name.ifBlank { hostOf(c.server) }.let { base ->
            var n = base; var i = 2
            while (playlists.any { it.name.equals(n, true) }) { n = "$base $i"; i++ }
            n
        }
        val pl = Playlist(UUID.randomUUID().toString(), finalName, c.server.trim(), c.username.trim(), c.password.trim())
        playlists = playlists + pl
        activeId = pl.id
        persist()
        resetLists()
        pushPlaylistsInBackground()
    }

    fun selectPlaylist(id: String) {
        if (id == activeId || playlists.none { it.id == id }) return
        activeId = id
        persist()
        resetLists()
    }

    fun removePlaylist(id: String) {
        val victim = playlists.firstOrNull { it.id == id } ?: return
        playlists = playlists.filter { it.id != id }
        favorites.removeAll { it.startsWith("$id|") }
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("cache_${id}_") || it.name == "epg_$id.bin.gz" }?.forEach { it.delete() }
        if (activeId == id) {
            activeId = playlists.firstOrNull()?.id ?: ""
            resetLists()
        }
        persist()
        favoritesVersion++
        if (Cloud.signedIn) io.launch {
            runCatching { Cloud.deletePlaylist(victim.id) }
            runCatching { Cloud.pushPlaylists(playlists) }
        }
    }

    private fun pushPlaylistsInBackground() {
        if (Cloud.signedIn) io.launch { runCatching { Cloud.pushPlaylists(playlists) } }
    }

    // ---- account sync ----

    /** Pull playlists and favorites from the HUB account and merge. Returns a message. */
    suspend fun syncWithAccount(): String = withContext(Dispatchers.IO) {
        if (!Cloud.signedIn) return@withContext "Not signed in"
        try {
            val firstTime = prefs().getString("synced_for", null) != Cloud.email
            val cloud = Cloud.pullPlaylists()
            val cloudKeys = cloud.map { "${it.server.lowercase()}|${it.username}" }.toSet()
            val localOnly = playlists.filter { "${it.server.lowercase()}|${it.username}" !in cloudKeys }
            val merged = cloud + (if (firstTime) localOnly else emptyList())
            val before = activeId
            val oldActive = active
            // If this device's active playlist is the same service as a cloud one, keep it active.
            val newActive = merged.firstOrNull { it.id == before }
                ?: oldActive?.let { o -> merged.firstOrNull { it.server.equals(o.server, true) && it.username == o.username } }
                ?: merged.firstOrNull()
            playlists = merged
            activeId = newActive?.id ?: ""
            if (activeId != before) resetLists()

            val cloudFavs = Cloud.pullFavorites()
            val localFavs = HashSet(favorites)
            favorites.clear()
            favorites += cloudFavs
            if (firstTime) {
                favorites += localFavs
                (localFavs - cloudFavs).takeIf { it.isNotEmpty() }?.let { Cloud.pushFavorites(it) }
                if (localOnly.isNotEmpty()) Cloud.pushPlaylists(merged)
            }
            favorites.removeAll { key -> merged.none { key.startsWith(it.id + "|") } }
            persist()
            prefs().edit().putString("synced_for", Cloud.email).apply()
            favoritesVersion++
            runCatching {
                val cloudHist = Cloud.pullHistory()
                val byKey = HashMap<String, HistoryItem>()
                (history + cloudHist).forEach { h -> val o = byKey[h.key]; if (o == null || h.updatedAt > o.updatedAt) byKey[h.key] = h }
                val localNewer = history.filter { l -> cloudHist.none { it.key == l.key && it.updatedAt >= l.updatedAt } }
                history = byKey.values.sortedByDescending { it.updatedAt }.take(200)
                persistHistory()
                if (localNewer.isNotEmpty()) Cloud.pushHistory(localNewer.take(100))
            }
            "Synced: ${merged.size} playlist(s), ${favorites.size} favorite(s)"
        } catch (e: Exception) {
            "Couldn't sync: ${e.message}"
        }
    }

    // ---- favorites (per playlist) ----
    private fun favKey(kind: Kind, id: Int) = "$activeId|${kind.name}:$id"

    fun isFavorite(kind: Kind, id: Int) = favorites.contains(favKey(kind, id))

    fun toggleFavorite(kind: Kind, id: Int) {
        val key = favKey(kind, id)
        val added = !favorites.remove(key)
        if (added) favorites.add(key)
        persist()
        favoritesVersion++
        if (Cloud.signedIn) io.launch {
            runCatching { if (added) Cloud.pushFavorites(listOf(key)) else Cloud.deleteFavorite(key) }
        }
    }

    // ---- cache ----
    private fun cacheFile(name: String) = File(appContext.filesDir, "cache_${activeId}_$name.json")

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
        val api = api ?: return@withContext "No playlist"
        val forId = activeId
        try {
            if (serverZone == null) serverZone = runCatching {
                JSONObject(api.raw(null)).optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
            }.getOrNull()
            status = "Loading live categories..."
            val lc = XtreamApi.parseCategories(cached("live_cat", force) { api.raw("get_live_categories") })
            status = "Loading live channels..."
            val lv = XtreamApi.parseChannels(cached("live", force) { api.raw("get_live_streams") })
            status = "Loading movies..."
            val mc = XtreamApi.parseCategories(cached("vod_cat", force) { api.raw("get_vod_categories") })
            val mv = XtreamApi.parseMovies(cached("vod", force) { api.raw("get_vod_streams") })
            status = "Loading shows..."
            val sc = XtreamApi.parseCategories(cached("series_cat", force) { api.raw("get_series_categories") })
            val sh = XtreamApi.parseShows(cached("series", force) { api.raw("get_series") })
            if (forId != activeId) return@withContext null // switched playlists meanwhile
            liveCategories = lc; live = lv; movieCategories = mc; movies = mv; showCategories = sc; shows = sh
            status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
            loaded = true
            null
        } catch (e: Exception) {
            status = "Couldn't load: ${e.message}"
            e.message ?: "Unknown error"
        }
    }

    // ---- guide snippets, fetched on demand and kept for a few minutes ----
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()

    suspend fun epg(streamId: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val key = "$activeId:$streamId"
        epgCache[key]?.let { if (now - it.first < 5 * 60_000L) return it.second }
        val list = withContext(Dispatchers.IO) { runCatching { api?.shortEpg(streamId) }.getOrNull() ?: emptyList() }
        epgCache[key] = now to list
        return list
    }

    // ---- what the player should play ----
    class PlayItem(val title: String, val url: String, val altUrl: String?, val isLive: Boolean, val streamId: Int = 0)

    /** The playlist the player zaps through with Up/Down while watching live TV. */
    var zapList: List<Channel> = emptyList()
    var zapIndex: Int = 0

    /** Other channels showing the same game, tried automatically if the first one fails. */
    val fallback = java.util.ArrayDeque<Channel>()

    fun playItemFor(c: Channel): PlayItem {
        val a = api!!
        return PlayItem(c.name, a.liveUrl(c.id), a.liveTsUrl(c.id), true, c.id)
    }
}
