package kcl.realwayfinder.nav

enum class BiteResult { WAIT, HOME, SKIP }

/** First dry visit is 30 seconds, the next is 40. A catch cancels the timeout. */
fun biteResult(failures: Int, ticks: Int, caught: Boolean): BiteResult {
	if (caught) return BiteResult.WAIT
	val limit = if (failures >= 1) 40 * 20 else 30 * 20
	if (ticks < limit) return BiteResult.WAIT
	return if (failures >= 1) BiteResult.SKIP else BiteResult.HOME
}
