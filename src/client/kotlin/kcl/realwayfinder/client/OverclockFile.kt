package kcl.realwayfinder.client

import kcl.realwayfinder.nav.GrottoKind
import kcl.realwayfinder.nav.HookPick
import kcl.realwayfinder.nav.IslandOc
import kcl.realwayfinder.nav.MagnetPick
import kcl.realwayfinder.nav.RodPick
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class OcTriple(val hook: HookPick, val magnet: MagnetPick, val rod: RodPick) {
	fun kept(): Boolean = hook == HookPick.KEEP && magnet == MagnetPick.KEEP && rod == RodPick.KEEP
}

object OverclockFile {
	private val path = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("overclock.txt")
	private val island = linkedMapOf<IslandOc, OcTriple>()
	private val grotto = linkedMapOf<GrottoKind, OcTriple>()

	fun island(kind: IslandOc): OcTriple = island[kind] ?: kept()

	fun grotto(kind: GrottoKind): OcTriple = grotto[kind] ?: kept()

	fun setIsland(kind: IslandOc, triple: OcTriple) {
		island[kind] = triple
		save()
	}

	fun setGrotto(kind: GrottoKind, triple: OcTriple) {
		grotto[kind] = triple
		save()
	}

	fun load() {
		island.clear()
		grotto.clear()
		if (!Files.exists(path)) return
		for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
			val parts = line.split('=', limit = 2)
			if (parts.size != 2) continue
			val key = parts[0].trim().split('.')
			if (key.size != 3) continue
			val saved = parts[1].trim()
			when (key[0]) {
				"island" -> {
					val kind = enumOr<IslandOc>(key[1], null) ?: continue
					island[kind] = writePart(island(kind), key[2], saved)
				}
				"grotto" -> {
					val kind = enumOr<GrottoKind>(key[1], null) ?: continue
					grotto[kind] = writePart(grotto(kind), key[2], saved)
				}
			}
		}
	}

	private fun writePart(current: OcTriple, part: String, raw: String): OcTriple = when (part) {
		"hook" -> current.copy(hook = enumOr(raw, HookPick.KEEP) ?: HookPick.KEEP)
		"magnet" -> current.copy(magnet = enumOr(raw, MagnetPick.KEEP) ?: MagnetPick.KEEP)
		"rod" -> current.copy(rod = enumOr(raw, RodPick.KEEP) ?: RodPick.KEEP)
		else -> current
	}

	fun save() {
		Files.createDirectories(path.parent)
		val text = buildString {
			for (kind in IslandOc.entries) {
				val triple = island(kind)
				append("island.").append(kind.name).append(".hook=").append(triple.hook.name).append('\n')
				append("island.").append(kind.name).append(".magnet=").append(triple.magnet.name).append('\n')
				append("island.").append(kind.name).append(".rod=").append(triple.rod.name).append('\n')
			}
			for (kind in GrottoKind.entries) {
				val triple = grotto(kind)
				append("grotto.").append(kind.name).append(".hook=").append(triple.hook.name).append('\n')
				append("grotto.").append(kind.name).append(".magnet=").append(triple.magnet.name).append('\n')
				append("grotto.").append(kind.name).append(".rod=").append(triple.rod.name).append('\n')
			}
		}
		Files.writeString(path, text, StandardCharsets.UTF_8)
	}

	private fun kept() = OcTriple(HookPick.KEEP, MagnetPick.KEEP, RodPick.KEEP)
}

private inline fun <reified T : Enum<T>> enumOr(raw: String, fallback: T?): T? =
	enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback

object OcCache {
	var key: String = ""
	var hook: String? = null
	var magnet: String? = null
	var rod: String? = null
	var valid: Boolean = false

	fun clear() {
		valid = false
	}

	fun store(key: String, hook: String?, magnet: String?, rod: String?) {
		this.key = key
		this.hook = hook
		this.magnet = magnet
		this.rod = rod
		valid = true
	}

	fun matches(key: String, hook: String?, magnet: String?, rod: String?): Boolean {
		if (!valid || this.key != key) return false
		if (hook != null && this.hook != hook) return false
		if (magnet != null && this.magnet != magnet) return false
		if (rod != null && this.rod != rod) return false
		return true
	}
}
