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

    /** Playlists that are switched on. Several can be on at once; their channels, movies and shows are merged. */
    var enabledIds by mutableStateOf<Set<String>>(emptySet())
        private set
    val enabled: List<Playlist> get() = playlists.filter { it.id in enabledIds }
    fun isEnabled(id: String) = id in enabledIds

    val active: Playlist? get() = playlists.firstOrNull { it.id == activeId }
    val creds: Credentials? get() = active?.let { Credentials(it.server, it.username, it.password) }
    val api: XtreamApi? get() = creds?.let { c -> apiFor(c) }
    private val apiCache = HashMap<Credentials, XtreamApi>()
    private fun apiFor(c: Credentials): XtreamApi = apiCache.getOrPut(c) { XtreamApi(c) }

    /**
     * With several playlists on, every channel/movie/show id is slot * SLOT + the provider's own id, where slot is the
     * playlist's position among the ones that are on. With one playlist on, ids are the provider's own.
     */
    const val SLOT = 100_000_000
    fun playlistIdOf(id: Int): String = enabled.getOrNull(id / SLOT)?.id ?: activeId
    fun realId(id: Int): Int = id % SLOT
    fun apiForPlaylist(playlistId: String): XtreamApi? =
        playlists.firstOrNull { it.id == playlistId }?.let { apiFor(Credentials(it.server, it.username, it.password)) }
    fun apiOf(id: Int): XtreamApi? = apiForPlaylist(playlistIdOf(id))

    fun movieUrl(id: Int, ext: String): String = apiOf(id)!!.movieUrl(realId(id), ext)
    fun episodeUrl(showId: Int, episodeId: String, ext: String): String = apiOf(showId)!!.episodeUrl(episodeId, ext)
    fun catchupUrl(channelId: Int, startSec: Long, durationMin: Int): String =
        apiOf(channelId)!!.catchupUrl(realId(channelId), startSec, durationMin, zones[playlistIdOf(channelId)])
    suspend fun seriesEpisodes(showId: Int): List<Episode> = apiOf(showId)!!.seriesEpisodes(realId(showId))

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
    private val zones = HashMap<String, String>()

    private val favorites = HashSet<String>()
    var favoritesVersion by mutableStateOf(0)
        private set

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        installCrashLog()
        Profiles.init(appContext)
        AddonStore.init(appContext)
        val p = prefs()
        val json = p.getString("playlists", null)
        if (json != null) {
            val arr = JSONArray(json)
            playlists = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Playlist(o.getString("id"), o.getString("name"), o.getString("server"), o.getString("username"), o.getString("password"))
            }
            activeId = p.getString("active", playlists.firstOrNull()?.id ?: "") ?: ""
            enabledIds = (p.getStringSet("enabled", null) ?: setOf(activeId)).filter { id -> playlists.any { it.id == id } }.toSet()
            if (enabledIds.isEmpty() && activeId.isNotEmpty()) enabledIds = setOf(activeId)
            // The app died while loading this playlist last time: don't loop, go back to another one.
            val dying = p.getString("loading_id", "") ?: ""
            if (dying.isNotEmpty()) {
                p.edit().remove("loading_id").apply()
                if (dying in enabledIds) {
                    val bad = playlists.firstOrNull { it.id == dying }
                    val other = enabled.firstOrNull { it.id != dying } ?: playlists.firstOrNull { it.id != dying }
                    notice = "The app crashed while loading \"${bad?.name ?: "a playlist"}\" (probably too big for this device's memory)." +
                        if (other != null) " Turned it off." else ""
                    if (other != null) {
                        enabledIds = (enabledIds - dying).ifEmpty { setOf(other.id) }
                        if (activeId == dying) activeId = enabledIds.first()
                    }
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
                enabledIds = setOf(pl.id)
                (p.getStringSet("favorites", emptySet()) ?: emptySet()).forEach { favorites += "${pl.id}|$it" }
                appContext.filesDir.listFiles()?.filter { it.name.startsWith("cache_") }?.forEach { it.delete() }
                persist()
            }
        }
        if (active == null && playlists.isNotEmpty()) activeId = playlists.first().id
        if (enabledIds.isEmpty() && activeId.isNotEmpty()) enabledIds = setOf(activeId)
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
        if (!Profiles.isPrimary) loadProfileData()
    }

    private fun favPref(primary: Boolean = Profiles.isPrimary) = if (primary) "favorites2" else "fav_${Profiles.activeId}"
    private fun histPref(primary: Boolean = Profiles.isPrimary) = if (primary) "history" else "hist_${Profiles.activeId}"

    /** Loads the favorites and watch history of the active profile (or the admin's, when [primary]). */
    private fun loadProfileData(primary: Boolean = Profiles.isPrimary) {
        val p = prefs()
        favorites.clear()
        favorites += p.getStringSet(favPref(primary), emptySet()) ?: emptySet()
        history = runCatching {
            val arr = JSONArray(p.getString(histPref(primary), "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryItem(o.getString("key"), o.optString("pl"), o.getString("kind"), o.getString("id"), o.getString("title"),
                    o.optString("icon"), o.optString("ext"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
        favoritesVersion++
    }

    fun onProfileChanged() {
        loadProfileData()
        if (loaded) applyCuration()
    }

    private fun persistHistory() {
        val arr = JSONArray()
        history.take(200).forEach {
            arr.put(JSONObject().put("key", it.key).put("pl", it.playlistId).put("kind", it.kind).put("id", it.itemId)
                .put("title", it.title).put("icon", it.icon).put("ext", it.ext).put("pos", it.positionMs)
                .put("dur", it.durationMs).put("at", it.updatedAt))
        }
        prefs().edit().putString(histPref(), arr.toString()).apply()
    }

    /** Remember that you watched something (and how far). [push] also sends it to your account. */
    fun recordPlay(
        kind: String, itemId: String, title: String, icon: String, ext: String, positionMs: Long, durationMs: Long, push: Boolean,
        playlistId: String = activeId,
    ) {
        if (playlistId.isEmpty()) return
        val key = "$playlistId|$kind:$itemId"
        val item = HistoryItem(key, playlistId, kind, itemId, title, icon, ext, positionMs, durationMs, System.currentTimeMillis())
        history = (listOf(item) + history.filter { it.key != key }).take(200)
        persistHistory()
        if (push && Cloud.signedIn && Profiles.isPrimary) io.launch { runCatching { Cloud.pushHistory(listOf(item)) } }
    }

    fun historyFor(vararg kinds: String): List<HistoryItem> =
        history.filter { it.playlistId in enabledIds && it.kind in kinds }

    private fun persist() {
        val arr = JSONArray()
        playlists.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("server", it.server)
                .put("username", it.username).put("password", it.password))
        }
        prefs().edit()
            .putString("playlists", arr.toString())
            .putString("active", activeId)
            .putStringSet("enabled", HashSet(enabledIds))
            .putStringSet(favPref(), HashSet(favorites))
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
        raws.clear()
        loaded = false
        status = ""
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
        if (activeId.isEmpty()) activeId = pl.id
        enabledIds = enabledIds + pl.id // switched on alongside the others
        persist()
        resetLists()
        pushPlaylistsInBackground()
    }

    /** Use only this playlist. */
    fun selectPlaylist(id: String) {
        if (playlists.none { it.id == id } || (enabledIds == setOf(id) && activeId == id)) return
        activeId = id
        enabledIds = setOf(id)
        persist()
        resetLists()
    }

    /** Switch a playlist on or off. At least one stays on. */
    fun setEnabled(id: String, on: Boolean) {
        if (playlists.none { it.id == id }) return
        val n = if (on) enabledIds + id else enabledIds - id
        if (n.isEmpty() || n == enabledIds) return
        enabledIds = n
        if (activeId !in n) activeId = enabled.first().id
        persist()
        resetLists()
    }

    fun removePlaylist(id: String) {
        val victim = playlists.firstOrNull { it.id == id } ?: return
        playlists = playlists.filter { it.id != id }
        favorites.removeAll { it.startsWith("$id|") }
        prefs().edit().remove("curation_$id").apply()
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("cache_${id}_") || it.name == "epg_$id.bin.gz" }?.forEach { it.delete() }
        val wasOn = id in enabledIds
        enabledIds = enabledIds - id
        if (enabledIds.isEmpty()) enabledIds = playlists.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
        if (activeId == id || activeId !in enabledIds) activeId = enabledIds.firstOrNull() ?: ""
        if (wasOn) resetLists()
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
    suspend fun syncWithAccount(): String {
        val swap = !Profiles.isPrimary
        if (swap) loadProfileData(true)
        try { return syncInner() } finally { if (swap) loadProfileData(false) }
    }

    private suspend fun syncInner(): String = withContext(Dispatchers.IO) {
        if (!Cloud.signedIn) return@withContext "Not signed in"
        try {
            val firstTime = prefs().getString("synced_for", null) != Cloud.email
            val cloud = Cloud.pullPlaylists()
            val cloudKeys = cloud.map { "${it.server.lowercase()}|${it.username}" }.toSet()
            val localOnly = playlists.filter { "${it.server.lowercase()}|${it.username}" !in cloudKeys }
            val merged = cloud + (if (firstTime) localOnly else emptyList())
            val before = activeId
            val oldEnabled = enabledIds
            val oldActive = active
            // If this device's active playlist is the same service as a cloud one, keep it active.
            val newActive = merged.firstOrNull { it.id == before }
                ?: oldActive?.let { o -> merged.firstOrNull { it.server.equals(o.server, true) && it.username == o.username } }
                ?: merged.firstOrNull()
            playlists = merged
            activeId = newActive?.id ?: ""
            val keepOn = oldEnabled.filter { id -> merged.any { it.id == id } }.toSet()
            enabledIds = keepOn.ifEmpty { if (activeId.isEmpty()) emptySet() else setOf(activeId) }
            if (activeId !in enabledIds) activeId = enabledIds.firstOrNull() ?: ""
            if (activeId != before || enabledIds != oldEnabled) resetLists()

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
                        if (pid in enabledIds) { Curation.load(pid); changedActive = true }
                    } else if (localD.updatedAt > cloudD.updatedAt) Cloud.pushCuration(pid, localD.toJson())
                }
                if (changedActive && loaded) applyCuration()
            }
            runCatching {
                val cloudAddons = Cloud.pullAddons()
                val local = AddonStore.addons
                val extra = local.filter { l -> cloudAddons.none { it.url == l.url } }
                if (firstTime) {
                    AddonStore.replace(cloudAddons + extra)
                    if (extra.isNotEmpty()) Cloud.pushAddons(AddonStore.addons)
                } else AddonStore.replace(cloudAddons)
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

    /** What one playlist sent, with the provider's own ids. */
    class Raw(
        val lc: List<Category>, val lv: List<Channel>, val mc: List<Category>, val mv: List<Movie>,
        val sc: List<Category>, val sh: List<Show>,
    )
    private val raws = LinkedHashMap<String, Raw>()
    fun rawFor(playlistId: String): Raw? = raws[playlistId]

    /** Builds the lists the screens use from each playlist's lists plus your choices for it, merged. */
    fun applyCuration() {
        val multi = enabled.size > 1
        val lcs = ArrayList<Category>(); val mcs = ArrayList<Category>(); val scs = ArrayList<Category>()
        val lvs = ArrayList<Channel>(); val mvs = ArrayList<Movie>(); val shs = ArrayList<Show>()
        val kid = Profiles.isKid
        enabled.forEachIndexed { slot, pl ->
            val raw = raws[pl.id] ?: return@forEachIndexed
            val d = curationText(pl.id)?.let { CurationData.fromJson(it) } ?: CurationData()
            val catRules = Rules(d.catRules)
            val nameRules = Rules(d.nameRules)
            fun ns(c: String) = if (multi) "$slot~$c" else c
            fun tag(id: Int) = slot * SLOT + id
            fun label(n: String) = if (multi) "[${pl.name}] $n" else n
            fun cats(k: String, rawCats: List<Category>): List<Category> {
                val order = d.order[k].orEmpty()
                return rawCats.filter { c -> "$k|${c.id}" !in d.hiddenCats && !catRules.matches(c.name) }
                    .sortedBy { c -> order.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            val lc = cats("L", raw.lc); val mc = cats("M", raw.mc); val sc = cats("S", raw.sc)
            val lv = lc.map { it.id }.toHashSet(); val mv = mc.map { it.id }.toHashSet(); val sv = sc.map { it.id }.toHashSet()
            val kl = raw.lc.map { it.id }.toHashSet(); val km = raw.mc.map { it.id }.toHashSet(); val ks = raw.sc.map { it.id }.toHashSet()
            val nameL = raw.lc.associate { it.id to it.name }; val nameM = raw.mc.associate { it.id to it.name }; val nameS = raw.sc.associate { it.id to it.name }
            fun named(k: String, c: Category) = label(d.catNames["$k|${c.id}"] ?: c.name)
            lc.forEach { lcs += Category(ns(it.id), named("L", it)) }
            mc.forEach { mcs += Category(ns(it.id), named("M", it)) }
            sc.forEach { scs += Category(ns(it.id), named("S", it)) }
            for (c in raw.lv) if ((c.categoryId in lv || c.categoryId !in kl) && "L|${c.id}" !in d.hiddenItems && !nameRules.matches(c.name) &&
                (!kid || KidSafe.live(c.name, nameL[c.categoryId].orEmpty())))
                lvs += c.copy(id = tag(c.id), categoryId = ns(c.categoryId))
            for (m in raw.mv) if ((m.categoryId in mv || m.categoryId !in km) && "M|${m.id}" !in d.hiddenItems && !nameRules.matches(m.name) &&
                (!kid || KidSafe.title(m.name, nameM[m.categoryId].orEmpty())))
                mvs += m.copy(id = tag(m.id), categoryId = ns(m.categoryId))
            for (sh in raw.sh) if ((sh.categoryId in sv || sh.categoryId !in ks) && "S|${sh.id}" !in d.hiddenItems && !nameRules.matches(sh.name) &&
                (!kid || KidSafe.title(sh.name, nameS[sh.categoryId].orEmpty())))
                shs += sh.copy(id = tag(sh.id), categoryId = ns(sh.categoryId))
        }
        if (kid) {
            val a = lvs.map { it.categoryId }.toHashSet(); val b = mvs.map { it.categoryId }.toHashSet(); val c = shs.map { it.categoryId }.toHashSet()
            lcs.retainAll { it.id in a }; mcs.retainAll { it.id in b }; scs.retainAll { it.id in c }
        }
        live = lvs; movies = mvs; shows = shs
        liveCategories = lcs; movieCategories = mcs; showCategories = scs
        if (loaded) status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
        curationVersion++
    }

    fun pushAddonsBackground(removed: String? = null) {
        if (!Cloud.signedIn) return
        io.launch {
            runCatching { if (removed != null) Cloud.deleteAddon(removed) else Cloud.pushAddons(AddonStore.addons) }
        }
    }

    fun pushCurationBackground() {
        if (!Cloud.signedIn) return
        for (id in enabledIds) {
            val text = curationText(id) ?: continue
            io.launch { runCatching { Cloud.pushCuration(id, text) } }
        }
    }

    // ---- favorites (per playlist) ----
    private fun favKey(kind: Kind, id: Int) = "${playlistIdOf(id)}|${kind.name}:${realId(id)}"

    fun isFavorite(kind: Kind, id: Int) = favorites.contains(favKey(kind, id))

    fun toggleFavorite(kind: Kind, id: Int) {
        val key = favKey(kind, id)
        val added = !favorites.remove(key)
        if (added) favorites.add(key)
        persist()
        favoritesVersion++
        if (Cloud.signedIn && Profiles.isPrimary) io.launch {
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
        val list = enabled
        if (list.isEmpty()) return@withContext "No playlist"
        val keyNow = list.map { it.id }
        val got = LinkedHashMap<String, Raw>()
        var firstError: String? = null
        for (pl in list) {
            val forId = pl.id
            val api = apiForPlaylist(forId) ?: continue
            prefs().edit().putString("loading_id", forId).commit()
            val tag = if (list.size > 1) "${pl.name}: " else ""
            try {
                if (zones[forId] == null) runCatching {
                    JSONObject(api.raw(null)).optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
                }.getOrNull()?.let { zones[forId] = it }
                status = "${tag}Loading live categories..."
                val lc = XtreamApi.parseCategories(cached(forId, "live_cat", force) { api.download("get_live_categories", it) })
                status = "${tag}Loading live channels..."
                val lv = XtreamApi.parseChannels(cached(forId, "live", force) { api.download("get_live_streams", it) })
                status = "${tag}Loading movies..."
                val mc = XtreamApi.parseCategories(cached(forId, "vod_cat", force) { api.download("get_vod_categories", it) })
                val mv = XtreamApi.parseMovies(cached(forId, "vod", force) { api.download("get_vod_streams", it) })
                status = "${tag}Loading shows..."
                val sc = XtreamApi.parseCategories(cached(forId, "series_cat", force) { api.download("get_series_categories", it) })
                val sh = XtreamApi.parseShows(cached(forId, "series", force) { api.download("get_series", it) })
                got[forId] = Raw(lc, lv, mc, mv, sc, sh)
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError) "not enough memory for this playlist's catalog" else (e.message ?: "unknown error")
                if (firstError == null) firstError = "${pl.name}: $msg"
            } finally {
                prefs().edit().remove("loading_id").commit()
            }
        }
        if (keyNow != enabled.map { it.id }) return@withContext null // switched playlists meanwhile
        if (got.isEmpty()) {
            status = "Couldn't load: $firstError"
            return@withContext firstError
        }
        raws.clear(); raws.putAll(got)
        loaded = true
        applyCuration()
        status = "${live.size} channels, ${movies.size} movies, ${shows.size} shows"
        if (firstError != null) notice = "Couldn't load $firstError"
        firstError
    }

    // ---- guide snippets, fetched on demand and kept for a few minutes ----
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()

    suspend fun epg(streamId: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val key = "$streamId"
        epgCache[key]?.let { if (now - it.first < 5 * 60_000L) return it.second }
        val list = withContext(Dispatchers.IO) { runCatching { apiOf(streamId)?.shortEpg(realId(streamId)) }.getOrNull() ?: emptyList() }
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
        val a = apiOf(c.id)!!
        val r = realId(c.id)
        return PlayItem(c.name, a.liveUrl(r), a.liveTsUrl(r), true, c.id)
    }
}
