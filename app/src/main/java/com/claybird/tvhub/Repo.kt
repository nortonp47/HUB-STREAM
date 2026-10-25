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

    // What the provider sent, before your hide/rename/order choices (see Curation).
    var rawLiveCategories: List<Category> = emptyList(); private set
    var rawLive: List<Channel> = emptyList(); private set
    var rawMovieCategories: List<Category> = emptyList(); private set
    var rawMovies: List<Movie> = emptyList(); private set
    var rawShowCategories: List<Category> = emptyList(); private set
    var rawShows: List<Show> = emptyList(); private set
    /** Bumped when your hide/rename choices change, so screens rebuild from the new lists. */
    var curationVersion by mutableStateOf(0)
        private set

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
        installCrashLog()
        val p = prefs()
        val json = p.getString("playlists", null)
        if (json != null) {
            val arr = JSONArray(json)
            playlists = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Playlist(o.getString("id"), o.getString("name"), o.getString("server"), o.getString("username"), o.getString("password"))
            }
            activeId = p.getString("active", playlists.firstOrNull()?.id ?: "") ?: ""
            // The app died while loading this playlist last time: don't loop, go back to another one.
            val dying = p.getString("loading_id", "") ?: ""
            if (dying.isNotEmpty()) {
                p.edit().remove("loading_id").apply()
                if (dying == activeId) {
                    val bad = playlists.firstOrNull { it.id == dying }
                    val other = playlists.firstOrNull { it.id != dying }
                    notice = "The app crashed while loading \"${bad?.name ?: "a playlist"}\" (probably too big for this device's memory)." +
                        if (other != null) " Switched back to \"${other.name}\"." else ""
                    if (other != null) activeId = other.id
                }
            }
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
        rawLive = emptyList(); rawLiveCategories = emptyList(); rawMovies = emptyList(); rawMovieCategories = emptyList()
        rawShows = emptyList(); rawShowCategories = emptyList()
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
        prefs().edit().remove("curation_$id").apply()
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
            runCatching {
                var changedActive = false
                Cloud.pullCuration().forEach { (pid, text) ->
                    val cloudD = CurationData.fromJson(text)
                    val localD = curationText(pid)?.let { CurationData.fromJson(it) }
                    if (localD == null || cloudD.updatedAt > localD.updatedAt) {
                        prefs().edit().putString("curation_$pid", text).apply()
                        if (pid == activeId) { Curation.load(pid); changedActive = true }
                    } else if (localD.updatedAt > cloudD.updatedAt) Cloud.pushCuration(pid, localD.toJson())
                }
                if (changedActive && loaded) applyCuration()
            }
            "Synced: ${merged.size} playlist(s), ${favorites.size} favorite(s)"
        } catch (e: Exception) {
            "Couldn't sync: ${e.message}"
        }
    }

    // ---- your hide / rename / order choices ----

    fun curationText(playlistId: String): String? = prefs().getString("curation_$playlistId", null)

    fun saveCuration(playlistId: String, d: CurationData) {
        if (playlistId.isEmpty()) return
        prefs().edit().putString("curation_$playlistId", d.toJson()).apply()
    }

    /** Builds the lists the screens use from the provider's lists plus your choices. */
    fun applyCuration() {
        val d = Curation.data
        val catRules = Rules(d.catRules)
        val nameRules = Rules(d.nameRules)
        fun cats(k: String, raw: List<Category>): List<Category> {
            val order = d.order[k].orEmpty()
            return raw.filter { c -> "$k|${c.id}" !in d.hiddenCats && !catRules.matches(c.name) }
                .sortedBy { c -> order.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { c -> d.catNames["$k|${c.id}"]?.let { Category(c.id, it) } ?: c }
        }
        fun visible(k: String, raw: List<Category>): Set<String> = cats(k, raw).map { it.id }.toHashSet()
        val lcs = cats("L", rawLiveCategories); val mcs = cats("M", rawMovieCategories); val scs = cats("S", rawShowCategories)
        val lv = visible("L", rawLiveCategories); val mv = visible("M", rawMovieCategories); val sv = visible("S", rawShowCategories)
        val known = { all: List<Category>, id: String -> all.any { it.id == id } }
        live = rawLive.filter { (it.categoryId in lv || !known(rawLiveCategories, it.categoryId)) && "L|${it.id}" !in d.hiddenItems && !nameRules.matches(it.name) }
        movies = rawMovies.filter { (it.categoryId in mv || !known(rawMovieCategories, it.categoryId)) && "M|${it.id}" !in d.hiddenItems && !nameRules.matches(it.name) }
        shows = rawShows.filter { (it.categoryId in sv || !known(rawShowCategories, it.categoryId)) && "S|${it.id}" !in d.hiddenItems && !nameRules.matches(it.name) }
        liveCategories = lcs; movieCategories = mcs; showCategories = scs
        if (loaded) status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
        curationVersion++
    }

    fun pushCurationBackground() {
        val id = activeId
        if (id.isEmpty() || !Cloud.signedIn) return
        val text = curationText(id) ?: return
        io.launch { runCatching { Cloud.pushCuration(id, text) } }
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
    private fun cacheFile(id: String, name: String) = File(appContext.filesDir, "cache_${id}_$name.json")

    /** The cache file for a catalog, downloaded first if missing or stale. Never read into one String: it can be huge. */
    private fun cached(id: String, name: String, force: Boolean, load: (File) -> Unit): File {
        val f = cacheFile(id, name)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MAX_AGE_MS) {
            return f
        }
        return try {
            load(f)
            f
        } catch (e: Exception) {
            // Offline or the panel is down: an old copy is better than nothing.
            if (f.exists()) f else throw e
        }
    }

    /** Downloads (or reads from cache) every catalog. Returns an error message or null on success. */
    /** A message to show the user once (e.g. after a crash), cleared when dismissed. */
    var notice by mutableStateOf("")

    private fun installCrashLog() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(appContext.filesDir, "last_crash.txt").writeText(
                    "${java.util.Date()}  thread ${t.name}\n${sw.toString().take(6000)}")
            }
            old?.uncaughtException(t, e)
        }
    }

    fun lastCrash(): String = runCatching { java.io.File(appContext.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    fun clearCrash() { runCatching { java.io.File(appContext.filesDir, "last_crash.txt").delete() } }

    suspend fun refresh(force: Boolean): String? = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext "No playlist"
        val forId = activeId
        prefs().edit().putString("loading_id", forId).commit()
        try {
            if (serverZone == null) serverZone = runCatching {
                JSONObject(api.raw(null)).optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
            }.getOrNull()
            status = "Loading live categories..."
            val lc = XtreamApi.parseCategories(cached(forId, "live_cat", force) { api.download("get_live_categories", it) })
            status = "Loading live channels..."
            val lv = XtreamApi.parseChannels(cached(forId, "live", force) { api.download("get_live_streams", it) })
            status = "Loading movies..."
            val mc = XtreamApi.parseCategories(cached(forId, "vod_cat", force) { api.download("get_vod_categories", it) })
            val mv = XtreamApi.parseMovies(cached(forId, "vod", force) { api.download("get_vod_streams", it) })
            status = "Loading shows..."
            val sc = XtreamApi.parseCategories(cached(forId, "series_cat", force) { api.download("get_series_categories", it) })
            val sh = XtreamApi.parseShows(cached(forId, "series", force) { api.download("get_series", it) })
            if (forId != activeId) return@withContext null // switched playlists meanwhile
            rawLiveCategories = lc; rawLive = lv; rawMovieCategories = mc; rawMovies = mv; rawShowCategories = sc; rawShows = sh
            Curation.load(forId)
            applyCuration()
            status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
            loaded = true
            null
        } catch (e: Throwable) {
            val msg = if (e is OutOfMemoryError) "Not enough memory for this playlist's catalog" else (e.message ?: "Unknown error")
            status = "Couldn't load: $msg"
            msg
        } finally {
            prefs().edit().remove("loading_id").commit()
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
