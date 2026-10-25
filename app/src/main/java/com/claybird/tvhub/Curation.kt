package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * How you want a playlist to look: which categories and channels are hidden, renamed categories, their order,
 * and text rules that hide things automatically. Kept per playlist, synced through the HUB account.
 * Kind keys: "L" live, "M" movies, "S" shows.
 */
class CurationData(
    val hiddenCats: Set<String> = emptySet(),          // "L|<categoryId>"
    val hiddenItems: Set<String> = emptySet(),         // "L|<streamId>"
    val catNames: Map<String, String> = emptyMap(),    // "L|<categoryId>" -> new name
    val order: Map<String, List<String>> = emptyMap(), // "L" -> category ids, first = top
    val catRules: List<String> = emptyList(),          // hide categories whose name matches
    val nameRules: List<String> = emptyList(),         // hide channels/movies/shows whose name matches
    val updatedAt: Long = 0L,
) {
    val isEmpty get() = hiddenCats.isEmpty() && hiddenItems.isEmpty() && catNames.isEmpty() && order.isEmpty() && catRules.isEmpty() && nameRules.isEmpty()

    fun toJson(): String {
        val o = JSONObject()
        o.put("hc", JSONArray(hiddenCats.toList()))
        o.put("hi", JSONArray(hiddenItems.toList()))
        o.put("cn", JSONObject().also { j -> catNames.forEach { (k, v) -> j.put(k, v) } })
        o.put("or", JSONObject().also { j -> order.forEach { (k, v) -> j.put(k, JSONArray(v)) } })
        o.put("cr", JSONArray(catRules))
        o.put("nr", JSONArray(nameRules))
        o.put("at", updatedAt)
        return o.toString()
    }

    companion object {
        private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        fun fromJson(text: String): CurationData = runCatching {
            val o = JSONObject(text)
            val cn = o.optJSONObject("cn")
            val or = o.optJSONObject("or")
            CurationData(
                hiddenCats = strs(o.optJSONArray("hc")).toSet(),
                hiddenItems = strs(o.optJSONArray("hi")).toSet(),
                catNames = cn?.keys()?.asSequence()?.associateWith { cn.getString(it) } ?: emptyMap(),
                order = or?.keys()?.asSequence()?.associateWith { strs(or.optJSONArray(it)) } ?: emptyMap(),
                catRules = strs(o.optJSONArray("cr")),
                nameRules = strs(o.optJSONArray("nr")),
                updatedAt = o.optLong("at", 0L),
            )
        }.getOrDefault(CurationData())
    }
}

/** A rule is plain text (contains, ignoring case) or "re:" followed by a regular expression. */
class Rules(rules: List<String>) {
    private val tests: List<(String) -> Boolean> = rules.mapNotNull { r ->
        val t = r.trim()
        if (t.isEmpty()) null
        else if (t.startsWith("re:", true)) {
            runCatching { Regex(t.substring(3), RegexOption.IGNORE_CASE) }.getOrNull()?.let { rx -> { s: String -> rx.containsMatchIn(s) } }
        } else { s: String -> s.contains(t, ignoreCase = true) }
    }
    val any get() = tests.isNotEmpty()
    fun matches(s: String): Boolean = tests.any { it(s) }
}

object Curation {
    var data by mutableStateOf(CurationData())
        private set
    private var loadedFor = ""

    fun load(playlistId: String) {
        loadedFor = playlistId
        data = Repo.curationText(playlistId)?.let { CurationData.fromJson(it) } ?: CurationData()
    }

    fun edit(change: (CurationData) -> CurationData) {
        val n = change(data)
        data = CurationData(n.hiddenCats, n.hiddenItems, n.catNames, n.order, n.catRules, n.nameRules, System.currentTimeMillis())
        Repo.saveCuration(loadedFor, data)
    }

    fun key(kind: String, id: String) = "$kind|$id"
}

private fun kindKey(k: Kind) = when (k) { Kind.Live -> "L"; Kind.Movies -> "M"; Kind.Series -> "S" }

