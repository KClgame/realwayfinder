package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.Realwayfinder
import kcl.realwayfinder.RouteLock
import kcl.realwayfinder.nav.BuiltInRoutes
import kcl.realwayfinder.nav.IslandJob
import kcl.realwayfinder.nav.pickIsland
import kcl.realwayfinder.nav.CastSolution
import kcl.realwayfinder.nav.Mode
import kcl.realwayfinder.nav.Node
import kcl.realwayfinder.nav.Route
import kcl.realwayfinder.nav.RouteMemory
import kcl.realwayfinder.nav.ScanPoint
import kcl.realwayfinder.nav.ScriptRole
import kcl.realwayfinder.nav.TickInput
import kcl.realwayfinder.nav.stuckCells
import kcl.spotfilter.client.Hotkeys
import kcl.spotfilter.client.data.SpotKind
import kcl.realwayfinder.nav.grottoClosed
import kcl.realwayfinder.nav.hourlyClear

import kcl.spotfilter.client.data.FishingPlace
import kcl.spotfilter.client.data.StockLevel
import kcl.spotfilter.client.data.FishingWorld
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.floor

object RouteRuntime {
	@Volatile
	var plan: PlannedRoute? = null
	private var tick = 0
	private var stillTicks = 0
	private var lastX = Double.NaN
	private var lastZ = Double.NaN
	private var revision = RouteSettings.revision
	private var phase = Phase.SEEK
	private var lastClearMs = 0L
	private val dock = CastDock()
	private val runner = PathRunner()
	private val tapePlayer = TapePlayer()
	private enum class Phase { SEEK, SCRIPT, WAIT_FISH, WAIT_GROTTO, RING, HOME, COVER }
	private var script: List<TickInput>? = null
	private var scriptRole: ScriptRole? = null
	private var fishDelay = 0
	private var pendingGrotto: FishingPlace? = null
	private var ring: List<ScanPoint> = emptyList()
	private var ringIndex = 0
	private var ringRoute: Route? = null
	private var ringGrotto = false
	private var poseX = Double.NaN
	private var poseY = Double.NaN
	private var poseZ = Double.NaN
	private var poseTick = 0
	private var savedGoal = ""
	private var fishPending = false
	private var replanDelay = 0
	private var arrivalKey = ""
	private var scanAfterHome = false
	private var homeThenFish = false
	private var skipHome = false
	private var pendingPlace: FishingPlace? = null
	/** After this island's return-to-spawn replay, start its scan without returning again. */
	private var scanAfterReturnId: String? = null
	private var homeTarget: ScanPoint? = null
	private var homeRoute: Route? = null
	private var grottoScan: GrottoScan? = null
	/** Scan progress kept when M turns the mod off mid-scan. */
	private var heldScan: HeldScan? = null
	private var pausedCover = false

	private data class HeldScan(val points: List<ScanPoint>, val index: Int, val grotto: Boolean)
	val avoidCasts = HashSet<String>()
	/** Columns where a cast failed. The player has to leave that block before a new stance is used. */
	val failedColumns = HashSet<String>()
	/** Throws already tried from a stance. The first miss adds the angle; the next miss leaves the cell. */
	val triedAngles = HashMap<String, MutableList<Pair<Float, Float>>>()
	val stanceMisses = HashMap<String, Int>()
	/** Stance cells skipped for one fishing spot, until that spot is depleted. */
	val droppedStances = HashMap<String, MutableSet<String>>()
	val skippedSpots = HashSet<String>()
	private val biteFailures = HashMap<String, Int>()
	private var biteSpot = ""
	private var biteTicks = 0
	private var biteCaught = false

