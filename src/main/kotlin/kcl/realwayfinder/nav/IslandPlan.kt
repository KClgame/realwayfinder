package kcl.realwayfinder.nav

enum class IslandJob { SCAN, FISH }

data class IslandPick(val id: String, val job: IslandJob)

/**
 * Highest enabled island that is not finished.
 * An unscanned island is scanned. A scanned island is fished. A finished island is skipped.
 */
fun pickIsland(order: List<String>, scanned: Set<String>, finished: Set<String>): IslandPick? {
	for (id in order) {
		if (id in finished) continue
		return if (id in scanned) IslandPick(id, IslandJob.FISH) else IslandPick(id, IslandJob.SCAN)
	}
	return null
}

/**
 * Grotto scanned is only for the current visit. Fishing starts after this entry's scan finishes.
 * Discovery text and "Your Grotto has become unstable..." clear that mark, and the next entry scans again.
 */
fun grottoNeedsScan(placeId: String, scanned: Set<String>, armedScan: String?): Boolean {
	val id = placeId.uppercase()
	val armed = armedScan?.equals(id, ignoreCase = true) == true
	return !(armed && scanned.any { it.equals(id, ignoreCase = true) })
}
