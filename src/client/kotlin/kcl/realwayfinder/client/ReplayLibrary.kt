package kcl.realwayfinder.client

import kcl.realwayfinder.nav.MapStart
import kcl.realwayfinder.nav.ScriptRole
import kcl.realwayfinder.nav.matchMapStart
import kcl.realwayfinder.nav.TickInput
import kcl.realwayfinder.nav.formatTape
import kcl.realwayfinder.nav.parseTape
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class MeetPoint(val x: Int, val y: Int, val z: Int)

/**
 * Named replays live in config/realwayfinder/replays.
 * The text format starts with a version line and ignores unknown trailing fields, so a newer jar can still read them.
 */
object ReplayLibrary {
	private val names = LinkedHashMap<String, String>()
	private val cache = HashMap<String, List<TickInput>>()
	private val bindings = HashMap<String, String>()
	private val meets = HashMap<String, MutableList<MapStart>>()

	fun load() {
		names.clear()
		cache.clear()
		bindings.clear()
		meets.clear()
		Files.createDirectories(dir())
		if (Files.exists(index())) {
			for (line in Files.readAllLines(index(), StandardCharsets.UTF_8)) {
				val trimmed = line.trim()
				if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
				val parts = trimmed.split('=', limit = 2)
				if (parts.size == 2) names[parts[0]] = parts[1]
			}
		}
		if (Files.exists(bindingsFile())) {
			for (line in Files.readAllLines(bindingsFile(), StandardCharsets.UTF_8)) {
				val trimmed = line.trim()
				if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
				val parts = trimmed.split('=', limit = 2)
				if (parts.size != 2) continue
				if (parts[0].endsWith(".meet")) {
					parseMeet(parts[1])?.let { start ->
						val place = parts[0].removeSuffix(".meet")
						meets.getOrPut(place) { mutableListOf() }.add(start)
					}
				} else {
					bindings[parts[0]] = parts[1]
				}
			}
		}
		importLegacy()
	}

	fun names(): List<String> = names.keys.toList()

	fun frames(name: String): List<TickInput>? {
		val file = names[name] ?: return null
		return cache.getOrPut(name) {
			val path = dir().resolve(file)
			if (!Files.exists(path)) emptyList() else parseTape(Files.readString(path, StandardCharsets.UTF_8))
		}.takeIf { it.isNotEmpty() }
	}

	fun save(name: String, frames: List<TickInput>) {
		val clean = name.trim()
		if (clean.isEmpty()) return
		val file = names[clean] ?: uniqueFile(clean)
		names[clean] = file
		val path = dir().resolve(file)
		Files.writeString(path, formatTape(frames), StandardCharsets.UTF_8)
		cache[clean] = frames.toList()
		saveIndex()
	}

	fun delete(name: String) {
		val file = names.remove(name) ?: return
		Files.deleteIfExists(dir().resolve(file))
		cache.remove(name)
		bindings.entries.removeIf { it.value == name }
		saveIndex()
		saveBindings()
	}

	fun binding(placeKey: String, role: ScriptRole): String? = bindings["$placeKey.${role.file}"]

	fun bind(placeKey: String, role: ScriptRole, name: String) {
		bindings["$placeKey.${role.file}"] = name
		saveBindings()
	}

	fun bindMapScan(placeKey: String, mapX: Int, mapZ: Int, name: String) {
		bindings["$placeKey.$mapX.$mapZ.scan"] = name
		saveBindings()
	}

	fun mapScan(placeKey: String, mapX: Int, mapZ: Int): String? = bindings["$placeKey.$mapX.$mapZ.scan"]

	fun meets(placeKey: String): List<MapStart> = meets[placeKey]?.toList() ?: emptyList()

	fun matchMeet(placeKey: String, x: Double, z: Double): MeetPoint? {
		val found = matchMapStart(meets(placeKey), x, z) ?: return null
		return MeetPoint(found.x, found.y, found.z)
	}

	fun addMeet(placeKey: String, start: MapStart) {
		meets.getOrPut(placeKey) { mutableListOf() }.add(start)
		saveBindings()
	}

	fun removeMeet(placeKey: String, index: Int) {
		val list = meets[placeKey] ?: return
		if (index !in list.indices) return
		list.removeAt(index)
		saveBindings()
	}

	private fun parseMeet(text: String): MapStart? {
		val halves = text.split('@')
		val start = halves[0].trim().split(' ')
		if (start.size != 3) return null
		val x = start[0].toIntOrNull() ?: return null
		val y = start[1].toIntOrNull() ?: return null
		val z = start[2].toIntOrNull() ?: return null
		if (halves.size == 1) return MapStart(x, z, x, y, z)
		val map = halves[1].trim().split(' ')
		if (map.size != 2) return MapStart(x, z, x, y, z)
		val mapX = map[0].toIntOrNull() ?: return null
		val mapZ = map[1].toIntOrNull() ?: return null
		return MapStart(mapX, mapZ, x, y, z)
	}

	private fun importLegacy() {
		val legacy = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("tapes")
		if (!Files.isDirectory(legacy)) return
		Files.list(legacy).use { stream ->
			stream.filter { it.toString().endsWith(".txt") }.forEach { path ->
				val id = path.fileName.toString().removeSuffix(".txt")
				if (id in names) return@forEach
				val frames = parseTape(Files.readString(path, StandardCharsets.UTF_8))
				if (frames.isEmpty()) return@forEach
				save(id, frames)
				val role = when {
					id.endsWith("-scan") -> ScriptRole.SCAN
					id.endsWith("-return") -> ScriptRole.RETURN
					id.endsWith("-enter") -> ScriptRole.ENTER
					else -> null
				}
				if (role != null) {
					val place = id.removeSuffix("-${role.file}")
					if (binding(place, role) == null) bind(place, role, id)
				}
			}
		}
	}

	private fun uniqueFile(name: String): String {
		val base = name.replace(Regex("[^\\w\\u4e00-\\u9fff._-]"), "_").ifEmpty { "replay" }
		var file = "$base.txt"
		var n = 2
		val used = names.values.toSet()
		while (file in used || Files.exists(dir().resolve(file))) {
			file = "$base-$n.txt"
			n++
		}
		return file
	}

	private fun saveIndex() {
		val text = buildString {
			append("# realwayfinder-replay-library 1\n")
			for ((name, file) in names) append(name.replace('\n', ' ')).append('=').append(file).append('\n')
		}
		Files.writeString(index(), text, StandardCharsets.UTF_8)
	}

	private fun saveBindings() {
		val text = buildString {
			append("# realwayfinder-bindings 1\n")
			for ((key, name) in bindings) append(key).append('=').append(name).append('\n')
			for ((place, points) in meets) {
				for (point in points) {
					append(place).append(".meet=")
					append(point.x).append(' ').append(point.y).append(' ').append(point.z)
					append(" @ ").append(point.mapX).append(' ').append(point.mapZ).append('\n')
				}
			}
		}
		Files.writeString(bindingsFile(), text, StandardCharsets.UTF_8)
	}

	private fun dir() = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("replays")
	private fun index() = dir().resolve("index.txt")
	private fun bindingsFile() = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("bindings.txt")
}