	fun tick() {
		val client = Minecraft.getInstance()
		val player = client.player
		val level = client.level
		if (player != null && ScanRecord.recording) {
			ScanRecord.capture(client, player)
			BaritoneNav.stop()
			runner.release(client)
			tapePlayer.release(client)
			return
		}
		if (player == null || level == null) {
			plan = null
				stillTicks = 0
			phase = Phase.SEEK
			dock.clear()
			runner.release(client)
			tapePlayer.release(client)
			return
		}
		if (phase == Phase.SCRIPT) {
			BaritoneNav.stop()
			if (adaptScanIsland(player)) return
			tickScript(client, player)
			return
		}
		if (phase == Phase.HOME) {
			tickHome(client, player, level)
			return
		}
		if (phase == Phase.COVER) {
			tickCover()
			return
		}
		if (phase == Phase.RING) {
			if (adaptScanIsland(player)) return
			tickRing(client, player, level)
			return
		}
		if (phase == Phase.WAIT_GROTTO) {
			val here = FishingWorld.current
			val want = pendingGrotto
			if (here != null && here == want) beginGrottoRoute(player)
			return
		}
		if (phase == Phase.WAIT_FISH) {
			fishDelay--
			if (fishDelay <= 0) {
				RouteSettings.enabled = true
				RouteSettings.save()
				phase = Phase.SEEK
				player.sendSystemMessage(Component.literal("Realwayfinder 开始寻路"))
			}
			return
		}
		if (RouteSettings.enabled) followIslandCycle(FishingWorld.current)
		if (phase != Phase.SEEK) return
		if (!RouteSettings.enabled) {
			plan = null
				stillTicks = 0
			dock.clear()
			runner.release(client)
			return
		}
		tick++
		if (movedHorizontally(lastX, lastZ, player.x, player.z)) {
			stillTicks = 0
			lastX = player.x
			lastZ = player.z
		} else {
			stillTicks++
		}
		val place = FishingWorld.current
		val paused = false
		var blocked = false
		val current = plan
		val onGoal = atCast(player, current)
		val walking = BaritoneNav.busy()
		if (onGoal && current != null) rememberSuccess(current.spotX, current.spotZ)
		if (!walking && !onGoal && !dock.active && !player.isInWater && !turningInPlace(player, current?.route) && noProgress(player, 0.5)) {
			rememberFailure(current?.spotX ?: 0, current?.spotZ ?: 0, current?.route, player)
			fishPending = true
			RoutePlanner.cancel()
			runner.release(client)
			stillTicks = 0
		}
		if (phase == Phase.SEEK && place != null && !paused && current != null && walking && !BaritoneNav.calculating() && stillTicks >= RouteLock.STUCK_TICKS && !onGoal && !dock.active) {
			BaritoneNav.stop()
			fishPending = true
			stillTicks = 0
		} else if (phase == Phase.SEEK && place != null && !paused && current != null && !walking) {
			blocked = runner.takeFailed()
			val stuck = (blocked || stillTicks >= RouteLock.STUCK_TICKS) && !onGoal && !dock.active
			if (stuck) {
				rememberFailure(current.spotX, current.spotZ, current.route, player)
				fishPending = true
				stillTicks = 0
				runner.release(client)
			}
		}
		val settingsChanged = RouteSettings.revision != revision
		val chosen = plan
		val replace = RouteLock.shouldReplace(
			lockedComplete = chosen?.route?.complete == true,
			spotValid = chosen?.spotStillThere() == true,
			biasChanged = settingsChanged,
			stillTicks = stillTicks,
			onGoal = onGoal,
			docking = dock.active
		)
		val allowed = place != null && RouteSettings.accepts(place)
		if (!allowed) {
			plan = emptyDenied(place)
			dock.clear()
			runner.release(client)
			fishPending = false
			RoutePlanner.cancel()
		} else if (chosen == null || replace || settingsChanged) {
			if (replace || settingsChanged) {
				dock.clear()
						stillTicks = 0
				avoidCasts.clear()
				failedColumns.clear()
			}
			revision = RouteSettings.revision
			fishPending = true
		} else if (chosen.route == null && chosen.status == "靠近鱼点" && chosen.spotLabel.isNotEmpty() && (chosen.stand == null || reachedStand(player, chosen) || spotChunkLoaded(level, chosen))) {
			if (replanDelay > 0) replanDelay-- else fishPending = true
		} else if (chosen.route != null && advanceRoute(player, chosen)) {
			if (replanDelay > 0) replanDelay-- else fishPending = true
		} else if (chosen.route?.complete != true && !onGoal && chosen.route != null) {
			val end = chosen.route.nodes.lastOrNull()
			if (end != null && reachedCell(player, end.x, end.y, end.z)) fishPending = true
		}
		releaseDepletedStances()
		val miss = dock.takeMiss()
		if (miss != null) {
			val spotKey = if (chosen == null) "" else "${chosen.spotX},${chosen.spotY},${chosen.spotZ}"
			val stanceKey = castKey(miss)
			val angle = chosen?.cast
			if (angle != null && angle.stance.x == miss.x && angle.stance.z == miss.z) {
				triedAngles.getOrPut(stanceKey) { mutableListOf() }.add(angle.yaw to angle.pitch)
			}
			val misses = (stanceMisses[stanceKey] ?: 0) + 1
			stanceMisses[stanceKey] = misses
			dock.clear()
			if (misses <= 1 && chosen != null) {
				plan = chosen.copy(cast = null)
				player.sendSystemMessage(Component.literal("Realwayfinder 重新寻找角度"))
			} else {
				dropStance(spotKey, stanceKey)
				arrivalKey = ""
				plan = chosen?.copy(cast = null, casts = emptyList(), stand = null, status = "靠近鱼点")
				fishPending = true
				player.sendSystemMessage(Component.literal("Realwayfinder 换站位"))
			}
		}
		if (fishPending) {
			val next = RoutePlanner.chooseFish(level, player)
			fishPending = false
			val previous = plan
			plan = next
			if (next.cast == null && next.spotLabel.isNotEmpty()) replanDelay = 20
			val changed = previous?.spotX != next.spotX || previous.spotY != next.spotY || previous.spotZ != next.spotZ || previous.cast?.stance != next.cast?.stance || previous.stand != next.stand
			if (previous?.stand != next.stand) arrivalKey = ""
			if (changed) BaritoneNav.stop()
			runner.release(client)
			stillTicks = 0
		}
		if (phase == Phase.SEEK && runner.takeRepeat()) returnHome(thenScan = false)
		noteArrival(player, level)
		val goal = atCast(player, plan)
		val cast = castHere(player, plan)
		val fishing = goal && cast != null
		val fishingPlan = plan
		val arrived = fishingPlan?.status == "完整" && fishingPlan.stand != null && reachedStand(player, fishingPlan)
		if (fishing || arrived || paused || dock.active || fishingPlan == null || fishingPlan.spotLabel == "扫点" || fishingPlan.spotLabel == "回出生点") {
			BaritoneNav.stop()
			runner.release(client)
		} else {
			val dest = fishingPlan.stand ?: fishingPlan.cast?.stance
			if (dest != null) BaritoneNav.go(dest.x, dest.y, dest.z, report = true)
			else BaritoneNav.stop()
			runner.release(client)
		}
		val live = plan
		if (dock.active && live != null && live.spotLabel.isNotEmpty() && live.spotLabel != "扫点" && live.spotLabel != "回出生点") {
			val aimed = RoutePlanner.aimAtSpot(level, player, live)
			if (aimed != null) plan = live.copy(cast = aimed, casts = listOf(aimed), waterX = aimed.landX, waterZ = aimed.landZ)
		}
		val throwCast = castHere(player, plan)
		dock.tick(client, player, goal || throwCast != null, throwCast ?: cast, paused, plan?.waterX ?: Double.NaN, plan?.waterZ ?: Double.NaN)
		watchBite(player, goal, plan)
	}

	private fun watchBite(player: net.minecraft.world.entity.player.Player, onGoal: Boolean, current: PlannedRoute?) {
		if (!onGoal || current == null || current.spotLabel.isEmpty()) {
			biteSpot = ""
			biteTicks = 0
			biteCaught = false
			return
		}
		val id = "${current.spotX},${current.spotY},${current.spotZ}"
		if (biteSpot != id) {
			biteSpot = id
			biteTicks = 0
			biteCaught = false
		}
		val result = kcl.realwayfinder.nav.biteResult(biteFailures[id] ?: 0, biteTicks, biteCaught)
		biteTicks++
		when (result) {
			kcl.realwayfinder.nav.BiteResult.WAIT -> Unit
			kcl.realwayfinder.nav.BiteResult.HOME -> {
				biteTicks = 0
				biteFailures[id] = 1
				player.sendSystemMessage(Component.literal("Realwayfinder 30秒没钓到，回出生点"))
				returnHome(thenScan = false)
			}
			kcl.realwayfinder.nav.BiteResult.SKIP -> {
				skippedSpots.add(id)
				biteFailures.remove(id)
				biteSpot = ""
				biteTicks = 0
				dock.clear()
				fishPending = true
				RoutePlanner.cancel()
				player.sendSystemMessage(Component.literal("Realwayfinder 这个点没钓到，换下一个"))
			}
		}
	}

