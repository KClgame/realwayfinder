package kcl.realwayfinder.nav

/**
 * Watches the mod's own movement commands. A pattern that repeats five times
 * means the automatic control is stuck in a loop.
 */
class AutoLoop(private val limit: Int = 5) {
	private val history = ArrayDeque<String>()
	private var last = ""

	fun clear() {
		history.clear()
		last = ""
	}

	fun note(cell: String, jumpStart: Boolean, sneakStart: Boolean, moving: Boolean): Boolean {
		var loop = false
		if (jumpStart) loop = push("J:$cell") || loop
		if (sneakStart) loop = push("S:$cell") || loop
		loop = push("${if (moving) "F" else "T"}:$cell") || loop
		return loop
	}

	private fun push(token: String): Boolean {
		if (token == last) return false
		last = token
		history.addLast(token)
		while (history.size > 48) history.removeFirst()
		if (!repeated()) return false
		clear()
		return true
	}

	private fun repeated(): Boolean {
		val list = history.toList()
		val maxLen = minOf(8, list.size / limit)
		for (len in 1..maxLen) {
			if (list.size < len * limit) continue
			val tail = list.takeLast(len)
			var same = true
			for (k in 1 until limit) {
				val start = list.size - (k + 1) * len
				if (list.subList(start, start + len) != tail) {
					same = false
					break
				}
			}
			if (same) return true
		}
		return false
	}
}
