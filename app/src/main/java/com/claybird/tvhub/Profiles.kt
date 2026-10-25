package com.claybird.tvhub

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

enum class Role { ADMIN, ADULT, KID }

class Profile(val id: String, val name: String, val role: Role)

/**
 * People using the app. The first profile ("Admin") is the account owner: it manages playlists,
 * add-ons and organizing, and its favorites and history sync to the account.
 * Other profiles keep their own favorites and history on this device only.
 */
object Profiles {
    var list by mutableStateOf(listOf(Profile("admin", "Admin", Role.ADMIN)))
        private set
    var activeId by mutableStateOf("admin")
        private set
    private lateinit var ctx: Context

    val active: Profile get() = list.firstOrNull { it.id == activeId } ?: list.first()
    val isKid: Boolean get() = active.role == Role.KID
    val isAdmin: Boolean get() = active.role == Role.ADMIN
    val isPrimary: Boolean get() = activeId == "admin"

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        KidSafe.init(ctx)
        val p = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(p.getString("profiles", "[]"))
            val got = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Profile(o.getString("id"), o.getString("name"), runCatching { Role.valueOf(o.getString("role")) }.getOrDefault(Role.ADULT))
            }
            if (got.isNotEmpty() && got.any { it.id == "admin" }) list = got
        }
        activeId = p.getString("active_profile", "admin") ?: "admin"
        if (list.none { it.id == activeId }) activeId = "admin"
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("role", it.role.name)) }
        ctx.getSharedPreferences("hub", Context.MODE_PRIVATE).edit()
            .putString("profiles", arr.toString()).putString("active_profile", activeId).apply()
    }

    fun add(name: String, role: Role) {
        val id = "p" + System.currentTimeMillis().toString(36)
        list = list + Profile(id, name.trim().ifEmpty { if (role == Role.KID) "Kids" else "Adult" }, role)
        save()
    }

    fun remove(id: String) {
        if (id == "admin" || id == activeId) return
        list = list.filter { it.id != id }
        save()
        ctx.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().remove("fav_$id").remove("hist_$id").apply()
    }

    fun switchTo(id: String) {
        if (id == activeId || list.none { it.id == id }) return
        activeId = id
        save()
        DiscoverState.items = emptyList(); DiscoverState.key = ""; DiscoverState.genre = ""
        if (isKid && DiscoverState.catalog == "year") DiscoverState.catalog = "imdbRating"
        Repo.onProfileChanged()
    }
}

/** Decides what a kid profile (age 14 and under) may see. Anything not clearly fine stays hidden. */
object KidSafe {
    private var ok: Set<String> = emptySet()

    fun init(c: Context) {
        runCatching { ok = c.assets.open("kids_ok.txt").bufferedReader().readLines().filter { it.isNotBlank() }.toHashSet() }
    }

    private val deny = Regex(
        "south park|family guy|american dad|rick and morty|bojack|big mouth|archer|futurama|beavis|bob'?s burgers|king of the hill|harley quinn|" +
            "invincible|hazbin|helluva|solar opposites|aqua teen|robot chicken|metalocalypse|venture bros|sausage party|drawn together|smiling friends|" +
            "midnight gospel|ncis|party of five|friday night lights|\\bgreek\\b|american idol|cosby|chesapeake shores|\\beve\\b|mortal kombat|dead space|" +
            "hentai|xxx|erotic|uncensored|18\\+|not suitable|rated r|tv-ma|killing joke|deadpool|horror|slasher|massacre|murder|\\bsex|naked|nude|porn|" +
            "adult swim|\\bdead\\b|death note|devil may cry|\\bblood|killer|gore|torture|zombie apocalypse|the walking dead|game of thrones|breaking bad",
        RegexOption.IGNORE_CASE,
    )
    private val kidCat = Regex("kid|famil|child|disney|pixar|cartoon|junior|toon|pbs|nick|boomerang|baby|cbeebies|treehouse|ytv", RegexOption.IGNORE_CASE)
    private val animCat = Regex("anim", RegexOption.IGNORE_CASE)
    private val sportsCat = Regex("sport|espn|nfl|nba|mlb|nhl|ncaa|wnba|\\bflo\\b", RegexOption.IGNORE_CASE)

    fun denied(name: String) = deny.containsMatchIn(name)

    private fun key(name: String): String {
        var s = name.trim().replace(Regex("\\s*-\\s*(19|20)\\d{2}$"), "").replace(Regex("\\((19|20)\\d{2}\\)"), "")
        s = s.lowercase().replace("&", "and")
        return s.replace(Regex("[^a-z0-9]+"), "")
    }

    private fun latin(name: String) = name.count { it.code > 0x24F } * 4 < name.length.coerceAtLeast(1)

    fun live(name: String, category: String): Boolean {
        if (denied(name) || !latin(name)) return false
        return sportsCat.containsMatchIn(category) || kidCat.containsMatchIn(category)
    }

    fun title(name: String, category: String): Boolean {
        if (denied(name) || !latin(name)) return false
        if (key(name) in ok) return true
        return kidCat.containsMatchIn(category) && !animCat.containsMatchIn(category)
    }
}

@Composable
fun ProfilesScreen(isTv: Boolean, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("KID") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Who's watching?", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(Profiles.list, key = { it.id }) { p ->
                val label = when (p.role) { Role.ADMIN -> "Admin"; Role.ADULT -> "Adult"; Role.KID -> "Kids (14 and under)" }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FocusButton(
                        (if (p.id == Profiles.activeId) "● " else "") + p.name + "  -  " + label,
                        Modifier.weight(1f).height(54.dp),
                    ) { Profiles.switchTo(p.id); onDone() }
                    if (Profiles.isAdmin && p.id != "admin" && p.id != Profiles.activeId) {
                        Spacer(Modifier.width(8.dp))
                        FocusButton("Remove", Modifier.width(110.dp).height(54.dp), textSize = 14.sp) { Profiles.remove(p.id) }
                    }
                }
            }
        }
        if (Profiles.isAdmin) {
            Spacer(Modifier.height(14.dp))
            Text("Add a profile", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Kids profiles only see approved kids channels, movies and shows (age 14 and under), plus sports. Each profile keeps its own favorites and watch history.",
                fontSize = 13.sp, color = HubMuted, modifier = Modifier.padding(vertical = 4.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(listOf("KID" to "Kids", "ADULT" to "Adult"), role) { role = it }
            FocusButton("Add profile", Modifier.fillMaxWidth().height(48.dp)) {
                Profiles.add(name, Role.valueOf(role)); name = ""
            }
        }
    }
}