	private fun noteCatch() {
		if (biteSpot.isEmpty()) return
		biteCaught = true
		biteFailures.remove(biteSpot)
	}

	fun preview(player: net.minecraft.world.entity.player.Player, tape: List<TickInput>, name: String) {
		script = tape
		scriptRole = null
		phase = Phase.SCRIPT
		tapePlayer.reset()
		runner.release(Minecraft.getInstance())
		dock.clear()
		player.sendSystemMessage(Component.literal("Realwayfinder 回放开始 $name"))
	}

	fun toggleScan() {
		if (phase == Phase.RING || phase == Phase.HOME || phase == Phase.COVER || scanAfterHome) {
			stopScan()
			return
		}
		scanThisIsland()
	}

	private fun stopScan() {
		ring = emptyList()
		ringRoute = null
		heldScan = null
		pausedCover = false
		scanAfterHome = false
		skipHome = false
		pendingPlace = null
		homeTarget = null
		homeRoute = null
		grottoScan = null
		script = null
		scriptRole = null
		tapePlayer.reset()
		if (plan?.spotLabel == "扫点" || plan?.spotLabel == "回出生点") plan = null
		phase = Phase.SEEK
		RoutePlanner.cancel()
		runner.release(Minecraft.getInstance())
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 扫点中断"))
	}

	fun skipScanPoint() {
		if (phase != Phase.RING) return
		ringIndex++
		ringRoute = null
		RoutePlanner.cancel()
		runner.release(Minecraft.getInstance())
		val player = Minecraft.getInstance().player ?: return
		if (ringIndex >= ring.size) {
			finishRing(player)
			return
		}
		showScan(null, ring[ringIndex])
		player.sendSystemMessage(Component.literal("Realwayfinder 扫点 ${ringIndex + 1}/${ring.size}"))
	}

	fun scanThisIsland() {
		val place = FishingWorld.current
		val player = Minecraft.getInstance().player
		if (place == null) {
			player?.sendSystemMessage(Component.literal("不在钓鱼岛"))
			return
		}
		if (!beginScript(place, ScriptRole.SCAN)) {
			player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${place.shortId} 的扫点录像"))
		}
	}

	fun discoverGrotto(grotto: FishingPlace) {
		pendingGrotto = grotto
		if (scriptRole == ScriptRole.ENTER && phase == Phase.SCRIPT) return
		if (FishingWorld.current == grotto) {
			val who = Minecraft.getInstance().player
			if (who != null) beginGrottoRoute(who) else startGrottoFish()
			return
		}
		if (!beginScript(grotto, ScriptRole.ENTER)) {
			phase = Phase.WAIT_GROTTO
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${grotto.shortId} 的进入录像，等待进入"))
		}
	}

	fun onChat(text: String) {
		GrottoEntry.onChat(text)
		if (grottoClosed(text)) {
			RouteSettings.resetGrottoScanned()
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder Grotto 扫点已重置"))
		}
		if (missedFishingSpot(text)) rejectCast()
		if (text.contains("You caught", ignoreCase = true)) noteCatch()
		if (!hourlyClear(text)) return
		val now = System.currentTimeMillis()
		if (now - lastClearMs < 2000) return
		lastClearMs = now
		startIslandHour()
	}

	private fun startIslandHour() {
		RouteSettings.resetScanned()
		skippedSpots.clear()
		biteFailures.clear()
		IslandCycle.resetToTop()
		val duty = IslandCycle.duty
		val place = FishingWorld.current
		val player = Minecraft.getInstance().player
		if (duty == null) {
			if (place != null) beginScript(place, ScriptRole.SCAN)
			return
		}
		if (place != null && IslandCycle.matches(place)) {
			IslandCycle.armedScan = duty
			beginScript(place, ScriptRole.SCAN)
		} else {
			IslandCycle.waiting = true
			IslandCycle.job = kcl.realwayfinder.nav.IslandJob.SCAN
			IslandCycle.returnedFor = duty
			if (!playIslandReturnThenScan(duty)) {
				val msg = "前往 $duty 扫点"
				if (IslandCycle.say(msg)) player?.sendSystemMessage(Component.literal("Realwayfinder $msg"))
				player?.sendSystemMessage(Component.literal("Realwayfinder 没有 $duty 的回出生点"))
			}
		}
	}

	private fun followIslandCycle(place: FishingPlace?) {
		if (place == null || place.kind == SpotKind.GROTTO) return
		if (phase != Phase.SEEK) return
		refreshFinished()
		val order = IslandCycle.enabledOrder()
		val pick = pickIsland(order, RouteSettings.scanned, RouteSettings.finished)
		if (pick == null) {
			IslandCycle.duty = null
			IslandCycle.waiting = false
			IslandCycle.armedScan = null
			if (order.isNotEmpty() && IslandCycle.say("启用岛屿都已钓完")) {
				Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 启用岛屿都已钓完"))
			}
			return
		}
		val arrived = place.shortId.equals(pick.id, ignoreCase = true)
		if (IslandCycle.duty != pick.id || IslandCycle.job != pick.job) {
			if (IslandCycle.duty != pick.id) IslandCycle.returnedFor = null
			IslandCycle.duty = pick.id
			IslandCycle.job = pick.job
			IslandCycle.waiting = !arrived
			if (pick.job != IslandJob.SCAN) IslandCycle.armedScan = null
		}
		if (!arrived) {
			if (pick.job == IslandJob.SCAN && IslandCycle.returnedFor != pick.id) {
				IslandCycle.returnedFor = pick.id
				if (playIslandReturnThenScan(pick.id)) return
				val player = Minecraft.getInstance().player
				val msg = "前往 ${pick.id} 扫点"
				if (IslandCycle.say(msg)) player?.sendSystemMessage(Component.literal("Realwayfinder $msg"))
				player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${pick.id} 的回出生点"))
			} else if (pick.job == IslandJob.FISH) {
				val msg = "前往 ${pick.id} 钓鱼"
				if (IslandCycle.say(msg)) {
					Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder $msg"))
				}
			}
			IslandCycle.waiting = true
			return
		}
		IslandCycle.waiting = false
		if (pick.job == IslandJob.SCAN && IslandCycle.armedScan != pick.id) {
			IslandCycle.armedScan = pick.id
			if (!beginScript(place, ScriptRole.SCAN)) {
				Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${place.shortId} 的扫点"))
			}
		}
	}

