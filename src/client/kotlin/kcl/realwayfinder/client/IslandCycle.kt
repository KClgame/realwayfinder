package kcl.realwayfinder.client

import kcl.realwayfinder.nav.IslandJob
import kcl.realwayfinder.nav.islandSpotQualifies
import kcl.realwayfinder.nav.pickIsland
import kcl.spotfilter.client.data.FishingPlace
import kcl.spotfilter.client.data.SpotKind
import kcl.spotfilter.client.data.SpotPool
import kcl.spotfilter.client.data.StockLevel
import kcl.spotfilter.client.parse.PerkType

/** Which enabled island is being scanned or fished. The hour starts again at the top of the list. */
object IslandCycle {
	var duty: String? = null
	var job: IslandJob = IslandJob.SCAN
	var waiting = false
	private var told = ""
	var armedScan: String? = null
	/** Island id whose return-to-spawn replay was already started for this scan trip. */
	var returnedFor: String? = null

	fun enabledOrder(): List<String> = RouteSettings.islandOrder.filter { it in RouteSettings.islands }

	fun resetToTop() {
		val pick = pickIsland(enabledOrder(), RouteSettings.scanned, RouteSettings.finished)
		duty = pick?.id
		job = pick?.job ?: IslandJob.SCAN
		waiting = false
		armedScan = null
		returnedFor = null
		told = ""
	}

	fun matches(place: FishingPlace?): Boolean {
		val wanted = duty ?: return place != null
		return place != null && place.kind != SpotKind.GROTTO && place.shortId.uppercase() == wanted
	}

	/**
	 * True when this island still has a qualifying spot.
	 * Null when the client has not seen any spot there yet.
	 * Skipped and depleted spots are already done.
	 */
	fun remaining(id: String, skipped: Set<String>): Boolean? {
		var seen = false
		val work = SpotPool.all().any { spot ->
			val place = spot.place ?: return@any false
			if (!place.shortId.equals(id, ignoreCase = true) || spot.kind == SpotKind.GROTTO) return@any false
			seen = true
			spot.stock != StockLevel.DEPLETED &&
				"${spot.x},${spot.y},${spot.z}" !in skipped &&
				qualifies(spot)
		}
		if (!seen) return null
		return work
	}

	private fun qualifies(spot: kcl.spotfilter.client.data.FishingSpot): Boolean =
		islandSpotQualifies(
			spot.perkValue(PerkType.STRONG_HOOK),
			spot.perkValue(PerkType.WISE_HOOK),
			spot.perkValue(PerkType.ELUSIVE_CHANCE),
			spot.perkValue(PerkType.WAYFINDER_DATA),
			spot.perkValue(PerkType.PEARL_CHANCE),
			spot.perkValue(PerkType.TREASURE_CHANCE),
			spot.perkValue(PerkType.SPIRIT_CHANCE),
			RouteSettings.fishTargets
		)

	fun say(message: String): Boolean {
		if (told == message) return false
		told = message
		return true
	}
}
