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