	/** A scanned island with no qualifying spot left is finished and the cycle moves on. */
	private fun refreshFinished() {
		for (id in RouteSettings.scanned.toList()) {
			when (IslandCycle.remaining(id, skippedSpots)) {
				true -> RouteSettings.clearFinished(id)
				false -> RouteSettings.markFinished(id)
				null -> Unit
			}
		}
	}

	private fun dropStance(spotKey: String, stanceKey: String) {
		droppedStances.getOrPut(spotKey) { HashSet() }.add(stanceKey)
		stanceMisses.remove(stanceKey)
		triedAngles.remove(stanceKey)
	}

	private fun releaseDepletedStances() {
		if (droppedStances.isEmpty()) return
		val pool = kcl.spotfilter.client.data.SpotPool.all()
		val gone = droppedStances.keys.filter { key ->
			val parts = key.split(",")
			if (parts.size != 3) return@filter false
			val x = parts[0].toIntOrNull() ?: return@filter false
			val y = parts[1].toIntOrNull() ?: return@filter false
			val z = parts[2].toIntOrNull() ?: return@filter false
			pool.any { it.x == x && it.y == y && it.z == z && it.stock == StockLevel.DEPLETED }
		}
		for (key in gone) {
			droppedStances.remove(key)?.forEach {
				stanceMisses.remove(it)
				triedAngles.remove(it)
			}
		}
	}

	private fun rejectCast() {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val level = client.level ?: return
		val current = plan ?: return
		val failed = castHere(player, current) ?: current.cast ?: return
		avoidCasts.add(castKey(failed.stance))
		failedColumns.add("${failed.stance.x},${failed.stance.z}")
		dock.clear()
		fishPending = true
		RoutePlanner.cancel()
		plan = current.copy(cast = null, casts = emptyList())
		runner.release(client)
		player.sendSystemMessage(Component.literal("Realwayfinder 重新抛竿"))
	}

	/** Play that island's return-to-spawn replay, then scan it. The place key is I1–I9. */
	private fun playIslandReturnThenScan(islandId: String): Boolean {
		val id = islandId.uppercase()
		val tape = ScanRecord.tape(id, ScriptRole.RETURN) ?: return false
		val player = Minecraft.getInstance().player ?: return false
		script = tape
		scriptRole = ScriptRole.RETURN
		scanAfterReturnId = id
		scanAfterHome = false
		homeThenFish = false
		pendingPlace = null
		tapePlayer.reset()
		runner.release(Minecraft.getInstance())
		dock.clear()
		plan = null
		stillTicks = 0
		phase = Phase.SCRIPT
		player.sendSystemMessage(Component.literal("Realwayfinder 前往 $id 扫点，先回出生点"))
		return true
	}

	private fun startIslandScan(islandId: String) {
		val id = islandId.uppercase()
		IslandCycle.duty = id
		IslandCycle.job = IslandJob.SCAN
		IslandCycle.waiting = false
		IslandCycle.armedScan = id
		val builtIn = BuiltInRoutes.island(id)
		if (builtIn != null) {
			startRing(builtIn, grotto = false)
			return
		}
		val tape = ScanRecord.tape(id, ScriptRole.SCAN)
		val player = Minecraft.getInstance().player
		if (tape == null) {
			phase = Phase.SEEK
			player?.sendSystemMessage(Component.literal("Realwayfinder 没有 $id 的扫点"))
			return
		}
		script = tape
		scriptRole = ScriptRole.SCAN
		tapePlayer.reset()
		runner.release(Minecraft.getInstance())
		dock.clear()
		plan = null
		stillTicks = 0
		phase = Phase.SCRIPT
		player?.sendSystemMessage(Component.literal("Realwayfinder 扫点回放开始"))
	}

	private fun beginScript(place: FishingPlace, role: ScriptRole): Boolean {
		if (role == ScriptRole.SCAN && !skipHome && queueHome(place)) return true
		skipHome = false
		if (role == ScriptRole.SCAN && place.kind == SpotKind.GROTTO) {
			val who = Minecraft.getInstance().player
			if (who != null) beginGrottoRoute(who) else startGrottoFish()
			return phase == Phase.RING || phase == Phase.SEEK
		}
		val builtIn = BuiltInRoutes.island(place.shortId)
		if (role == ScriptRole.SCAN && builtIn != null) {
			startRing(builtIn, grotto = false)
			return true
		}
		val tape = ScanRecord.tape(place, role) ?: return false
		script = tape
		scriptRole = role
		tapePlayer.reset()
		runner.release(Minecraft.getInstance())
		dock.clear()
		plan = null
		stillTicks = 0
		phase = Phase.SCRIPT
		if (role == ScriptRole.SCAN) {
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 扫点回放开始"))
		} else if (role == ScriptRole.ENTER) {
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 进入回放开始"))
		}
		return true
	}

	private fun returnHome(thenScan: Boolean) {
		val place = FishingWorld.current ?: return
		if (phase == Phase.HOME || (phase == Phase.SCRIPT && scriptRole == ScriptRole.RETURN)) return
		runner.clearRepeat()
		if (!queueHome(place, thenScan)) {
			phase = Phase.SEEK
		}
	}

