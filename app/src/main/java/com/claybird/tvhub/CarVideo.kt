package com.claybird.tvhub

import android.content.Intent
import android.graphics.Color
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EXPERIMENTAL: video on the Android Auto screen (for passengers; the driver stays on the map).
 * Android Auto has no video API, so this appears as a navigation-style app and draws the video onto
 * the surface the car gives it. Needs "Unknown sources" turned on in Android Auto developer settings.
 */
class HubCarService : CarAppService() {
    // Accepts any Android Auto host. Fine for a personal sideloaded app; a Play Store app would pin Google's hosts.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarVideoScreen(carContext)
    }
}

/** Shared between the phone player and the car screen, so playback started on the phone can show in the car. */
object CarBridge {
    var surface by androidx.compose.runtime.mutableStateOf<Surface?>(null)
    var casting by androidx.compose.runtime.mutableStateOf(false)
    var pv: androidx.media3.ui.PlayerView? = null

    /** Phone view back on screen again (called when the car screen goes away or you tap the button). */
    fun release() {
        if (!casting) return
        casting = false
        pv?.let { v -> val p = v.player; v.player = null; v.player = p }
    }

    fun send(player: androidx.media3.exoplayer.ExoPlayer) {
        val s = surface ?: return
        player.setVideoSurface(s)
        casting = true
    }
}

private object CarState {
    var list: List<Channel> = emptyList()
    var index = 0
    var title = ""
}

class CarVideoScreen(ctx: CarContext) : Screen(ctx) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = PlayerHolder.get(ctx)
    private var surface: Surface? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(c: SurfaceContainer) {
                surface = c.surface
                CarBridge.surface = c.surface
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                player.setVideoSurface(c.surface)
                // Something already playing from the phone: show it on the car screen too.
                if (player.currentMediaItem != null) CarBridge.casting = true
            }

            override fun onSurfaceDestroyed(c: SurfaceContainer) {
                surface = null
                CarBridge.surface = null
                if (CarBridge.casting) CarBridge.release() else player.clearVideoSurface()
            }
        })
    }

    fun playAt(i: Int) {
        val list = CarState.list
        if (list.isEmpty()) return
        CarState.index = ((i % list.size) + list.size) % list.size
        val c = list[CarState.index]
        scope.launch {
            withContext(Dispatchers.IO) { ensureRepoLoaded(carContext) }
            val pi = Repo.playItemFor(c)
            CarState.title = c.name
            player.setMediaItem(MediaItem.fromUri(pi.url))
            player.prepare()
            player.playWhenReady = true
            invalidate()
        }
    }

    fun playList(list: List<Channel>, index: Int) {
        CarState.list = list
        playAt(index)
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Channels").setOnClickListener {
                screenManager.push(GroupsScreen(carContext, this))
            }.build())
            .addAction(Action.Builder().setTitle("Prev").setOnClickListener { playAt(CarState.index - 1) }.build())
            .addAction(Action.Builder().setTitle("Next").setOnClickListener { playAt(CarState.index + 1) }.build())
            .addAction(Action.Builder().setTitle(if (player.playWhenReady) "Pause" else "Play").setOnClickListener {
                player.playWhenReady = !player.playWhenReady
                invalidate()
            }.build())
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }
}

/** Favorites, recents and categories, a page at a time (the car limits how many rows fit while driving). */
class GroupsScreen(ctx: CarContext, private val video: CarVideoScreen) : Screen(ctx) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var groups: List<Pair<String, () -> List<Channel>>>? = null
    private var page = 0

    init {
        scope.launch {
            withContext(Dispatchers.IO) { ensureRepoLoaded(carContext) }
            val g = ArrayList<Pair<String, () -> List<Channel>>>()
            g += "Favorite channels" to { Repo.live.filter { Repo.isFavorite(Kind.Live, it.id) } }
            g += "Recently watched" to {
                Repo.historyFor("Live").mapNotNull { h ->
                    Repo.live.firstOrNull { Repo.realId(it.id).toString() == h.itemId && Repo.playlistIdOf(it.id) == h.playlistId }
                }
            }
            Repo.liveCategories.forEach { cat -> g += cat.name to { Repo.live.filter { it.categoryId == cat.id } } }
            groups = g
            invalidate()
        }
    }

    private fun limit() = runCatching {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(3, 20)

    override fun onGetTemplate(): Template {
        val g = groups ?: return ListTemplate.Builder().setLoading(true).setTitle("Hub").setHeaderAction(Action.BACK).build()
        val per = limit() - 1
        val from = page * per
        val slice = g.drop(from).take(per)
        val items = ItemList.Builder()
        slice.forEach { (name, loader) ->
            items.addItem(Row.Builder().setTitle(name).setOnClickListener {
                screenManager.push(ChannelsScreen(carContext, video, name, loader()))
            }.build())
        }
        if (from + per < g.size) {
            items.addItem(Row.Builder().setTitle("More...").setOnClickListener { page++; invalidate() }.build())
        } else if (page > 0) {
            items.addItem(Row.Builder().setTitle("Back to start").setOnClickListener { page = 0; invalidate() }.build())
        }
        return ListTemplate.Builder().setTitle("Hub").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}

class ChannelsScreen(
    ctx: CarContext, private val video: CarVideoScreen, private val name: String, private val channels: List<Channel>,
) : Screen(ctx) {
    private var page = 0

    private fun limit() = runCatching {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(3, 20)

    override fun onGetTemplate(): Template {
        val per = limit() - 1
        val from = page * per
        val items = ItemList.Builder()
        channels.drop(from).take(per).forEachIndexed { i, c ->
            items.addItem(Row.Builder().setTitle(c.name).setOnClickListener {
                video.playList(channels, from + i)
                screenManager.popToRoot()
            }.build())
        }
        if (channels.isEmpty()) items.setNoItemsMessage("Nothing here")
        if (from + per < channels.size) {
            items.addItem(Row.Builder().setTitle("More...").setOnClickListener { page++; invalidate() }.build())
        } else if (page > 0) {
            items.addItem(Row.Builder().setTitle("Back to start").setOnClickListener { page = 0; invalidate() }.build())
        }
        return ListTemplate.Builder().setTitle(name).setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}
