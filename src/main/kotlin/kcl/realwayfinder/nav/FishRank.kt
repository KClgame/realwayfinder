package kcl.realwayfinder.nav

enum class FishBias {
	WISE,
	STRONG
}

/** Higher is better. Null when Strong + Wise is zero. */
fun fishRank(strong: Int, wise: Int, bias: FishBias): Double? {
	val safeStrong = strong.coerceAtLeast(0)
	val safeWise = wise.coerceAtLeast(0)
	val sum = safeStrong + safeWise
	if (sum <= 0) return null
	val share = if (bias == FishBias.WISE) safeWise.toDouble() / sum else safeStrong.toDouble() / sum
	return sum * 1000.0 + share
}
