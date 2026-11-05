package kcl.realwayfinder

object RouteLock {
	const val STUCK_TICKS = 200

	/**
	 * A live spot keeps its route. A depleted or missing spot is replaced at once,
	 * including when the route never finished. A complete route is also replaced
	 * when the bias changes, or when the player has not moved for five seconds.
	 * Standing on the cast, or turning to cast, does not count as being stuck.
	 */
	fun shouldReplace(
		lockedComplete: Boolean,
		spotValid: Boolean,
		biasChanged: Boolean,
		stillTicks: Int,
		onGoal: Boolean,
		docking: Boolean
	): Boolean {
		if (biasChanged) return true
		if (!spotValid) return true
		if (!lockedComplete) return false
		if (onGoal || docking) return false
		return stillTicks >= STUCK_TICKS
	}
}