	private fun queueHome(place: FishingPlace, thenScan: Boolean = true): Boolean {
		pendingPlace = place
		homeThenFish = !thenScan
		val tape = ScanRecord.tape(place, ScriptRole.RETURN)
		if (tape != null) {
			script = tape
			scriptRole = ScriptRole.RETURN
			scanAfterHome = thenScan
			tapePlayer.reset()
			runner.release(Minecraft.getInstance())
			dock.clear()
			plan = null
			stillTicks = 0
			phase = Phase.SCRIPT
			val why = if (thenScan) "Realwayfinder 回出生点" else "Realwayfinder 操作重复，回出生点"
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal(why))
			return true
		}
		val player = Minecraft.getInstance().player ?: return false
		val spawn = homePoint(place, player) ?: return false
		scanAfterHome = thenScan
		homeTarget = spawn
		homeRoute = null
		phase = Phase.HOME
		runner.release(Minecraft.getInstance())
		dock.clear()
		plan = PlannedRoute("回出生点", "", null, null, "回出生点", spawn.x, spawn.y, spawn.z)
		stillTicks = 0
		val why = if (thenScan) "Realwayfinder 回出生点" else "Realwayfinder 操作重复，回出生点"
		player.sendSystemMessage(Component.literal(why))
		return true
	}

	private fun homePoint(place: FishingPlace, player: net.minecraft.world.entity.player.Player): ScanPoint? {
		if (place.kind == SpotKind.GROTTO) return BuiltInRoutes.matchGrotto(player.x, player.z)?.spawn
		return BuiltInRoutes.islandSpawns[place.shortId.uppercase()]
	}

	private fun tickHome(
		client: Minecraft,
		player: net.minecraft.world.entity.player.Player,
		level: net.minecraft.client.multiplayer.ClientLevel
	) {
		val target = homeTarget ?: run {
			finishHome(player)
			return
		}
		tick++
		plan = PlannedRoute("回出生点", "", null, null, "回出生点", target.x, target.y, target.z)
		BaritoneNav.go(target.x, target.y, target.z, 1)
		runner.release(client)
		if (reachedCell(player, target.x, target.y, target.z)) {
			BaritoneNav.stop()
			finishHome(player)
		}
	}

	private fun finishHome(player: net.minecraft.world.entity.player.Player) {
		homeTarget = null
		homeRoute = null
		scanAfterHome = false
		runner.release(Minecraft.getInstance())
		player.sendSystemMessage(Component.literal("Realwayfinder 回出生点结束"))
		runner.clearRepeat()
		if (homeThenFish) {
			homeThenFish = false
			pendingPlace = null
			plan = null
			fishPending = true
			RouteSettings.enabled = true
			RouteSettings.save()
			phase = Phase.SEEK
			return
		}
		val place = pendingPlace
		if (place != null) {
			skipHome = true
			beginScript(place, ScriptRole.SCAN)
		} else {
			phase = Phase.SEEK
		}
	}

	private fun beginGrottoRoute(player: net.minecraft.world.entity.player.Player) {
		val here = pendingGrotto ?: FishingWorld.current
		val matched = BuiltInRoutes.matchGrotto(player.x, player.z)
		val map = if (here == FishingPlace.GROTTO_TROPICAL && matched?.name?.startsWith("grotto_temperate") == true) null else matched
		val px = floor(player.x).toInt()
		val pz = floor(player.z).toInt()
		val py = floor(player.y).toInt()
		val spawn = map?.spawn ?: ScanPoint(px, py, pz)
		val reach = entityLoadBlocks()
		val boundMinX = map?.minX
		val boundMaxX = map?.maxX
		val boundMinZ = map?.minZ
		val boundMaxZ = map?.maxZ
		val minX = if (boundMinX != null) maxOf(px - reach, boundMinX) else px - reach
		val maxX = if (boundMaxX != null) minOf(px + reach, boundMaxX) else px + reach
		val minZ = if (boundMinZ != null) maxOf(pz - reach, boundMinZ) else pz - reach
		val maxZ = if (boundMaxZ != null) minOf(pz + reach, boundMaxZ) else pz + reach
		val level = levelOf(player)
		val minY = (spawn.y - 24).coerceAtLeast(level.minY)
		val maxY = (spawn.y + 20).coerceAtMost(level.maxY - 1)
		grottoScan = GrottoScan(level, minX, maxX, minZ, maxZ, minY, maxY, spawn)
		phase = Phase.COVER
		player.sendSystemMessage(Component.literal("规划扫点"))
	}

	/** Horizontal reach of loaded entities. The scan reads through walls inside this square. */
	private fun entityLoadBlocks(): Int {
		val chunks = Minecraft.getInstance().options.simulationDistance().get()
		return (chunks * 16).coerceIn(48, 384)
	}

	private fun levelOf(player: net.minecraft.world.entity.player.Player) =
		player.level() as net.minecraft.client.multiplayer.ClientLevel

	private fun tickCover() {
		val job = grottoScan ?: run {
			phase = Phase.SEEK
			return
		}
		job.step(400)
		val points = job.ready() ?: return
		grottoScan = null
		startRing(points, grotto = true)
	}

	private fun startRing(points: List<ScanPoint>, grotto: Boolean) {
		ring = points
		ringIndex = 0
		ringRoute = null
		ringGrotto = grotto
		phase = Phase.RING
		runner.release(Minecraft.getInstance())
		dock.clear()
		showScan(null, points.first())
		stillTicks = 0
		val player = Minecraft.getInstance().player ?: return
		player.sendSystemMessage(Component.literal("Realwayfinder 扫点 1/${points.size}"))
	}

	private fun showScan(route: Route?, target: ScanPoint) {
		plan = PlannedRoute("扫点", "${ringIndex + 1}/${ring.size}", route, null, "扫点", target.x, target.y, target.z)
	}

	/** The player changed islands while a scan was running. Follow the island they are on. */
	private fun adaptScanIsland(player: net.minecraft.world.entity.player.Player): Boolean {
		if (ringGrotto || (phase == Phase.SCRIPT && scriptRole != ScriptRole.SCAN)) return false
		val here = FishingWorld.current ?: return false
		if (here.kind == SpotKind.GROTTO) return false
		val id = here.shortId.uppercase()
		val scanning = (IslandCycle.duty ?: IslandCycle.armedScan)?.uppercase() ?: return false
		if (id == scanning) return false
		BaritoneNav.stop()
		ring = emptyList()
		ringRoute = null
		script = null
		scriptRole = null
		tapePlayer.reset()
		runner.release(Minecraft.getInstance())
		player.sendSystemMessage(Component.literal("Realwayfinder 扫点改到 $id"))
		if (id in RouteSettings.scanned) {
			phase = Phase.SEEK
			IslandCycle.duty = id
			IslandCycle.job = IslandJob.FISH
			IslandCycle.waiting = false
			IslandCycle.armedScan = null
			IslandCycle.returnedFor = null
			return true
		}
		startIslandScan(id)
		return true
	}

	private fun tickRing(
		client: Minecraft,
		player: net.minecraft.world.entity.player.Player,
		level: net.minecraft.client.multiplayer.ClientLevel
	) {
		val target = ring.getOrNull(ringIndex)
		if (target == null) {
			finishRing(player)
			return
		}
		tick++
		if (reachedScan(player, target)) {
			rememberSuccess(target.x, target.z)
			BaritoneNav.stop()
			ringRoute = null
			ringIndex++
			if (ringIndex >= ring.size) {
				finishRing(player)
				return
			}
			showScan(null, ring[ringIndex])
			player.sendSystemMessage(Component.literal("Realwayfinder 扫点 ${ringIndex + 1}/${ring.size}"))
			return
		}
		showScan(null, target)
		BaritoneNav.go(target.x, target.y, target.z, 2)
		runner.release(client)
	}

	private fun finishRing(player: net.minecraft.world.entity.player.Player) {
		ring = emptyList()
		ringRoute = null
		heldScan = null
		player.sendSystemMessage(Component.literal("Realwayfinder 扫点结束"))
		FishingWorld.current?.shortId?.let { RouteSettings.markScanned(it) }
		val place = FishingWorld.current
		if (place != null && queueHome(place, thenScan = false)) return
		if (ringGrotto) {
			startGrottoFish()
		} else {
			fishDelay = 60
			phase = Phase.WAIT_FISH
		}
	}

	private fun startGrottoFish() {
		pendingGrotto = null
		script = null
		scriptRole = null
		tapePlayer.reset()
		val client = Minecraft.getInstance()
		runner.release(client)
		dock.clear()
		plan = null
		stillTicks = 0
		lastX = Double.NaN
		lastZ = Double.NaN
		RouteSettings.enabled = true
		RouteSettings.save()
		phase = Phase.SEEK
		client.player?.sendSystemMessage(Component.literal("Realwayfinder 开始寻路"))
	}

	fun halt() {
		phase = Phase.SEEK
		ring = emptyList()
		ringRoute = null
		fishPending = false
		plan = null
		stillTicks = 0
		dock.clear()
		RoutePlanner.cancel()
		BaritoneNav.stop()
		runner.release(Minecraft.getInstance())
	}

	/** M. Mid-scan: turn the mod off and remember the point. Off with a saved scan: resume it. Otherwise reset and start pathfinding. */
	fun resetRoute() {
		val client = Minecraft.getInstance()
		val player = client.player
		if (phase == Phase.RING && ring.isNotEmpty()) {
			heldScan = HeldScan(ring.toList(), ringIndex.coerceIn(0, ring.lastIndex), ringGrotto)
			pauseMod(client)
			player?.sendSystemMessage(Component.literal("Realwayfinder 已关闭，扫点 ${ringIndex + 1}/${ring.size} 已记下"))
			return
		}
		if (phase == Phase.COVER && grottoScan != null) {
			pausedCover = true
			pauseMod(client)
			player?.sendSystemMessage(Component.literal("Realwayfinder 已关闭，扫点规划已记下"))
			return
		}
		if (!RouteSettings.enabled && heldScan != null) {
			val saved = heldScan ?: return
			heldScan = null
			RouteSettings.enabled = true
			RouteSettings.save()
			ring = saved.points
			ringIndex = saved.index.coerceIn(0, saved.points.lastIndex)
			ringGrotto = saved.grotto
			ringRoute = null
			phase = Phase.RING
			runner.release(client)
			showScan(null, ring[ringIndex])
			player?.sendSystemMessage(Component.literal("Realwayfinder 继续扫点 ${ringIndex + 1}/${ring.size}"))
			return
		}
		if (!RouteSettings.enabled && pausedCover && grottoScan != null) {
			pausedCover = false
			RouteSettings.enabled = true
			RouteSettings.save()
			phase = Phase.COVER
			player?.sendSystemMessage(Component.literal("Realwayfinder 继续规划扫点"))
			return
		}
		RouteSettings.enabled = true
		RouteSettings.save()
		avoidCasts.clear()
		failedColumns.clear()
		dock.clear()
		fishPending = true
		plan = null
		stillTicks = 0
		lastX = Double.NaN
		lastZ = Double.NaN
		poseX = Double.NaN
		poseTick = 0
		RoutePlanner.cancel()
		runner.release(client)
		if (phase == Phase.RING || phase == Phase.HOME || phase == Phase.COVER) {
			ring = emptyList()
			ringRoute = null
			grottoScan = null
			heldScan = null
			pausedCover = false
			phase = Phase.SEEK
		} else if (phase == Phase.SEEK || phase == Phase.WAIT_FISH) {
			phase = Phase.SEEK
		}
		player?.sendSystemMessage(Component.literal("Realwayfinder 重置寻路"))
	}

	private fun pauseMod(client: Minecraft) {
		RouteSettings.enabled = false
		RouteSettings.save()
		dock.clear()
		fishPending = false
		plan = null
		ringRoute = null
		stillTicks = 0
		RoutePlanner.cancel()
		BaritoneNav.stop()
		runner.release(client)
		phase = Phase.SEEK
	}

	fun arm() {
		if (phase == Phase.SCRIPT || phase == Phase.WAIT_GROTTO || phase == Phase.RING || phase == Phase.HOME || phase == Phase.COVER) return
		phase = Phase.SEEK
		stillTicks = 0
		lastX = Double.NaN
		lastZ = Double.NaN
		dock.clear()
		runner.release(Minecraft.getInstance())
	}

	private fun noteArrival(
		player: net.minecraft.world.entity.player.Player,
		level: net.minecraft.client.multiplayer.ClientLevel
	) {
		val chosen = plan ?: return
		if (chosen.spotLabel.isEmpty() || chosen.spotLabel == "扫点" || chosen.spotLabel == "回出生点") {
			BaritoneNav.takeArrival()
			return
		}
		val stand = chosen.stand
		val reported = BaritoneNav.takeArrival()
		val byBaritone = reported != null && stand != null &&
			reported.x == stand.x && reported.z == stand.z && kotlin.math.abs(reported.y - stand.y) <= 1
		val byFeet = chosen.status == "完整" && stand != null && reachedStand(player, chosen)
		if (!byBaritone && !byFeet) return
		val key = "${stand.x},${stand.y},${stand.z}"
		if (arrivalKey != key) {
			arrivalKey = key
			player.sendSystemMessage(Component.literal("到达终点"))
		}
		if (chosen.cast != null) return
		val solved = RoutePlanner.aimAtSpot(level, player, chosen)
		if (solved == null) {
			val stanceKey = "${stand.x},${stand.y},${stand.z}"
			if ((stanceMisses[stanceKey] ?: 0) >= 1) {
				dropStance("${chosen.spotX},${chosen.spotY},${chosen.spotZ}", stanceKey)
				arrivalKey = ""
				plan = chosen.copy(cast = null, casts = emptyList(), stand = null, status = "靠近鱼点")
				fishPending = true
				player.sendSystemMessage(Component.literal("Realwayfinder 换站位"))
			}
			return
		}
		plan = chosen.copy(cast = solved, casts = listOf(solved), waterX = solved.landX, waterZ = solved.landZ)
	}

	private fun reachedStand(player: net.minecraft.world.entity.player.Player, chosen: PlannedRoute): Boolean {
		val stand = chosen.stand ?: return false
		return reachedCell(player, stand.x, stand.y, stand.z)
	}

	private fun spotChunkLoaded(
		level: net.minecraft.client.multiplayer.ClientLevel,
		chosen: PlannedRoute
	): Boolean {
		val y = chosen.spotY.coerceIn(level.minY, level.maxY - 1)
		return level.isLoaded(net.minecraft.core.BlockPos(chosen.spotX, y, chosen.spotZ))
	}

	private fun advanceRoute(player: net.minecraft.world.entity.player.Player, chosen: PlannedRoute): Boolean {
		if (dock.active || atCast(player, chosen)) return false
		val route = chosen.route ?: return true
		val end = route.nodes.lastOrNull() ?: return true
		val atEnd = reachedCell(player, end.x, end.y, end.z)
		if (route.complete && chosen.cast != null && !atEnd && route.steps.size >= 2) return false
		return atEnd || route.steps.size < 2
	}

	/** A big turn stops forward movement on purpose. That pause is not a stuck route. */
	private fun turningInPlace(player: net.minecraft.world.entity.player.Player, route: Route?): Boolean {
		val nodes = route?.nodes ?: return false
		if (nodes.size < 2) return false
		val fx = floor(player.x).toInt()
		val fz = floor(player.z).toInt()
		var here = 0
		var best = Int.MAX_VALUE
		for (i in nodes.indices) {
			val dist = kotlin.math.abs(nodes[i].x - fx) + kotlin.math.abs(nodes[i].z - fz)
			if (dist < best) {
				best = dist
				here = i
			}
		}
		val aim = nodes[(here + 1).coerceAtMost(nodes.lastIndex)]
		val yaw = Math.toDegrees(kotlin.math.atan2(-(aim.x + 0.5 - player.x), aim.z + 0.5 - player.z)).toFloat()
		return kotlin.math.abs(kcl.realwayfinder.nav.wrapDegrees(yaw - player.yRot)) > 45f
	}

	/** Net movement over 3 seconds. Circling counts as stuck because the window is not reset early. */
	private fun noProgress(player: net.minecraft.world.entity.player.Player, horizontal: Double): Boolean {
		if (poseX.isNaN()) {
			poseX = player.x
			poseY = player.y
			poseZ = player.z
			poseTick = tick
			return false
		}
		if (tick - poseTick < 60) return false
		val net = kotlin.math.hypot(player.x - poseX, player.z - poseZ)
		val rise = player.y - poseY
		poseX = player.x
		poseY = player.y
		poseZ = player.z
		poseTick = tick
		return net < horizontal && rise < 0.8
	}

	private fun rememberFailure(goalX: Int, goalZ: Int, route: Route?, player: net.minecraft.world.entity.player.Player) {
		if (route == null || goalX == 0 && goalZ == 0) return
		val cells = stuckCells(route, floor(player.x).toInt(), floor(player.z).toInt())
		if (RouteMemory.noteFailure(goalX, goalZ, cells)) RouteMemoryStore.save()
	}

	private fun rememberSuccess(goalX: Int, goalZ: Int) {
		val id = "$goalX,$goalZ"
		if (id == savedGoal || !RouteMemory.hasFailure(goalX, goalZ)) return
		savedGoal = id
		RouteMemoryStore.save()
	}

	/** Fishing goals and path nodes: the player must stand on that cell. */
	private fun reachedCell(player: net.minecraft.world.entity.player.Player, x: Int, y: Int, z: Int): Boolean {
		val fx = floor(player.x).toInt()
		val fy = floor(player.y + 1.0e-4).toInt()
		val fz = floor(player.z).toInt()
		return fx == x && fz == z && kotlin.math.abs(fy - y) <= 1
	}

	/** Scan markers only. Fishing casts do not use this radius. */
	private fun reachedScan(player: net.minecraft.world.entity.player.Player, target: ScanPoint): Boolean {
		val flat = kotlin.math.hypot(player.x - (target.x + 0.5), player.z - (target.z + 0.5))
		return flat <= 5.0 && kotlin.math.abs(player.y - target.y) <= 3.0
	}

	/** The route has to end inside the scan box. A path that stopped on the player's own cell does not count. */
	private fun routeAimedAt(route: Route, target: ScanPoint): Boolean {
		val end = route.nodes.lastOrNull() ?: return false
		val flat = kotlin.math.hypot((end.x + 0.5) - (target.x + 0.5), (end.z + 0.5) - (target.z + 0.5))
		return flat <= 5.0 && kotlin.math.abs(end.y - target.y) <= 3
	}

	private fun tickScript(client: Minecraft, player: net.minecraft.world.entity.player.Player) {
		val frames = script
		if (frames == null) {
			phase = Phase.SEEK
			return
		}
		val open = client.gui.screen()
		val name = open?.javaClass?.simpleName
		if (open is PauseScreen || open is ChatScreen || name == "ChatScreen") {
			tapePlayer.release(client)
			return
		}
		tapePlayer.tick(client, player, frames, screenOpen = false)
		plan = PlannedRoute("回放", "${frames.size}", null, null, "回放", 0, 0, 0)
		if (tapePlayer.failed) {
			script = null
			phase = Phase.SEEK
			tapePlayer.reset()
			player.sendSystemMessage(Component.literal("Realwayfinder 格子点击失败，脚本停止"))
			return
		}
		if (tapePlayer.done) {
			val role = scriptRole
			script = null
			scriptRole = null
			plan = null
			tapePlayer.reset()
			if (role == ScriptRole.SCAN) {
				player.sendSystemMessage(Component.literal("Realwayfinder 扫点回放结束"))
				FishingWorld.current?.shortId?.let { RouteSettings.markScanned(it) }
				fishDelay = 60
				phase = Phase.WAIT_FISH
			} else if (role == ScriptRole.RETURN) {
				player.sendSystemMessage(Component.literal("Realwayfinder 回出生点结束"))
				runner.clearRepeat()
				val scanId = scanAfterReturnId
				scanAfterReturnId = null
				if (scanId != null) {
					startIslandScan(scanId)
				} else if (homeThenFish) {
					homeThenFish = false
					pendingPlace = null
					plan = null
					fishPending = true
					RouteSettings.enabled = true
					RouteSettings.save()
					phase = Phase.SEEK
				} else if (scanAfterHome) {
					scanAfterHome = false
					val place = pendingPlace
					if (place != null) {
						skipHome = true
						beginScript(place, ScriptRole.SCAN)
					} else {
						phase = Phase.SEEK
					}
				} else {
					phase = Phase.SEEK
				}
			} else if (role == ScriptRole.ENTER) {
				player.sendSystemMessage(Component.literal("Realwayfinder 进入回放结束"))
				val here = FishingWorld.current
				if (pendingGrotto != null && here == pendingGrotto) {
					beginGrottoRoute(player)
				} else {
					phase = if (pendingGrotto != null) Phase.WAIT_GROTTO else Phase.SEEK
				}
			} else if (role == null) {
				player.sendSystemMessage(Component.literal("Realwayfinder 回放结束"))
				phase = Phase.SEEK
			} else {
				stillTicks = 0
				lastX = Double.NaN
				lastZ = Double.NaN
				phase = Phase.SEEK
			}
		}
	}

	private fun emptyDenied(place: FishingPlace?): PlannedRoute {
		val status = if (place == null) "不在钓鱼岛" else "未选择 ${place.shortId}"
		return PlannedRoute("", "", null, null, status, 0, 0, 0)
	}

}

