package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.Realwayfinder
import kcl.realwayfinder.RouteLock
import kcl.realwayfinder.nav.BuiltInRoutes
import kcl.realwayfinder.nav.IslandJob
import kcl.realwayfinder.nav.lobbyScoreboard
import kcl.realwayfinder.nav.perkName
import kcl.realwayfinder.nav.grottoNeedsScan
import kcl.realwayfinder.nav.pickIsland
import kcl.realwayfinder.nav.travelDestination
import kcl.realwayfinder.nav.CastSolution
import kcl.realwayfinder.nav.Mode
import kcl.realwayfinder.nav.Node
import kcl.realwayfinder.nav.Route
import kcl.realwayfinder.nav.RouteMemory
import kcl.realwayfinder.nav.ScanPoint
import kcl.realwayfinder.nav.stuckCells
import kcl.spotfilter.client.Hotkeys
import kcl.spotfilter.client.data.SpotKind
import kcl.realwayfinder.nav.grottoClosed
import kcl.realwayfinder.nav.grottoUnstable
import kcl.realwayfinder.nav.hubPrompt
import kcl.realwayfinder.nav.hourlyClear

import kcl.spotfilter.client.data.FishingPlace
import kcl.spotfilter.client.data.SpotPool
import kcl.spotfilter.client.data.StockLevel
import kcl.spotfilter.client.data.FishingWorld
import net.minecraft.world.scores.DisplaySlot
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
	private var lastHubMs = 0L
	private val dock = CastDock()
	private val runner = PathRunner()
	private var resumeAfterSpawn = false
	private var travelCooldown = 0
	private var ocCooldown = 0
	private var toldNoDestination = false
	/** After a reconnect, press escape twice with five seconds between, then wait five more seconds. */
	private var awaitJoin = false
	private var joinTicks = -1
	private var forcedDest: String? = null
	private var travelNow = false
	private var abandonGrotto = false
	private enum class Phase { SEEK, WAIT_FISH, WAIT_GROTTO, RING, COVER }
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
		if (player == null || level == null) {
			plan = null
				stillTicks = 0
			phase = Phase.SEEK
			dock.clear()
			runner.release(client)
			OcCache.clear()
			travelCooldown = 0
			ocCooldown = 0
			awaitJoin = true
			joinTicks = -1
			MenuClicker.cancel(client)
			return
		}
		if (awaitJoin) {
			awaitJoin = false
			joinTicks = 0
		}
		if (joinTicks >= 0) {
			if (joinTicks == 0 || joinTicks == 100) MenuClicker.tapEscape(client)
			joinTicks++
			if (joinTicks > 200) joinTicks = -1
		}
		if (RouteSettings.enabled) {
			syncDuty(client)
			driveMenus(client, player)
		} else if (!RouteSettings.enabled) {
			MenuClicker.cancel(client)
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
				plan = null
				fishPending = true
				phase = Phase.SEEK
				player.sendSystemMessage(Component.literal("Realwayfinder 开始寻路"))
			}
			return
		}
		if (RouteSettings.enabled) followIslandCycle(FishingWorld.current)
		if (phase != Phase.SEEK) return
		val hereNow = FishingWorld.current
		if (hereNow?.kind == SpotKind.GROTTO && grottoNeedsScan(hereNow.shortId, RouteSettings.scanned, IslandCycle.armedScan)) {
			plan = null
			fishPending = false
			dock.clear()
			BaritoneNav.stop()
			runner.release(client)
			return
		}
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
		if (phase == Phase.SEEK && runner.takeRepeat()) returnHome()
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
		val holdOc = waitingForOverclock(client, plan)
		val castNow = if (holdOc) null else throwCast ?: cast
		dock.tick(client, player, (goal || throwCast != null) && !holdOc, castNow, paused, plan?.waterX ?: Double.NaN, plan?.waterZ ?: Double.NaN)
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
				returnHome()
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

	fun toggleScan() {
		if (phase == Phase.RING || phase == Phase.COVER) {
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
		grottoScan = null
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
		if (!startScan(place)) {
			player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${place.shortId} 的扫点"))
		}
	}

	fun discoverGrotto(grotto: FishingPlace) {
		forgetGrottoScan()
		pendingGrotto = grotto
		if (FishingWorld.current == grotto) {
			abandonGrotto = false
			startGrottoWork(grotto, force = true)
			return
		}
		RouteSettings.noteGrotto(grotto.climate.name)
		abandonGrotto = false
		requestIslandTravel(grotto.climate.name)
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 前往 ${grotto.shortId}"))
	}

	fun onActionBar(text: String) {
		if (grottoUnstable(text)) forgetUnstableGrotto()
		if (!hubPrompt(text)) return
		val now = System.currentTimeMillis()
		if (now - lastHubMs < 5000) return
		lastHubMs = now
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		client.gui.setScreen(net.minecraft.client.gui.screens.ChatScreen("/hub", true))
		player.connection.sendCommand("hub")
		client.gui.setScreen(null)
		player.sendSystemMessage(Component.literal("Realwayfinder /hub"))
	}

	fun onChat(text: String) {
		GrottoEntry.onChat(text)
		if (grottoUnstable(text)) forgetUnstableGrotto()
		if (grottoClosed(text)) {
			forgetGrottoScan()
			RouteSettings.noteGrotto("")
			pendingGrotto = null
			abandonGrotto = true
			val next = pickIsland(IslandCycle.enabledOrder(), RouteSettings.scanned, RouteSettings.finished)
			if (next != null) {
				IslandCycle.duty = next.id
				IslandCycle.job = next.job
				IslandCycle.returnedFor = next.id
				requestIslandTravel(next.id)
			}
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

	/** Discovery, collapse, and the unstable teleport all drop every grotto scanned mark. */
	private fun forgetGrottoScan() {
		RouteSettings.resetGrottoScanned()
		if (RouteSettings.isGrottoScanId(IslandCycle.armedScan ?: "")) IslandCycle.armedScan = null
	}

	/** Game is pulling the player out. Do not keep this visit's scan, and do not start another one here. */
	private fun forgetUnstableGrotto() {
		val scanning = phase == Phase.COVER || (phase == Phase.RING && ringGrotto)
		val had = scanning || pendingGrotto != null ||
			RouteSettings.scanned.any { RouteSettings.isGrottoScanId(it) } ||
			RouteSettings.isGrottoScanId(IslandCycle.armedScan ?: "")
		forgetGrottoScan()
		pendingGrotto = null
		abandonGrotto = true
		fishPending = false
		if (scanning || ringGrotto) {
			ring = emptyList()
			ringRoute = null
			grottoScan = null
			heldScan = null
			pausedCover = false
			plan = null
			phase = Phase.SEEK
			BaritoneNav.stop()
			runner.release(Minecraft.getInstance())
		}
		if (had) {
			Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder Grotto 扫点已重置"))
		}
	}

	private fun startIslandHour() {
		val place = FishingWorld.current
		RouteSettings.resetScanned()
		skippedSpots.clear()
		biteFailures.clear()
		IslandCycle.resetToTop()
		val player = Minecraft.getInstance().player
		if (place != null && place.kind == SpotKind.GROTTO) {
			forcedDest = null
			travelNow = false
			player?.sendSystemMessage(Component.literal("Realwayfinder 整点已重置，留在 Grotto"))
			return
		}
		val duty = IslandCycle.duty
		if (duty == null) return
		if (place != null && IslandCycle.matches(place)) {
			IslandCycle.armedScan = duty
			startScan(place)
		} else {
			ring = emptyList()
			ringRoute = null
			grottoScan = null
			plan = null
			if (phase == Phase.RING || phase == Phase.COVER || phase == Phase.WAIT_FISH) {
				phase = Phase.SEEK
			}
			IslandCycle.armedScan = null
			IslandCycle.waiting = true
			IslandCycle.job = kcl.realwayfinder.nav.IslandJob.SCAN
			IslandCycle.returnedFor = null
			requestIslandTravel(duty)
			player?.sendSystemMessage(Component.literal("Realwayfinder 前往 $duty"))
		}
	}

	private fun followIslandCycle(place: FishingPlace?) {
		if (place == null) return
		if (place.kind == SpotKind.GROTTO) {
			followGrotto(place)
			return
		}
		if (RouteSettings.isGrottoScanId(IslandCycle.armedScan ?: "")) IslandCycle.armedScan = null
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
			if (IslandCycle.returnedFor != pick.id) {
				IslandCycle.returnedFor = pick.id
				requestIslandTravel(pick.id)
				val msg = if (pick.job == IslandJob.SCAN) "前往 ${pick.id} 扫点" else "前往 ${pick.id} 钓鱼"
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
			if (!startScan(place)) {
				Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${place.shortId} 的扫点"))
			}
		}
	}

	/** One cover plan per grotto visit. Fishing waits until that plan's ring finishes. */
	private fun followGrotto(place: FishingPlace) {
		if (phase != Phase.SEEK || abandonGrotto) return
		if (!grottoNeedsScan(place.shortId, RouteSettings.scanned, IslandCycle.armedScan)) return
		startGrottoWork(place)
	}

	private fun startGrottoWork(place: FishingPlace, force: Boolean = false) {
		val id = place.shortId.uppercase()
		if (!force && (phase == Phase.COVER || (phase == Phase.RING && ringGrotto))) return
		if (!force && !grottoNeedsScan(id, RouteSettings.scanned, IslandCycle.armedScan)) return
		val player = Minecraft.getInstance().player ?: return
		ring = emptyList()
		ringRoute = null
		grottoScan = null
		pendingGrotto = place
		abandonGrotto = false
		RouteSettings.clearScanned(id)
		IslandCycle.armedScan = id
		beginGrottoRoute(player)
	}

	/** A scanned island with no qualifying spot left is finished. The next pick is then scanned. */
	private fun refreshFinished() {
		val here = FishingWorld.current
		val noFishHere = plan?.status == "没有鱼点" && here != null && here.kind != SpotKind.GROTTO
		for (id in RouteSettings.scanned.toList()) {
			if (RouteSettings.isGrottoScanId(id)) continue
			when (IslandCycle.remaining(id, skippedSpots)) {
				true -> RouteSettings.clearFinished(id)
				false -> RouteSettings.markFinished(id)
				null -> if (noFishHere && here.shortId.equals(id, ignoreCase = true)) RouteSettings.markFinished(id)
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
		phase = Phase.SEEK
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 没有 $id 的扫点"))
	}

	private fun startScan(place: FishingPlace): Boolean {
		if (place.kind == SpotKind.GROTTO) {
			val who = Minecraft.getInstance().player ?: return false
			val id = place.shortId.uppercase()
			RouteSettings.clearScanned(id)
			IslandCycle.armedScan = id
			pendingGrotto = place
			beginGrottoRoute(who)
			return true
		}
		val builtIn = BuiltInRoutes.island(place.shortId)
		if (builtIn != null) {
			startRing(builtIn, grotto = false)
			return true
		}
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Realwayfinder 没有 ${place.shortId} 的扫点"))
		return false
	}

	/** Re-enter the current island or grotto through the navigator so the player spawns again. */
	private fun returnHome() {
		val place = FishingWorld.current ?: return
		val player = Minecraft.getInstance().player
		resumeAfterSpawn = true
		ring = emptyList()
		ringRoute = null
		grottoScan = null
		plan = null
		phase = Phase.SEEK
		BaritoneNav.stop()
		runner.clearRepeat()
		val dest = if (place.kind == SpotKind.GROTTO) place.climate.name else place.shortId
		requestIslandTravel(dest)
		player?.sendSystemMessage(Component.literal("Realwayfinder 回出生点"))
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

	/** Wrong island does not become the scan. Stop and let the priority pick send us onward. */
	private fun adaptScanIsland(player: net.minecraft.world.entity.player.Player): Boolean {
		if (ringGrotto) return false
		val here = FishingWorld.current ?: return false
		if (here.kind == SpotKind.GROTTO) return false
		val id = here.shortId.uppercase()
		val scanning = (IslandCycle.duty ?: IslandCycle.armedScan)?.uppercase() ?: return false
		if (id == scanning) return false
		BaritoneNav.stop()
		ring = emptyList()
		ringRoute = null
		runner.release(Minecraft.getInstance())
		phase = Phase.SEEK
		IslandCycle.armedScan = null
		IslandCycle.returnedFor = null
		player.sendSystemMessage(Component.literal("Realwayfinder 人在 $id，继续按优先级去 $scanning"))
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
		plan = null
		fishPending = true
		stillTicks = 0
		runner.clearRepeat()
		player.sendSystemMessage(Component.literal("Realwayfinder 扫点结束"))
		if (ringGrotto) {
			val id = FishingWorld.current?.shortId ?: pendingGrotto?.shortId
			if (id != null) {
				RouteSettings.markScanned(id)
				IslandCycle.armedScan = id.uppercase()
			}
			startGrottoFish()
			fishPending = true
			return
		}
		FishingWorld.current?.shortId?.let { RouteSettings.markScanned(it) }
		IslandCycle.job = IslandJob.FISH
		IslandCycle.armedScan = null
		phase = Phase.SEEK
		player.sendSystemMessage(Component.literal("Realwayfinder 开始寻路"))
	}

	private fun startGrottoFish() {
		pendingGrotto = null
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
		MenuClicker.cancel(Minecraft.getInstance())
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
		if (phase == Phase.RING || phase == Phase.COVER) {
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
		MenuClicker.cancel(client)
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
		if (phase == Phase.WAIT_GROTTO || phase == Phase.RING || phase == Phase.COVER) return
		phase = Phase.SEEK
		stillTicks = 0
		lastX = Double.NaN
		lastZ = Double.NaN
		dock.clear()
		runner.release(Minecraft.getInstance())
	}

	private fun syncDuty(client: Minecraft) {
		val here = FishingWorld.current
		if (here?.kind != SpotKind.GROTTO && RouteSettings.isGrottoScanId(IslandCycle.armedScan ?: "")) {
			IslandCycle.armedScan = null
		}
		if (abandonGrotto && here?.kind != SpotKind.GROTTO) abandonGrotto = false
		if (!abandonGrotto) {
			val grotto = pendingGrotto ?: here?.takeIf { it.kind == SpotKind.GROTTO }
			if (grotto != null) RouteSettings.noteGrotto(grotto.climate.name)
		}
		val duty = IslandCycle.duty
		if (!duty.isNullOrBlank()) RouteSettings.noteIsland(duty)
		val dest = forcedDest
		if (dest != null && here != null && arrivedAt(dest, here)) {
			forcedDest = null
			travelNow = false
			val screen = client.gui.screen()
			if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ||
				screen is net.minecraft.client.gui.screens.inventory.InventoryScreen
			) {
				client.gui.setScreen(null)
			}
			if (resumeAfterSpawn) {
				resumeAfterSpawn = false
				if (here.kind == SpotKind.GROTTO) startGrottoWork(here, force = true)
				else resumeFishing(here)
			} else if (here.kind == SpotKind.GROTTO) {
				startGrottoWork(here, force = true)
			} else if (here.shortId.equals(duty, ignoreCase = true)) {
				startDutyWork(here)
			}
		}
	}

	private fun resumeFishing(place: FishingPlace) {
		plan = null
		fishPending = true
		stillTicks = 0
		phase = Phase.SEEK
		if (place.kind == SpotKind.GROTTO) {
			IslandCycle.armedScan = place.shortId.uppercase()
			startGrottoFish()
			fishPending = true
			return
		}
		IslandCycle.job = IslandJob.FISH
		IslandCycle.armedScan = place.shortId.uppercase()
	}

	private fun startDutyWork(place: FishingPlace) {
		val id = place.shortId.uppercase()
		if (IslandCycle.job == IslandJob.FISH) {
			if (phase != Phase.SEEK) phase = Phase.SEEK
			fishPending = true
			return
		}
		if (phase == Phase.RING && IslandCycle.armedScan == id) return
		IslandCycle.armedScan = id
		IslandCycle.job = IslandJob.SCAN
		if (!startScan(place)) {
			phase = Phase.SEEK
			fishPending = true
		}
	}

	private fun arrivedAt(dest: String, place: FishingPlace): Boolean {
		if (place.kind == SpotKind.GROTTO) return place.climate.name.equals(dest, ignoreCase = true)
		return place.shortId.equals(dest, ignoreCase = true)
	}

	private fun requestIslandTravel(dest: String) {
		val id = dest.uppercase()
		if (forcedDest.equals(id, ignoreCase = true) && !MenuClicker.idle()) return
		forcedDest = id
		travelNow = true
		travelCooldown = 0
		if (phase == Phase.WAIT_GROTTO) phase = Phase.SEEK
		dock.clear()
	}

	private fun leaveDepletedGrotto(client: Minecraft, player: net.minecraft.world.entity.player.Player) {
		val here = FishingWorld.current
		if (here == null || here.kind != SpotKind.GROTTO || abandonGrotto) return
		val spots = SpotPool.all().filter { it.place == here }
		if (spots.isEmpty() || spots.any { it.stock != StockLevel.DEPLETED }) return
		abandonGrotto = true
		pendingGrotto = null
		RouteSettings.clearScanned(here.shortId)
		if (IslandCycle.armedScan.equals(here.shortId, ignoreCase = true)) IslandCycle.armedScan = null
		RouteSettings.noteGrotto("")
		val next = pickIsland(IslandCycle.enabledOrder(), RouteSettings.scanned, RouteSettings.finished)
		if (next == null) {
			player.sendSystemMessage(Component.literal("Realwayfinder Grotto 已耗尽"))
			return
		}
		IslandCycle.duty = next.id
		IslandCycle.job = next.job
		IslandCycle.returnedFor = next.id
		IslandCycle.waiting = true
		player.sendSystemMessage(Component.literal("Realwayfinder Grotto 已耗尽，前往 ${next.id}"))
		phase = Phase.SEEK
		plan = null
		requestIslandTravel(next.id)
	}

	private fun lobby(client: Minecraft): Boolean {
		val objective = client.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
		return lobbyScoreboard(objective.displayName.string)
	}

	private fun ownScreen(client: Minecraft): Boolean {
		val screen = client.gui.screen()
		return screen is SetupScreen || screen is OverclockPage || screen is MoveScreen ||
			screen is KeyScreen || screen is HudEditScreen
	}

	private fun driveMenus(client: Minecraft, player: net.minecraft.world.entity.player.Player) {
		leaveDepletedGrotto(client, player)
		if (joinTicks >= 0) return
		val inLobby = lobby(client)
		if (!inLobby) toldNoDestination = false
		MenuClicker.tick(client, player)
		if (!MenuClicker.idle() || ownScreen(client)) return
		if (client.gui.screen() != null) return
		if (inLobby || travelNow || forcedDest != null) {
			if (travelCooldown > 0) {
				travelCooldown--
				return
			}
			val dest = forcedDest ?: travelDestination(
				RouteSettings.dutyGrotto,
				IslandCycle.enabledOrder(),
				RouteSettings.scanned,
				RouteSettings.finished
			)
			if (dest == null) {
				if (!toldNoDestination) {
					toldNoDestination = true
					player.sendSystemMessage(Component.literal("Realwayfinder 没有要去的岛"))
				}
			} else {
				MenuClicker.startTravel(dest)
			}
			travelNow = false
			travelCooldown = 40
			return
		}
		val chosen = plan ?: return
		if (phase != Phase.SEEK || !fishPlan(chosen)) return
		if (ocCooldown > 0) {
			ocCooldown--
			return
		}
		val spot = SpotPool.all().firstOrNull { it.x == chosen.spotX && it.y == chosen.spotY && it.z == chosen.spotZ } ?: return
		val oc = RoutePlanner.overclockFor(spot) ?: return
		val triple = oc.second
		val hook = triple.hook.perkName()
		val magnet = triple.magnet.perkName()
		val rod = triple.rod.perkName()
		if (OcCache.matches(oc.first, hook, magnet, rod)) {
			if (!triple.kept()) MenuClicker.showIdle("Overclock 相符")
			return
		}
		MenuClicker.startSupplies(oc.first, oc.second)
		ocCooldown = 40
	}

	private fun fishPlan(chosen: PlannedRoute): Boolean =
		chosen.spotLabel.isNotEmpty() && chosen.spotLabel != "扫点" && chosen.spotLabel != "回出生点" && chosen.spotLabel != "回放"

	private fun waitingForOverclock(client: Minecraft, chosen: PlannedRoute?): Boolean {
		if (MenuClicker.holdingCast()) return true
		if (client.gui.screen() != null || chosen == null || !fishPlan(chosen)) return false
		val spot = SpotPool.all().firstOrNull { it.x == chosen.spotX && it.y == chosen.spotY && it.z == chosen.spotZ } ?: return false
		val oc = RoutePlanner.overclockFor(spot) ?: return false
		return !OcCache.matches(oc.first, oc.second.hook.perkName(), oc.second.magnet.perkName(), oc.second.rod.perkName())
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
		OverclockFile.load()
		RouteMemoryStore.load()
		KeyMappingHelper.registerKeyMapping(toggle)
		KeyMappingHelper.registerKeyMapping(setup)
		KeyMappingHelper.registerKeyMapping(scan)
		KeyMappingHelper.registerKeyMapping(skip)
		KeyMappingHelper.registerKeyMapping(resetPath)
		RouteOverlay.register()
		val onMessage: (Component) -> Unit = { message -> RouteRuntime.onChat(message.string) }
		ClientReceiveMessageEvents.GAME.register { message, _ -> onMessage(message) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onMessage(message) }
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			val screen = client.gui.screen()
			val typing = screen is ChatScreen
			if (!typing && Hotkeys.consume(toggle, RouteSettings.modToggle) && screen !is KeyScreen) {
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
			if (!typing && Hotkeys.consume(resetPath, RouteSettings.modReset) && screen !is KeyScreen) {
				RouteRuntime.resetRoute()
			}
			RouteRuntime.tick()
		}
	}
}
