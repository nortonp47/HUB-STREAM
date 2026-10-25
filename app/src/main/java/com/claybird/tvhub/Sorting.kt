package com.claybird.tvhub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sort and filter choices for Movies and Shows, shared by the phone and TV lists. */
object BrowseSort {
    var mode by mutableStateOf("default")
    var minRating by mutableStateOf(0)
    var decade by mutableStateOf(0) // 0 = any year, 1 = older than 2000, else the first year of the decade

    val modes = listOf(
        "default" to "Provider order", "added" to "Newest added", "rating" to "Top rated",
        "year" to "Newest release", "az" to "A to Z",
    )
    val ratings = listOf(0 to "Any rating", 6 to "6+", 7 to "7+", 8 to "8+")
    val decades = listOf(0 to "Any year", 2020 to "2020s", 2010 to "2010s", 2000 to "2000s", 1 to "Before 2000")

    private fun num(s: String) = s.toDoubleOrNull() ?: 0.0
    private fun yearOk(y: Int) = when (decade) {
        0 -> true
        1 -> y in 1..1999
        else -> y in decade..(decade + 9)
    }

    val active get() = mode != "default" || minRating > 0 || decade != 0

    fun movies(list: List<Movie>): List<Movie> {
        val f = list.filter { num(it.rating) >= minRating && yearOk(it.year) }
        return when (mode) {
            "added" -> f.sortedByDescending { it.added }
            "rating" -> f.sortedByDescending { num(it.rating) }
            "year" -> f.sortedWith(compareByDescending<Movie> { it.year }.thenByDescending { num(it.rating) })
            "az" -> f.sortedBy { it.name.lowercase() }
            else -> f
        }
    }

    fun shows(list: List<Show>): List<Show> {
        val f = list.filter { num(it.rating) >= minRating && yearOk(it.year) }
        return when (mode) {
            "added" -> f.sortedByDescending { it.added }
            "rating" -> f.sortedByDescending { num(it.rating) }
            "year" -> f.sortedWith(compareByDescending<Show> { it.year }.thenByDescending { num(it.rating) })
            "az" -> f.sortedBy { it.name.lowercase() }
            else -> f
        }
    }

    fun subtitle(rating: String, year: Int): String {
        val r = num(rating)
        return listOfNotNull(if (r > 0) "Rating %.1f".format(r) else null, if (year > 0) year.toString() else null).joinToString("  ")
    }
}

/** The sort and filter chips. Show for Movies and Shows only. */
@Composable
fun SortBar() {
    ChipRow(BrowseSort.modes.map { it.first to it.second }, BrowseSort.mode) { BrowseSort.mode = it }
    ChipRow(BrowseSort.ratings.map { it.first.toString() to it.second }, BrowseSort.minRating.toString()) { BrowseSort.minRating = it.toInt() }
    ChipRow(BrowseSort.decades.map { it.first.toString() to it.second }, BrowseSort.decade.toString()) { BrowseSort.decade = it.toInt() }
}
