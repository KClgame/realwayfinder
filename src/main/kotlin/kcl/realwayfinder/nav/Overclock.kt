package kcl.realwayfinder.nav

/** Game right-click order. Config adds 不启用 in front of this list. */
enum class UnstablePick { OFF, ELUSIVE, WAYFINDER, PEARL, TREASURE, SPIRIT }

val UNSTABLE_NAMES = listOf(
	"Elusive Fish Chance",
	"Wayfinder Data",
	"Pearl Chance",
	"Treasure Chance",
	"Spirit Chance"
)

fun UnstablePick.perkName(): String? = when (this) {
	UnstablePick.OFF -> null
	UnstablePick.ELUSIVE -> "Elusive Fish Chance"
	UnstablePick.WAYFINDER -> "Wayfinder Data"
	UnstablePick.PEARL -> "Pearl Chance"
	UnstablePick.TREASURE -> "Treasure Chance"
	UnstablePick.SPIRIT -> "Spirit Chance"
}

fun UnstablePick.menuLabel(): String = when (this) {
	UnstablePick.OFF -> "不启用"
	UnstablePick.ELUSIVE -> "难鱼"
	UnstablePick.WAYFINDER -> "寻路数据"
	UnstablePick.PEARL -> "珍珠"
	UnstablePick.TREASURE -> "宝藏"
	UnstablePick.SPIRIT -> "魂"
}

data class UnstableRead(
	val perk: UnstablePick?,
	val durationMs: Long?,
	val cooldownMs: Long?,
	val activeLeftMs: Long?,
	val cooldownLeftMs: Long?
)

fun isUnstableItem(name: String, lore: List<String>): Boolean {
	val blob = (listOf(name) + lore).joinToString(" ").replace(Regex("§."), "").lowercase()
	return "unstable" in blob && "overclock" in blob
}

/** Next moment the overclock may be turned on again: enable time plus duration plus cooldown. */
fun nextUnstableUse(enabledAtMs: Long, durationMs: Long, cooldownMs: Long): Long =
	enabledAtMs + durationMs + cooldownMs

/**
 * Reads whichever lines the item actually shows.
 * A line with Duration or Cooldown is the stat. Remaining or left on that line is time still running.
 */
fun readUnstable(lines: List<String>): UnstableRead {
	val plain = lines.map { it.replace(Regex("§."), "").trim() }.filter { it.isNotEmpty() }
	var perk: UnstablePick? = null
	var duration: Long? = null
	var cooldown: Long? = null
	var activeLeft: Long? = null
	var cooldownLeft: Long? = null
	val mentioned = plain.mapNotNull { unstablePerkIn(it) }.distinct()
	for (line in plain) {
		val lower = line.lowercase()
		val picked = unstablePerkIn(line)
		val marked = ">" in line || "selected" in lower || "current" in lower || "active" in lower
		if (picked != null && marked) perk = picked
		val times = sumTimes(line) ?: continue
		val remaining = "remain" in lower || "left" in lower
		when {
			"duration" in lower || "lasts" in lower -> if (remaining) activeLeft = times else duration = times
			"cooldown" in lower || "cool down" in lower -> if (remaining) cooldownLeft = times else cooldown = times
			remaining || "active" in lower -> activeLeft = times
		}
	}
	if (perk == null && mentioned.size == 1) perk = mentioned[0]
	return UnstableRead(perk, duration, cooldown, activeLeft, cooldownLeft)
}

private fun unstablePerkIn(line: String): UnstablePick? {
	val lower = line.lowercase()
	return when {
		"elusive fish chance" in lower -> UnstablePick.ELUSIVE
		"wayfinder data" in lower -> UnstablePick.WAYFINDER
		"pearl chance" in lower -> UnstablePick.PEARL
		"treasure chance" in lower -> UnstablePick.TREASURE
		"spirit chance" in lower -> UnstablePick.SPIRIT
		else -> null
	}
}

private val timeUnit = Regex(
	"""(\d+)\s*(hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)\b""",
	RegexOption.IGNORE_CASE
)

private fun sumTimes(line: String): Long? {
	val matches = timeUnit.findAll(line).toList()
	if (matches.isEmpty()) return null
	var ms = 0L
	for (match in matches) {
		val amount = match.groupValues[1].toLong()
		val unit = match.groupValues[2].lowercase()
		ms += when {
			unit.startsWith("h") -> amount * 3_600_000L
			unit.startsWith("m") -> amount * 60_000L
			else -> amount * 1_000L
		}
	}
	return ms
}

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
