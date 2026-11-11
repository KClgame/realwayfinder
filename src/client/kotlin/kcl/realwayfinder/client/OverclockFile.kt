package kcl.realwayfinder.client

import kcl.realwayfinder.nav.FishTarget
import kcl.realwayfinder.nav.GrottoKind
import kcl.realwayfinder.nav.HookPick
import kcl.realwayfinder.nav.MagnetPick
import kcl.realwayfinder.nav.RodPick
import kcl.realwayfinder.nav.UnstablePick
import kcl.realwayfinder.nav.UnstableRead
import kcl.realwayfinder.nav.nextUnstableUse
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class OcTriple(
	val hook: HookPick,
	val magnet: MagnetPick,
	val rod: RodPick,
	val unstable: UnstablePick = UnstablePick.OFF
) {
	fun kept(): Boolean = hook == HookPick.KEEP && magnet == MagnetPick.KEEP && rod == RodPick.KEEP
}

/** When the unstable overclock may be enabled again, from the system clock. */
object UnstableSchedule {
	var readyAtMs: Long = 0

	fun ready(now: Long = System.currentTimeMillis()): Boolean = now >= readyAtMs

	fun arm(now: Long, durationMs: Long, cooldownMs: Long) {
		readyAtMs = nextUnstableUse(now, durationMs, cooldownMs)
		OverclockFile.save()
	}

	fun holdUntil(at: Long) {
		if (at <= readyAtMs) return
		readyAtMs = at
		OverclockFile.save()
	}

	/** An item that is already running or cooling sets the next enable time without clicking. */
	fun absorb(read: UnstableRead, now: Long = System.currentTimeMillis()) {
		val activeLeft = read.activeLeftMs
		val cooldown = read.cooldownMs
		val cooldownLeft = read.cooldownLeftMs
		val until = when {
			activeLeft != null && cooldown != null -> now + activeLeft + cooldown
			activeLeft != null -> now + activeLeft
			cooldownLeft != null -> now + cooldownLeft
			else -> return
		}
		holdUntil(until)
	}
}

object OverclockFile {
	private val path = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("overclock.txt")
	private val fish = linkedMapOf<FishTarget, OcTriple>()
	private val grotto = linkedMapOf<GrottoKind, OcTriple>()

	fun fish(target: FishTarget): OcTriple = fish[target] ?: kept()

	fun grotto(kind: GrottoKind): OcTriple = grotto[kind] ?: kept()

	fun setFish(target: FishTarget, triple: OcTriple) {
		fish[target] = triple
		save()
	}

	fun setGrotto(kind: GrottoKind, triple: OcTriple) {
		grotto[kind] = triple
		save()
	}

	fun load() {
		fish.clear()
		grotto.clear()
		UnstableSchedule.readyAtMs = 0
		if (!Files.exists(path)) return
		val legacy = ArrayList<Triple<String, String, String>>()
		val explicit = HashSet<FishTarget>()
		for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
			val parts = line.split('=', limit = 2)
			if (parts.size != 2) continue
			val key = parts[0].trim().split('.')
			val saved = parts[1].trim()
			if (key.size == 2 && key[0] == "unstable" && key[1] == "readyAt") {
				UnstableSchedule.readyAtMs = saved.toLongOrNull() ?: UnstableSchedule.readyAtMs
				continue
			}
			if (key.size != 3) continue
			when (key[0]) {
				"fish" -> {
					val target = enumOr<FishTarget>(key[1], null) ?: continue
					explicit.add(target)
					fish[target] = writePart(fish(target), key[2], saved)
				}
				"grotto" -> {
					val kind = enumOr<GrottoKind>(key[1], null) ?: continue
					grotto[kind] = writePart(grotto(kind), key[2], saved)
				}
				"island" -> legacy.add(Triple(key[1], key[2], saved))
			}
		}
		for ((group, part, saved) in legacy) {
			for (target in legacyTargets(group)) {
				if (target in explicit) continue
				fish[target] = writePart(fish[target] ?: kept(), part, saved)
			}
		}
	}

	/** Older files stored one page for several targets. Each target now keeps its own copy. */
	private fun legacyTargets(group: String): List<FishTarget> = when (group.uppercase()) {
		"FISH" -> listOf(FishTarget.WISE, FishTarget.STRONG, FishTarget.ELUSIVE)
		"XP_WAYFINDER" -> listOf(FishTarget.XP, FishTarget.WAYFINDER)
		"PEARL" -> listOf(FishTarget.PEARL)
		"TREASURE" -> listOf(FishTarget.TREASURE)
		"SPIRIT" -> listOf(FishTarget.SPIRIT)
		else -> emptyList()
	}

	private fun writePart(current: OcTriple, part: String, raw: String): OcTriple = when (part) {
		"hook" -> current.copy(hook = enumOr(raw, HookPick.KEEP) ?: HookPick.KEEP)
		"magnet" -> current.copy(magnet = enumOr(raw, MagnetPick.KEEP) ?: MagnetPick.KEEP)
		"rod" -> current.copy(rod = enumOr(raw, RodPick.KEEP) ?: RodPick.KEEP)
		"unstable" -> current.copy(unstable = enumOr(raw, UnstablePick.OFF) ?: UnstablePick.OFF)
		"readyAt" -> current
		else -> current
	}

	fun save() {
		Files.createDirectories(path.parent)
		val text = buildString {
			for (target in FishTarget.entries) {
				val triple = fish(target)
				append("fish.").append(target.name).append(".hook=").append(triple.hook.name).append('\n')
				append("fish.").append(target.name).append(".magnet=").append(triple.magnet.name).append('\n')
				append("fish.").append(target.name).append(".rod=").append(triple.rod.name).append('\n')
				append("fish.").append(target.name).append(".unstable=").append(triple.unstable.name).append('\n')
			}
			for (kind in GrottoKind.entries) {
				val triple = grotto(kind)
				append("grotto.").append(kind.name).append(".hook=").append(triple.hook.name).append('\n')
				append("grotto.").append(kind.name).append(".magnet=").append(triple.magnet.name).append('\n')
				append("grotto.").append(kind.name).append(".rod=").append(triple.rod.name).append('\n')
				append("grotto.").append(kind.name).append(".unstable=").append(triple.unstable.name).append('\n')
			}
			append("unstable.readyAt=").append(UnstableSchedule.readyAtMs).append('\n')
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