/** Hide, rename and reorder categories; rules that hide things by name. Changes apply when you leave this screen. */
@Composable
fun CurateScreen(isTv: Boolean) {
    var kind by remember { mutableStateOf(Kind.Live) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var ruleText by remember { mutableStateOf("") }
    val d = Curation.data
    val k = kindKey(kind)

    DisposableEffect(Unit) { onDispose { Repo.applyCuration(); Repo.pushCurationBackground() } }

    val rawCats = when (kind) { Kind.Live -> Repo.rawLiveCategories; Kind.Movies -> Repo.rawMovieCategories; Kind.Series -> Repo.rawShowCategories }
    val counts: Map<String, Int> = remember(kind, Repo.loaded) {
        when (kind) {
            Kind.Live -> Repo.rawLive.groupingBy { it.categoryId }.eachCount()
            Kind.Movies -> Repo.rawMovies.groupingBy { it.categoryId }.eachCount()
            Kind.Series -> Repo.rawShows.groupingBy { it.categoryId }.eachCount()
        }
    }
    val catRules = Rules(d.catRules)
    val ordered = remember(kind, d.order, rawCats) {
        val o = d.order[k].orEmpty()
        rawCats.sortedBy { c -> o.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Organize playlist", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Text("Hidden things disappear from the guide, browse lists, search and sports. Syncs to your other devices.",
            fontSize = 12.sp, color = HubMuted, modifier = Modifier.padding(horizontal = 16.dp))
        ChipRow(listOf("L" to "Live TV", "M" to "Movies", "S" to "Shows"), k) {
            kind = when (it) { "M" -> Kind.Movies; "S" -> Kind.Series; else -> Kind.Live }; editing = null
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Text("Quick clean", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    FocusButton("Hide adult categories", Modifier.fillMaxWidth().height(44.dp), textSize = 15.sp) {
                        Curation.edit { it.addCatRule("re:\\b(xxx|adult|porn|18\\+|erotic)") }
                    }
                    FocusButton("Hide Spanish / French categories", Modifier.fillMaxWidth().height(44.dp), textSize = 15.sp) {
                        Curation.edit { it.addCatRule("re:\\b(mexico|latin|french|telemundo|univision|dominican|espanol|spanish|france)\\b|^(MX|DMA|FR)\\s*\\|") }
                    }
                    FocusButton("Hide stale ESPN Play (dated 2025)", Modifier.fillMaxWidth().height(44.dp), textSize = 15.sp) {
                        Curation.edit { it.addCatRule("re:^ESPN Play$") }
                    }
                    FocusButton("Hide divider channels (#### USA ####)", Modifier.fillMaxWidth().height(44.dp), textSize = 15.sp) {
                        Curation.edit { it.addNameRule("re:^[#=*_\\-\\s|]{2,}|[#=*]{3,}") }
                    }
                    FocusButton("Hide placeholder events (No EVENT...)", Modifier.fillMaxWidth().height(44.dp), textSize = 15.sp) {
                        Curation.edit { it.addNameRule("re:no event|no stream|no game|coming soon|off air") }
                    }
                }
                Text("Rules", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("Plain text matches anywhere in the name. Start with re: for a regular expression.", fontSize = 12.sp, color = HubMuted)
                if (d.catRules.isEmpty() && d.nameRules.isEmpty()) Text("No rules yet.", fontSize = 14.sp, color = HubMuted)
            }
            items(d.catRules.map { "C" to it } + d.nameRules.map { "N" to it }, key = { it.first + it.second }) { (t, r) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text((if (t == "C") "Category: " else "Name: ") + r, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FocusButton("Remove", Modifier.width(100.dp).height(40.dp), textSize = 14.sp) {
                        Curation.edit { c -> if (t == "C") c.withRules(c.catRules - r, c.nameRules) else c.withRules(c.catRules, c.nameRules - r) }
                    }
                }
            }
            if (!isTv) item {
                OutlinedTextField(value = ruleText, onValueChange = { ruleText = it }, singleLine = true,
                    placeholder = { Text("New rule, e.g. spanish or re:^(PT|BR)\\b") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    FocusButton("Hide categories", Modifier.weight(1f).height(44.dp), textSize = 14.sp) {
                        if (ruleText.isNotBlank()) { Curation.edit { it.addCatRule(ruleText.trim()) }; ruleText = "" }
                    }
                    FocusButton("Hide names", Modifier.weight(1f).height(44.dp), textSize = 14.sp) {
                        if (ruleText.isNotBlank()) { Curation.edit { it.addNameRule(ruleText.trim()) }; ruleText = "" }
                    }
                }
            } else item { Text("Add custom rules on your phone; they sync here.", fontSize = 12.sp, color = HubMuted) }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Categories (${ordered.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(ordered, key = { k + it.id }) { c ->
                val ck = Curation.key(k, c.id)
                val byRule = catRules.matches(c.name)
                val hidden = ck in d.hiddenCats || byRule
                val shown = d.catNames[ck] ?: c.name
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Color(0xFF1A1F2A)).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(shown, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (hidden) HubMuted else Color.White)
                            Text("${counts[c.id] ?: 0} items" + (if (byRule) "  (hidden by a rule)" else if (hidden) "  (hidden)" else "") +
                                (if (shown != c.name) "  was: ${c.name}" else ""), fontSize = 12.sp, color = HubMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FocusButton(if (hidden && !byRule) "Show" else "Hide", Modifier.weight(1f).height(40.dp), textSize = 14.sp) {
                            if (!byRule) Curation.edit { it.toggleCat(ck) }
                        }
                        FocusButton("Up", Modifier.weight(1f).height(40.dp), textSize = 14.sp) {
                            Curation.edit { it.move(k, ordered.map { x -> x.id }, c.id, -1) }
                        }
                        FocusButton("Down", Modifier.weight(1f).height(40.dp), textSize = 14.sp) {
                            Curation.edit { it.move(k, ordered.map { x -> x.id }, c.id, 1) }
                        }
                        if (!isTv) FocusButton("Rename", Modifier.weight(1.3f).height(40.dp), textSize = 14.sp) {
                            editing = if (editing == ck) null else ck; draft = shown
                        }
                    }
                    if (editing == ck) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        FocusButton("Save name", Modifier.fillMaxWidth().height(40.dp), textSize = 14.sp) {
                            Curation.edit { it.rename(ck, draft.trim().takeIf { n -> n.isNotEmpty() && n != c.name }) }
                            editing = null
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun CurationData.copyWith(
    hiddenCats: Set<String> = this.hiddenCats, hiddenItems: Set<String> = this.hiddenItems,
    catNames: Map<String, String> = this.catNames, order: Map<String, List<String>> = this.order,
    catRules: List<String> = this.catRules, nameRules: List<String> = this.nameRules,
) = CurationData(hiddenCats, hiddenItems, catNames, order, catRules, nameRules, updatedAt)

private fun CurationData.addCatRule(r: String) = if (r in catRules) this else copyWith(catRules = catRules + r)
private fun CurationData.addNameRule(r: String) = if (r in nameRules) this else copyWith(nameRules = nameRules + r)
private fun CurationData.withRules(c: List<String>, n: List<String>) = copyWith(catRules = c, nameRules = n)
private fun CurationData.toggleCat(key: String) = copyWith(hiddenCats = if (key in hiddenCats) hiddenCats - key else hiddenCats + key)
private fun CurationData.rename(key: String, name: String?) =
    copyWith(catNames = if (name == null) catNames - key else catNames + (key to name))

private fun CurationData.move(kind: String, current: List<String>, id: String, delta: Int): CurationData {
    val list = current.toMutableList()
    val i = list.indexOf(id)
    val j = i + delta
    if (i < 0 || j < 0 || j >= list.size) return this
    list.removeAt(i); list.add(j, id)
    return copyWith(order = order + (kind to list))
}
