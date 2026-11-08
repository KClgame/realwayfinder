package kcl.realwayfinder.nav

/** Reserved until Unstable Overclock is unlocked. Nothing reads or clicks it. */
interface UnstableOverclockSlot

enum class IslandOc {
	FISH,
	XP_WAYFINDER,
	PEARL,
	TREASURE,
	SPIRIT
}

fun islandOc(target: FishTarget): IslandOc = when (target) {
	FishTarget.WISE, FishTarget.STRONG, FishTarget.ELUSIVE -> IslandOc.FISH
	FishTarget.WAYFINDER, FishTarget.XP -> IslandOc.XP_WAYFINDER
	FishTarget.PEARL -> IslandOc.PEARL
	FishTarget.TREASURE -> IslandOc.TREASURE
	FishTarget.SPIRIT -> IslandOc.SPIRIT
}

enum class HookPick { KEEP, STRONG, WISE, GLIMMERING, GREEDY, LUCKY }

enum class MagnetPick { KEEP, XP, FISH, PEARL, TREASURE, SPIRIT }

enum class RodPick { KEEP, BOOSTED, SPEEDY, GRACEFUL, GLITCHED, STABLE }

fun <T> advance(values: List<T>, current: T): T {
	val index = values.indexOf(current)
	if (index < 0) return values.first()
	return values[(index + 1) % values.size]
}

val HOOK_NAMES = listOf("Strong Hook", "Wise Hook", "Glimmering Hook", "Greedy Hook", "Lucky Hook")
val MAGNET_NAMES = listOf("XP Magnet", "Fish Magnet", "Pearl Magnet", "Treasure Magnet", "Spirit Magnet")
val ROD_NAMES = listOf("Boosted Rod", "Speedy Rod", "Graceful Rod", "Glitched Rod", "Stable Rod")

fun HookPick.perkName(): String? = when (this) {
	HookPick.KEEP -> null
	HookPick.STRONG -> "Strong Hook"
	HookPick.WISE -> "Wise Hook"
	HookPick.GLIMMERING -> "Glimmering Hook"
	HookPick.GREEDY -> "Greedy Hook"
	HookPick.LUCKY -> "Lucky Hook"
}

fun MagnetPick.perkName(): String? = when (this) {
	MagnetPick.KEEP -> null
	MagnetPick.XP -> "XP Magnet"
	MagnetPick.FISH -> "Fish Magnet"
	MagnetPick.PEARL -> "Pearl Magnet"
	MagnetPick.TREASURE -> "Treasure Magnet"
	MagnetPick.SPIRIT -> "Spirit Magnet"
}

fun RodPick.perkName(): String? = when (this) {
	RodPick.KEEP -> null
	RodPick.BOOSTED -> "Boosted Rod"
	RodPick.SPEEDY -> "Speedy Rod"
	RodPick.GRACEFUL -> "Graceful Rod"
	RodPick.GLITCHED -> "Glitched Rod"
	RodPick.STABLE -> "Stable Rod"
}

fun clicksUntil(order: List<String>, from: String, to: String): Int {
	val start = order.indexOf(from)
	val goal = order.indexOf(to)
	if (start < 0 || goal < 0) return -1
	return (goal - start + order.size) % order.size
}

/** The line after `Overclocked Perk:` is a fixed `> Name` sentence. Empty or unknown names are absent. */
fun readPerkLine(lines: List<String>): String? {
	var armed = false
	for (raw in lines) {
		val line = raw.trim()
		if (line.contains("Overclocked Perk:")) {
			armed = true
			continue
		}
		if (!armed) continue
		if (line.isEmpty()) return null
		if (">" in line) {
			val name = line.substringAfter(">").trim()
			return knownPerk(name)
		}
	}
	return null
}

fun knownPerk(name: String): String? = when (name) {
	in HOOK_NAMES, in MAGNET_NAMES, in ROD_NAMES -> name
	else -> null
}

fun perkFromModel(path: String): String? {
	val base = path.substringAfterLast('/').removeSuffix("_glow")
	return when (base) {
		"strong_hook" -> "Strong Hook"
		"wise_hook" -> "Wise Hook"
		"glimmering_hook" -> "Glimmering Hook"
		"greedy_hook" -> "Greedy Hook"
		"lucky_hook" -> "Lucky Hook"
		"xp_magnet" -> "XP Magnet"
		"fish_magnet" -> "Fish Magnet"
		"pearl_magnet" -> "Pearl Magnet"
		"treasure_magnet" -> "Treasure Magnet"
		"spirit_magnet" -> "Spirit Magnet"
		"boosted_rod" -> "Boosted Rod"
		"speedy_rod" -> "Speedy Rod"
		"graceful_rod" -> "Graceful Rod"
		"glitched_rod" -> "Glitched Rod"
		"stable_rod" -> "Stable Rod"
		else -> null
	}
}

/** Lore text wins. The item model is used only when the text has no known perk. */
fun readPerk(lines: List<String>, model: String): String? = readPerkLine(lines) ?: perkFromModel(model)
