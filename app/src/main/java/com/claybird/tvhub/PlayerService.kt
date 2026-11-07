package com.claybird.tvhub

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One player for the whole app, so playback can outlive the screen that started it. */
object PlayerHolder {
    private var player: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Hub/1.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player = it }
    }
}

/**
 * Keeps playback alive when you leave the app, puts play/pause in the system media controls, and
 * lets Android Auto browse your channels. In the car it is audio only: video is never shown there.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun item(id: String, title: String, browsable: Boolean, subtitle: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                    .setIsBrowsable(browsable).setIsPlayable(!browsable).build()
            ).build()

    private suspend fun ensureLoaded() = ensureRepoLoaded(applicationContext)

    private fun channelItem(c: Channel) = item("ch:${c.id}", c.name, false)

    private val callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            CarLog.add(applicationContext, "Audio browse opened by ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofItem(item("root", "Hub", true), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val f = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { ensureLoaded() }
                    val list: List<MediaItem> = when {
                        parentId == "root" -> listOf(
                            item("favs", "Favorite channels", true),
                            item("recent", "Recently watched", true),
                            item("cats", "Live channels by category", true),
                        )
                        parentId == "favs" -> Repo.live.filter { Repo.isFavorite(Kind.Live, it.id) }.take(100).map { channelItem(it) }
                        parentId == "recent" -> Repo.historyFor("Live").take(30).mapNotNull { h ->
                            Repo.live.firstOrNull { Repo.realId(it.id).toString() == h.itemId && Repo.playlistIdOf(it.id) == h.playlistId }
                        }.map { channelItem(it) }
                        parentId == "cats" -> Repo.liveCategories.take(100).map { item("cat:${it.id}", it.name, true) }
                        parentId.startsWith("cat:") -> {
                            val cid = parentId.removePrefix("cat:")
                            Repo.live.filter { it.categoryId == cid }.take(200).map { channelItem(it) }
                        }
                        else -> emptyList()
                    }
                    f.set(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
                }.onFailure { f.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)) }
            }
            return f
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(item(mediaId, mediaId, false), null))

        // Turns a tapped channel into something the player can play.
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val f = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { ensureLoaded() }
                    val out = mediaItems.mapNotNull { mi ->
                        if (!mi.mediaId.startsWith("ch:")) return@mapNotNull mi
                        val id = mi.mediaId.removePrefix("ch:").toIntOrNull() ?: return@mapNotNull null
                        val c = Repo.live.firstOrNull { it.id == id } ?: return@mapNotNull null
                        val pi = Repo.playItemFor(c)
                        Repo.recordPlay("Live", Repo.realId(c.id).toString(), c.name, c.icon, "", 0, 0, true, Repo.playlistIdOf(c.id))
                        mi.buildUpon().setUri(pi.url).setMediaMetadata(
                            MediaMetadata.Builder().setTitle(c.name).setArtist("Hub").setIsPlayable(true).setIsBrowsable(false).build()
                        ).build()
                    }.toMutableList()
                    // Car screens are audio only.
                    PlayerHolder.get(applicationContext).let { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    }
                    f.set(out)
                }.onFailure { f.setException(it) }
            }
            return f
        }
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(applicationContext)
        session = MediaLibrarySession.Builder(this, PlayerHolder.get(this), callback).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recent apps: stop playing.
        PlayerHolder.get(this).stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** Makes sure the channel lists are loaded (from the cache if possible) when no screen has done it yet. */
suspend fun ensureRepoLoaded(context: Context) {
    Repo.init(context.applicationContext)
    if (!Repo.loaded && Repo.hasLogin) Repo.refresh(force = false)
}