internal fun missedFishingSpot(text: String): Boolean {
	val lower = text.lowercase().replace('’', '\'')
	return "not a fishing spot" in lower || ("fishing spot" in lower && "cast" in lower)
}

internal fun atCast(player: net.minecraft.world.entity.player.Player, plan: PlannedRoute?): Boolean =
	castHere(player, plan) != null

internal fun castHere(player: net.minecraft.world.entity.player.Player, plan: PlannedRoute?): CastSolution? {
	if (plan == null || plan.spotLabel == "扫点" || plan.spotLabel == "回出生点") return null
	val fx = floor(player.x).toInt()
	val fy = floor(player.y + 1.0e-4).toInt()
	val fz = floor(player.z).toInt()
	val aimed = plan.cast
	if (aimed != null && aimed.stance.x == fx && aimed.stance.z == fz && kotlin.math.abs(aimed.stance.y - fy) <= 1) return aimed
	return plan.casts.firstOrNull {
		it.stance.x == fx && it.stance.z == fz && kotlin.math.abs(it.stance.y - fy) <= 1
	}
}

internal fun standingOnGoal(player: net.minecraft.world.entity.player.Player, end: Node?): Boolean {
	if (end == null || end.mode != Mode.LAND) return false
	return floor(player.x).toInt() == end.x &&
		floor(player.z).toInt() == end.z &&
		floor(player.y + 1.0e-4).toInt() == end.y
}

