package kcl.realwayfinder.client

import kcl.realwayfinder.nav.FishTarget
import kcl.realwayfinder.nav.GrottoKind
import kcl.spotfilter.client.data.FishingPlace
import kcl.spotfilter.client.data.SpotKind
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object RouteSettings {
	var enabled: Boolean = false
	var grottoEnabled: Boolean = false
	val islands: LinkedHashSet<String> = linkedSetOf("I1", "I2")
	val islandOrder: ArrayList<String> = ArrayList(listOf("I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9"))
	val fishTargets: LinkedHashSet<FishTarget> = linkedSetOf(FishTarget.WISE)
	val fishOrder: ArrayList<FishTarget> = ArrayList(FishTarget.entries)
	val grottoOrder: ArrayList<GrottoKind> = ArrayList(GrottoKind.entries)
	val grottoTargets: LinkedHashSet<GrottoKind> = LinkedHashSet(GrottoKind.entries)
	var revision: Int = 0
	var yawStep: Float = 5f
	var pitchStep: Float = 3f
	var lookAhead: Double = 6.0
	/** Fishing arrival: blocks the feet may sit off the planned cell on each axis. */
	var arriveX: Int = 1
	var arriveZ: Int = 1
	var arriveY: Int = 2
	/** How far the walking goal may sit from the fishing spot, in blocks. */
	var standX: Int = 24
	var standZ: Int = 24
	var standY: Int = 4
	var hudX: Int = -1
	var hudY: Int = -1
	var modToggle: Int = 0
	var modSetup: Int = 0
	var modScan: Int = 0
	var modRecord: Int = 0
	var modSkip: Int = 0
	var modReset: Int = 0
	var modDrop: Int = 0
	val scanned: LinkedHashSet<String> = linkedSetOf()
	val finished: LinkedHashSet<String> = linkedSetOf()
	var scannedHour: Long = -1L
	var dutyIsland: String = ""
	var dutyGrotto: String = ""

	private val path = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("client.txt")

	fun accepts(place: FishingPlace): Boolean {
		if (place.kind == SpotKind.GROTTO) return grottoEnabled
		return place.shortId.uppercase() in islands
	}

	fun load() {
		if (!Files.exists(path)) return
		var sawFish = false
		var legacyBias: FishTarget? = null
		for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
			val parts = line.split('=', limit = 2)
			if (parts.size != 2) continue
			when (parts[0].trim()) {
				"enabled" -> enabled = parts[1].trim().toBooleanStrictOrNull() ?: enabled
				"grotto" -> grottoEnabled = parts[1].trim().toBooleanStrictOrNull() ?: grottoEnabled
				"islands" -> {
					islands.clear()
					parts[1].split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { islands.add(it) }
				}
				"islandOrder" -> {
					val parsed = parts[1].split(',').map { it.trim().uppercase() }.filter { it.matches(Regex("I[1-9]")) }
					if (parsed.isNotEmpty()) {
						islandOrder.clear()
						islandOrder.addAll(parsed.distinct())
						for (id in listOf("I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9")) {
							if (id !in islandOrder) islandOrder.add(id)
						}
					}
				}
				"fish" -> {
					val parsed = parts[1].split(',').mapNotNull { token ->
						FishTarget.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
					}
					if (parsed.isNotEmpty()) {
						fishTargets.clear()
						fishTargets.addAll(parsed)
						sawFish = true
					}
				}
				"bias" -> legacyBias = if (parts[1].trim().equals("strong", ignoreCase = true)) FishTarget.STRONG else FishTarget.WISE
				"fishOrder" -> {
					val parsed = parts[1].split(',').mapNotNull { token ->
						FishTarget.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
					}
					if (parsed.isNotEmpty()) {
						fishOrder.clear()
						fishOrder.addAll(parsed)
						for (target in FishTarget.entries) if (target !in fishOrder) fishOrder.add(target)
					}
				}
				"yawStep" -> yawStep = parts[1].trim().toFloatOrNull()?.coerceIn(1f, 30f) ?: yawStep
				"pitchStep" -> pitchStep = parts[1].trim().toFloatOrNull()?.coerceIn(1f, 20f) ?: pitchStep
				"lookAhead" -> lookAhead = parts[1].trim().toDoubleOrNull()?.coerceIn(1.0, 16.0) ?: lookAhead
				"arriveX" -> arriveX = parts[1].trim().toIntOrNull()?.coerceIn(0, 8) ?: arriveX
				"arriveZ" -> arriveZ = parts[1].trim().toIntOrNull()?.coerceIn(0, 8) ?: arriveZ
				"arriveY" -> arriveY = parts[1].trim().toIntOrNull()?.coerceIn(0, 8) ?: arriveY
				"standX" -> standX = parts[1].trim().toIntOrNull()?.coerceIn(0, 64) ?: standX
				"standZ" -> standZ = parts[1].trim().toIntOrNull()?.coerceIn(0, 64) ?: standZ
				"standY" -> standY = parts[1].trim().toIntOrNull()?.coerceIn(0, 32) ?: standY
				"grottoOrder" -> {
					val parsed = parts[1].split(',').mapNotNull { token ->
						GrottoKind.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
					}
					if (parsed.size == GrottoKind.entries.size) {
						grottoOrder.clear()
						grottoOrder.addAll(parsed)
					}
				}
				"grottoKinds" -> {
					val parsed = parts[1].split(',').mapNotNull { token ->
						GrottoKind.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
					}
					grottoTargets.clear()
					grottoTargets.addAll(parsed)
				}
				"hudX" -> hudX = parts[1].trim().toIntOrNull() ?: hudX
				"hudY" -> hudY = parts[1].trim().toIntOrNull() ?: hudY
				"modToggle" -> modToggle = parts[1].trim().toIntOrNull() ?: modToggle
				"modSetup" -> modSetup = parts[1].trim().toIntOrNull() ?: modSetup
				"modScan" -> modScan = parts[1].trim().toIntOrNull() ?: modScan
				"modRecord" -> modRecord = parts[1].trim().toIntOrNull() ?: modRecord
				"modSkip" -> modSkip = parts[1].trim().toIntOrNull() ?: modSkip
				"modReset" -> modReset = parts[1].trim().toIntOrNull() ?: modReset
				"modDrop" -> modDrop = parts[1].trim().toIntOrNull() ?: modDrop
				"scannedHour" -> scannedHour = parts[1].trim().toLongOrNull() ?: scannedHour
				"scanned" -> {
					scanned.clear()
					parts[1].split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { scanned.add(it) }
				}
				"finished" -> {
					finished.clear()
					parts[1].split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { finished.add(it) }
				}
				"dutyIsland" -> dutyIsland = parts[1].trim().uppercase()
				"dutyGrotto" -> dutyGrotto = parts[1].trim().uppercase()
			}
		}
		for (target in FishTarget.entries) if (target !in fishOrder) fishOrder.add(target)
		val grottoScanned = scanned.filter { isGrottoScanId(it) }
		scanned.clear()
		scanned.addAll(grottoScanned)
		finished.clear()
		scannedHour = hourNow()
		// Last session's grotto must not take the first trip after launch.
		dutyGrotto = ""
		write()
		if (!sawFish && legacyBias != null) {
			fishTargets.clear()
			fishTargets.add(legacyBias)
		}
	}

	fun hourNow(): Long = System.currentTimeMillis() / 3_600_000L

	fun markScanned(id: String) {
		if (!scanned.add(id.uppercase())) return
		scannedHour = hourNow()
		write()
	}

	fun clearScanned(id: String) {
		if (!scanned.remove(id.uppercase())) return
		write()
	}

	fun markFinished(id: String) {
		if (!finished.add(id.uppercase())) return
		write()
	}

	fun clearFinished(id: String) {
		if (!finished.remove(id.uppercase())) return
		write()
	}

	fun isGrottoScanId(id: String): Boolean {
		val key = id.uppercase()
		return key == "TEMPERATE" || key == "TROPICAL" || key == "BARREN" || key.startsWith("GROTTO")
	}

	/** Island hour and client restart. Grotto scan marks stay until the grotto closes. */
	fun resetScanned() {
		scanned.removeAll { !isGrottoScanId(it) }
		finished.clear()
		scannedHour = hourNow()
		write()
	}

	fun noteIsland(id: String) {
		val next = id.uppercase()
		if (dutyIsland == next) return
		dutyIsland = next
		write()
	}

	fun noteGrotto(id: String) {
		val next = id.uppercase()
		if (dutyGrotto == next) return
		dutyGrotto = next
		write()
	}

	fun resetGrottoScanned() {
		if (scanned.none { isGrottoScanId(it) }) return
		scanned.removeAll { isGrottoScanId(it) }
		write()
	}

	fun save() {
		revision++
		write()
	}

	private fun write() {
		Files.createDirectories(path.parent)
		val text = buildString {
			append("enabled=").append(enabled).append('\n')
			append("grotto=").append(grottoEnabled).append('\n')
			append("islands=").append(islands.joinToString(",")).append('\n')
			append("islandOrder=").append(islandOrder.joinToString(",")).append('\n')
			append("fish=").append(fishTargets.joinToString(",") { it.name }).append('\n')
			append("fishOrder=").append(fishOrder.joinToString(",") { it.name }).append('\n')
			append("grottoOrder=").append(grottoOrder.joinToString(",") { it.name }).append('\n')
			append("grottoKinds=").append(grottoTargets.joinToString(",") { it.name }).append('\n')
			append("yawStep=").append(yawStep).append('\n')
			append("pitchStep=").append(pitchStep).append('\n')
			append("lookAhead=").append(lookAhead).append('\n')
			append("arriveX=").append(arriveX).append('\n')
			append("arriveZ=").append(arriveZ).append('\n')
			append("arriveY=").append(arriveY).append('\n')
			append("standX=").append(standX).append('\n')
			append("standZ=").append(standZ).append('\n')
			append("standY=").append(standY).append('\n')
			append("hudX=").append(hudX).append('\n')
			append("hudY=").append(hudY).append('\n')
			append("modToggle=").append(modToggle).append('\n')
			append("modSetup=").append(modSetup).append('\n')
			append("modScan=").append(modScan).append('\n')
			append("modRecord=").append(modRecord).append('\n')
			append("modSkip=").append(modSkip).append('\n')
			append("modReset=").append(modReset).append('\n')
			append("modDrop=").append(modDrop).append('\n')
			append("scannedHour=").append(scannedHour).append('\n')
			append("scanned=").append(scanned.joinToString(",")).append('\n')
			append("finished=").append(finished.joinToString(",")).append('\n')
			append("dutyIsland=").append(dutyIsland).append('\n')
			append("dutyGrotto=").append(dutyGrotto).append('\n')
		}
		Files.writeString(path, text, StandardCharsets.UTF_8)
	}
}
