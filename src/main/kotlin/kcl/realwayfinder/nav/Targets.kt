package kcl.realwayfinder.nav

enum class FishTarget {
	WISE,
	STRONG,
	ELUSIVE,
	WAYFINDER,
	PEARL,
	TREASURE,
	SPIRIT
}

enum class GrottoKind {
	MAGNET_FISH,
	ELUSIVE_FISH,
	PURE_FISH,
	PEARL,
	TREASURE,
	SPIRIT
}

data class GrottoRank(val cost: Int, val type: Int, val bonus: Int)

/** Lower cost, then earlier type, then higher bonus. */
fun grottoPrefer(a: GrottoRank, b: GrottoRank): Boolean {
	if (a.cost != b.cost) return a.cost < b.cost
	if (a.type != b.type) return a.type < b.type
	return a.bonus > b.bonus
}

data class NormalRank(val type: Int, val value: Int, val tie: Int = 0)

/** Earlier priority, then the higher value, then the tie break for an equal hook sum. */
fun normalPrefer(a: NormalRank, b: NormalRank): Boolean {
	if (a.type != b.type) return a.type < b.type
	if (a.value != b.value) return a.value > b.value
	return a.tie > b.tie
}

/**
 * The spot takes its earliest enabled attribute. Null when none of those values are present.
 */
/** Hook sum is a percent. Other enabled kinds must simply have a chance. */
fun islandSpotQualifies(
	strong: Int,
	wise: Int,
	elusive: Int,
	wayfinder: Int,
	pearl: Int,
	treasure: Int,
	spirit: Int,
	enabled: Set<FishTarget>
): Boolean {
	if (enabled.isEmpty()) return false
	val hooks = FishTarget.WISE in enabled || FishTarget.STRONG in enabled
	if (hooks && strong.coerceAtLeast(0) + wise.coerceAtLeast(0) >= 30) return true
	if (FishTarget.ELUSIVE in enabled && elusive > 0) return true
	if (FishTarget.WAYFINDER in enabled && wayfinder > 0) return true
	if (FishTarget.PEARL in enabled && pearl > 0) return true
	if (FishTarget.TREASURE in enabled && treasure > 0) return true
	if (FishTarget.SPIRIT in enabled && spirit > 0) return true
	return false
}

fun normalRank(
	strong: Int,
	wise: Int,
	elusive: Int,
	wayfinder: Int,
	pearl: Int,
	treasure: Int,
	spirit: Int,
	order: List<FishTarget>,
	enabled: Set<FishTarget>
): NormalRank? {
	val hookSum = strong.coerceAtLeast(0) + wise.coerceAtLeast(0)
	val firstHook = order.firstOrNull { it in enabled && (it == FishTarget.WISE || it == FishTarget.STRONG) }
	val tie = if (firstHook == FishTarget.STRONG) strong.coerceAtLeast(0) else wise.coerceAtLeast(0)
	val values = mapOf(
		FishTarget.ELUSIVE to elusive,
		FishTarget.WAYFINDER to wayfinder,
		FishTarget.PEARL to pearl,
		FishTarget.TREASURE to treasure,
		FishTarget.SPIRIT to spirit
	)
	for ((index, target) in order.withIndex()) {
		if (target !in enabled) continue
		if (target == FishTarget.WISE || target == FishTarget.STRONG) {
			if (hookSum > 0) return NormalRank(index, hookSum, tie)
			continue
		}
		val value = values[target] ?: -1
		if (value > 0) return NormalRank(index, value, 0)
	}
	return null
}

fun hourlyClear(text: String): Boolean {
	val lower = text.lowercase()
	return "clear spots" in lower || "island spots refreshed" in lower
}
