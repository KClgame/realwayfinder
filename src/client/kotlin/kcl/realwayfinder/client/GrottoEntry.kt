package kcl.realwayfinder.client

import kcl.realwayfinder.nav.discoveredGrottoClimate
import kcl.spotfilter.client.data.FishingPlace

object GrottoEntry {
	fun onChat(text: String) {
		if (!RouteSettings.grottoEnabled) return
		val place = when (discoveredGrottoClimate(text)?.lowercase()) {
			"temperate" -> FishingPlace.GROTTO_TEMPERATE
			"tropical" -> FishingPlace.GROTTO_TROPICAL
			else -> return
		}
		RouteRuntime.discoverGrotto(place)
	}
}
