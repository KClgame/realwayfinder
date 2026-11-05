package kcl.realwayfinder.nav

fun spotKey(x: Int, y: Int, z: Int): String = "$x,$y,$z"

/**
 * After a spot is abandoned on the way back to spawn, it is not chosen again
 * until every other spot has also been skipped.
 */
fun selectionOrder(keys: List<String>, skipped: Set<String>): List<String> {
	val fresh = keys.filter { it !in skipped }
	return if (fresh.isEmpty()) keys else fresh
}