object RealwayfinderClient : ClientModInitializer {
	private val category = KeyMapping.Category.register(Realwayfinder.id("main"))

	val toggle = KeyMapping(
		"key.realwayfinder.toggle",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_V,
		category
	)

	val setup = KeyMapping(
		"key.realwayfinder.bias",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_N,
		category
	)

	val record = KeyMapping(
		"key.realwayfinder.record",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_R,
		category
	)

	val skip = KeyMapping(
		"key.realwayfinder.skip",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_J,
		category
	)

	val scan = KeyMapping(
		"key.realwayfinder.scan",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_K,
		category
	)

	val resetPath = KeyMapping(
		"key.realwayfinder.reset",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_M,
		category
	)

	override fun onInitializeClient() {
		RouteSettings.load()
		RouteMemoryStore.load()
		ScanRecord.loadAll()
		KeyMappingHelper.registerKeyMapping(toggle)
		KeyMappingHelper.registerKeyMapping(setup)
		KeyMappingHelper.registerKeyMapping(scan)
		KeyMappingHelper.registerKeyMapping(skip)
		KeyMappingHelper.registerKeyMapping(record)
		KeyMappingHelper.registerKeyMapping(resetPath)
		RouteOverlay.register()
		val onMessage: (Component) -> Unit = { message -> RouteRuntime.onChat(message.string) }
		ClientReceiveMessageEvents.GAME.register { message, _ -> onMessage(message) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onMessage(message) }
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			val screen = client.gui.screen()
			val typing = screen is ChatScreen
			if (!typing && Hotkeys.consume(toggle, RouteSettings.modToggle) && screen !is ScriptScreen && screen !is KeyScreen) {
				RouteSettings.enabled = !RouteSettings.enabled
				RouteSettings.save()
				if (RouteSettings.enabled) {
					RouteRuntime.plan = null
					RouteRuntime.arm()
				} else {
					RouteRuntime.halt()
				}
				val state = if (RouteSettings.enabled) "开" else "关"
				client.player?.sendSystemMessage(Component.literal("Realwayfinder $state"))
			}
			if (!typing && Hotkeys.consume(setup, RouteSettings.modSetup)) {
				if (screen is SetupScreen) client.gui.setScreen(null) else if (screen == null) client.gui.setScreen(SetupScreen())
			}
			if (!typing && Hotkeys.consume(scan, RouteSettings.modScan)) RouteRuntime.toggleScan()
			if (!typing && Hotkeys.consume(skip, RouteSettings.modSkip)) RouteRuntime.skipScanPoint()
			if (!typing && Hotkeys.consume(resetPath, RouteSettings.modReset) && screen !is ScriptScreen && screen !is KeyScreen) {
				RouteRuntime.resetRoute()
			}
			if (screen == null && !typing) {
				if (Hotkeys.consume(record, RouteSettings.modRecord)) {
					val player = client.player
					if (player != null) {
						if (ScanRecord.recording) ScanRecord.stop(player)
						else client.gui.setScreen(ScriptScreen(null))
					}
				}
			}
			RouteRuntime.tick()
		}
	}
}
