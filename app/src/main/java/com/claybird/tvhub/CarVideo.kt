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
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
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

    override fun onCreate() {
        super.onCreate()
        CarLog.add(this, "Video service started by Android Auto")
    }

    override fun onCreateSession(): Session {
        CarLog.add(this, "Video session created (the car opened Hub Video)")
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                CarLog.add(carContext, "Video screen created; car API level ${carContext.carAppApiLevel}")
                return CarVideoScreen(carContext)
            }
        }
    }
}

/** A short record of what Android Auto did with HUB, shown in Settings so problems can be diagnosed without the car. */
object CarLog {
    var lines by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set
    private var ctx: android.content.Context? = null

    fun attach(c: android.content.Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        lines = ctx!!.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).getString("carlog", "")!!.split("\n").filter { it.isNotBlank() }
    }

    fun add(c: android.content.Context, msg: String) {
        attach(c)
        val t = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines = (lines + "$t  $msg").takeLast(60)
        ctx!!.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).edit().putString("carlog", lines.joinToString("\n")).apply()
    }

    fun clear() {
        lines = emptyList()
        ctx?.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE)?.edit()?.remove("carlog")?.apply()
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
                CarLog.add(carContext, "Car gave a video surface ${c.width}x${c.height}")
                surface = c.surface
                CarBridge.surface = c.surface
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                player.setVideoSurface(c.surface)
                // Something already playing from the phone: show it on the car screen too.
                if (player.currentMediaItem != null) CarBridge.casting = true
            }

            override fun onSurfaceDestroyed(c: SurfaceContainer) {
                CarLog.add(carContext, "Car took the video surface away")
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

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template = try {
        // The car allows only two actions with text in the navigation strip, so the rest are icons.
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Channels").setOnClickListener {
                screenManager.push(GroupsScreen(carContext, this))
            }.build())
            .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_previous)).setOnClickListener { playAt(CarState.index - 1) }.build())
            .addAction(Action.Builder().setIcon(icon(if (player.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play))
                .setOnClickListener {
                    player.playWhenReady = !player.playWhenReady
                    invalidate()
                }.build())
            .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_next)).setOnClickListener { playAt(CarState.index + 1) }.build())
            .build()
        NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    } catch (e: Throwable) {
        // Show the problem on the car screen instead of crashing Android Auto.
        CarLog.add(carContext, "Template error: ${e.javaClass.simpleName}: ${e.message}")
        MessageTemplate.Builder("Hub video could not start: ${e.javaClass.simpleName}: ${e.message}").setTitle("Hub").setHeaderAction(Action.APP_ICON).build()
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

@androidx.compose.runtime.Composable
fun CarLogView() {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { CarLog.attach(context) }
    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp)) {
        androidx.compose.material3.Text("Android Auto log", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = HubGreen)
        androidx.compose.material3.Text(
            "Connect to the car, open Hub Video there, then come back here. An empty list means Android Auto never opened it.",
            fontSize = 13.sp, color = HubMuted, modifier = androidx.compose.ui.Modifier.padding(vertical = 6.dp),
        )
        FocusButton("Clear", androidx.compose.ui.Modifier.fillMaxWidth().height(44.dp)) { CarLog.clear() }
        androidx.compose.foundation.lazy.LazyColumn(modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)) {
            if (CarLog.lines.isEmpty()) item { androidx.compose.material3.Text("(nothing yet)", color = HubMuted) }
            androidx.compose.foundation.lazy.items(CarLog.lines.reversed()) { androidx.compose.material3.Text(it, fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp)) }
        }
    }
}
