package kcl.realwayfinder.nav

data class PlaceButton(val id: String, val titles: List<String>, val model: String)

val PLACE_BUTTONS = listOf(
	PlaceButton("I1", listOf("Verdant Woods"), "verdant_woods"),
	PlaceButton("I2", listOf("Floral Forest"), "floral_forest"),
	PlaceButton("I3", listOf("Dark Grove"), "dark_grove"),
	PlaceButton("I4", listOf("Tropical Overgrowth"), "tropical_overgrowth"),
	PlaceButton("I5", listOf("Coral Shores"), "coral_shores"),
	PlaceButton("I6", listOf("Twisted Swamp"), "twisted_swamp"),
	PlaceButton("I7", listOf("Ancient Sands"), "ancient_sands"),
	PlaceButton("I8", listOf("Blazing Canyon"), "blazing_canyon"),
	PlaceButton("I9", listOf("Ashen Wastes"), "ashen_wastes"),
	PlaceButton("TEMPERATE", listOf("Temperate Grotto", "Sunken Swamp", "Temperate"), "grotto_temperate"),
	PlaceButton("TROPICAL", listOf("Tropical Grotto", "Mirrored Oasis", "Tropical"), "grotto_tropical"),
	PlaceButton("BARREN", listOf("Barren Grotto", "Volcanic Springs", "Barren"), "grotto_barren")
)

fun lobbyScoreboard(text: String): Boolean {
	val cleaned = text.replace(Regex("§."), "")
	return cleaned.contains("MCCI: MAIN ISLAND", ignoreCase = true)
}

fun isTravelCompass(name: String, itemPath: String): Boolean {
	val path = itemPath.lowercase()
	if (path == "compass" || path.endsWith(":compass") || path.endsWith("/compass")) return true
	return name.contains("compass", ignoreCase = true)
}

fun isAnglrPanel(name: String, lore: List<String>): Boolean =
	blob(name, lore).contains("a.n.g.l.r. panel", ignoreCase = true)

fun isFishingSupplies(name: String, lore: List<String>): Boolean =
	blob(name, lore).contains("fishing supplies", ignoreCase = true)

/** Navigator page: an echo shard whose text contains Fishing. Shop is never that button. */
fun isFishingNav(name: String, lore: List<String>, model: String): Boolean {
	if (model.contains("navigator/shop", ignoreCase = true)) return false
	if (blob(name, lore).contains("fishing", ignoreCase = true)) return true
	return model.contains("navigator/island", ignoreCase = true) &&
		!model.contains("fishing/island", ignoreCase = true)
}

fun isEchoShard(itemPath: String): Boolean = itemPath.contains("echo_shard", ignoreCase = true)

/** Slot index inside the 6-row fishing-island chest, after Compass then Fishing. */
fun islandMenuSlot(id: String): Int? = when (id.uppercase()) {
	"I1" -> 20
	"I2" -> 21
	"I3" -> 22
	"TEMPERATE" -> 24
	"I4" -> 29
	"I5" -> 30
	"I6" -> 31
	"TROPICAL" -> 33
	"I7" -> 38
	"I8" -> 39
	"I9" -> 40
	"BARREN" -> 42
	else -> null
}

/**
 * Island button on an echo shard. The visible name must equal an island title, ignoring case.
 * Longer titles are tried first, so "Tropical Overgrowth" is not read as "Tropical".
 */
fun echoShardPlace(name: String, lore: List<String>): String? {
	val titles = PLACE_BUTTONS.flatMap { place -> place.titles.map { it to place.id } }
		.sortedByDescending { it.first.length }
	val lines = (listOf(name) + lore).map { plainLine(it) }.filter { it.isNotEmpty() }
	for (line in lines) {
		for ((title, id) in titles) {
			if (line.equals(title, ignoreCase = true)) return id
		}
	}
	return null
}

private fun plainLine(text: String): String = text.replace(Regex("§."), "").trim()

/** Name wins when it and the model point at different places. */
fun placeId(name: String, lore: List<String>, model: String): String? {
	val text = blob(name, lore)
	val byName = PLACE_BUTTONS.firstOrNull { place ->
		place.titles.any { title -> text.contains(title, ignoreCase = true) }
	}
	if (byName != null) return byName.id
	val byModel = PLACE_BUTTONS.firstOrNull { place ->
		model.contains(place.model, ignoreCase = true)
	}
	return byModel?.id
}

fun placeTitle(id: String): String =
	PLACE_BUTTONS.firstOrNull { it.id.equals(id, ignoreCase = true) }?.titles?.firstOrNull() ?: id

/**
 * Grotto first. Otherwise the first enabled island that is not finished.
 * Finished islands are skipped. No grotto and no island means nowhere to click.
 */
fun travelDestination(
	grotto: String,
	enabledIslandsInOrder: List<String>,
	scanned: Set<String>,
	finished: Set<String>
): String? {
	if (grotto.isNotBlank()) return grotto.uppercase()
	return pickIsland(enabledIslandsInOrder, scanned, finished)?.id
}

fun travelScript(): List<String> = listOf(
	"open-inventory",
	"click-compass",
	"click-fishing",
	"click-island",
	"close"
)

fun suppliesScript(hookClicks: Int, magnetClicks: Int, rodClicks: Int): List<String> {
	val steps = ArrayList<String>(8)
	steps.add("open-inventory")
	steps.add("click-anglr-panel")
	steps.add("click-fishing-supplies")
	repeat(hookClicks.coerceAtLeast(0)) { steps.add("right-hook") }
	repeat(magnetClicks.coerceAtLeast(0)) { steps.add("right-magnet") }
	repeat(rodClicks.coerceAtLeast(0)) { steps.add("right-rod") }
	steps.add("close")
	return steps
}

private fun blob(name: String, lore: List<String>): String = (listOf(name) + lore).joinToString("\n")
