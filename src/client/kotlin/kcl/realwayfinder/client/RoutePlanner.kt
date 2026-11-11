package kcl.realwayfinder.client

import kcl.realwayfinder.nav.CastSolution
import kcl.realwayfinder.nav.Cell
import kcl.realwayfinder.nav.FishTarget
import kcl.realwayfinder.nav.Grid
import kcl.realwayfinder.nav.GrottoKind
import kcl.realwayfinder.nav.GrottoRank
import kcl.realwayfinder.nav.Route
import kcl.realwayfinder.nav.Node
import kcl.realwayfinder.nav.Mode
import kcl.realwayfinder.nav.anchor
import kcl.realwayfinder.nav.findCasts
import kcl.realwayfinder.nav.solveCast
import kcl.realwayfinder.nav.solveCastFrom
import kcl.realwayfinder.nav.approachPoint
import kcl.realwayfinder.nav.findPath
import kcl.realwayfinder.nav.nearPlannedEnd
import kcl.realwayfinder.nav.nearestStand
import kcl.realwayfinder.nav.openAirBetween
import kcl.realwayfinder.nav.pickIsland
import kcl.realwayfinder.nav.grottoColKey
import kcl.realwayfinder.nav.grottoSameFloor
import kcl.realwayfinder.nav.isLand
import kcl.realwayfinder.nav.ScanPoint
import kcl.realwayfinder.nav.reachableSpawnLand
import kcl.realwayfinder.nav.waterTouchesSpawnLand

import kcl.realwayfinder.nav.grottoPrefer
import kcl.realwayfinder.nav.normalPrefer
import kcl.realwayfinder.nav.grottoKindOf
import kcl.realwayfinder.nav.islandLead
import kcl.realwayfinder.nav.islandSpotQualifies
import kcl.realwayfinder.nav.normalRank
import kcl.spotfilter.client.data.FishingSpot
import kcl.spotfilter.client.data.FishingWorld
import kcl.spotfilter.client.data.SpotKind
import kcl.spotfilter.client.data.SpotPool
import kcl.spotfilter.client.data.StabilityCost
import kcl.spotfilter.client.data.StockLevel
import kcl.spotfilter.client.parse.PerkType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class PlannedRoute(
	val spotLabel: String,
	val biasLabel: String,
	val route: Route?,
	val cast: CastSolution?,
	val status: String,
	val spotX: Int,
	val spotY: Int,
	val spotZ: Int,
	val casts: List<CastSolution> = emptyList(),
	val waterX: Double = Double.NaN,
	val waterZ: Double = Double.NaN,
	/** Land cell Baritone walks to. Never the water under the fishing spot. */
	val stand: Node? = null
) {
}

object RoutePlanner {
	fun queue(): List<FishingSpot> {
		val here = FishingWorld.current ?: return emptyList()
		return if (here.kind == SpotKind.GROTTO) {
			rankGrotto(here, RouteSettings.grottoOrder, RouteSettings.grottoTargets)
		} else {
			rankIsland(here, RouteSettings.fishTargets, RouteSettings.fishOrder)
		}
	}

	fun plan(
		level: ClientLevel,
		player: Player,
		targets: Set<FishTarget>,
		fishOrder: List<FishTarget>,
		grottoOrder: List<GrottoKind>
	): PlannedRoute {
		val here = FishingWorld.current
			?: return emptyPlan("不在钓鱼岛")
		val grotto = here.kind == SpotKind.GROTTO
		if (grotto && kcl.realwayfinder.nav.grottoNeedsScan(here.shortId, RouteSettings.scanned, IslandCycle.armedScan)) {
			return emptyPlan("扫点未完成")
		}
		if (!grotto) {
			val hold = islandHold(here)
			if (hold != null) return emptyPlan(hold)
		}
		val ranked = if (grotto) rankGrotto(here, grottoOrder, RouteSettings.grottoTargets) else rankIsland(here, targets, fishOrder)
		val spot = if (grotto) firstOpen(level, ranked) else ranked.firstOrNull()
		if (spot == null) return emptyPlan(if (grotto) "没有 grotto 点" else "没有鱼点")
		val label = if (grotto) "Grotto" else targets.joinToString("/") { it.name.lowercase() }
		return planSpot(level, player, spot, label)
	}

	fun replan(level: ClientLevel, player: Player, x: Int, y: Int, z: Int): PlannedRoute? {
		val here = FishingWorld.current ?: return null
		val spot = SpotPool.all().firstOrNull {
			it.x == x && it.y == y && it.z == z && it.place == here && it.stock != StockLevel.DEPLETED
		} ?: return null
		if (here.kind == SpotKind.GROTTO && temperateUnreachable(level, spot)) return null
		val label = if (here.kind == SpotKind.GROTTO) "Grotto" else RouteSettings.fishTargets.joinToString("/") { it.name.lowercase() }
		return planSpot(level, player, spot, label)
	}

	/** Temperate grotto only. Skip water that is off the spawn floor or not connected to the spawn. */
	private fun firstOpen(level: ClientLevel, spots: List<FishingSpot>): FishingSpot? {
		for (spot in spots) {
			if (!temperateUnreachable(level, spot)) return spot
		}
		return null
	}

	private var temperateMap = ""
	private var temperateLand: Set<Long>? = null

	private fun temperateUnreachable(level: ClientLevel, spot: FishingSpot): Boolean {
		val map = kcl.realwayfinder.nav.BuiltInRoutes.matchGrotto(spot.x.toDouble(), spot.z.toDouble()) ?: return false
		if (!map.name.startsWith("grotto_temperate")) return false
		val spawn = map.spawn
		val probe = spot.y.coerceIn(level.minY, level.maxY - 1)
		if (!level.isLoaded(BlockPos(spot.x, probe, spot.z))) return false
		val waterY = columnFloor(level, spot.x, spot.z, spawn.y)
		if (waterY == null || waterY.first != FloorCol.WATER) return false
		if (!grottoSameFloor(waterY.second, spawn.y)) return true
		val land = temperateLand(level, map.name, spawn) ?: return false
		return !waterBodyTouches(level, spot.x, spot.z, spawn.y, land)
	}

	private fun temperateLand(level: ClientLevel, name: String, spawn: ScanPoint): Set<Long>? {
		if (temperateMap == name && temperateLand != null) return temperateLand
		val landY = HashMap<Long, Int>()
		val seen = HashSet<Long>()
		val queue = ArrayDeque<Long>()
		var start: Long? = null
		var best = Int.MAX_VALUE
		var incomplete = false
		for (dx in -4..4) {
			for (dz in -4..4) {
				val col = columnFloor(level, spawn.x + dx, spawn.z + dz, spawn.y) ?: run {
					incomplete = true
					continue
				}
				if (col.first != FloorCol.LAND || !grottoSameFloor(col.second, spawn.y)) continue
				val dist = max(abs(dx), abs(dz))
				if (dist < best) {
					best = dist
					start = grottoColKey(spawn.x + dx, spawn.z + dz)
				}
			}
		}
		val origin = start ?: return if (incomplete) null else emptySet()
		seen.add(origin)
		queue.add(origin)
		landY[origin] = columnFloor(level, (origin shr 32).toInt(), origin.toInt(), spawn.y)?.second ?: spawn.y
		var steps = 0
		while (queue.isNotEmpty() && steps < 6000) {
			val key = queue.removeFirst()
			steps++
			val x = (key shr 32).toInt()
			val z = key.toInt()
			val y = landY[key] ?: continue
			for (dx in -1..1) {
				for (dz in -1..1) {
					if (dx == 0 && dz == 0) continue
					val nx = x + dx
					val nz = z + dz
					val next = grottoColKey(nx, nz)
					if (next in seen) continue
					val col = columnFloor(level, nx, nz, spawn.y)
					if (col == null) {
						incomplete = true
						continue
					}
					if (col.first != FloorCol.LAND) continue
					val ny = col.second
					if (!grottoSameFloor(ny, spawn.y) || !grottoSameFloor(ny, y)) continue
					if (ny - y > 1) continue
					seen.add(next)
					landY[next] = ny
					queue.add(next)
				}
			}
		}
		if (incomplete || steps >= 6000) return null
		val reached = reachableSpawnLand(spawn, landY)
		temperateMap = name
		temperateLand = reached
		return reached
	}

	private fun waterBodyTouches(level: ClientLevel, x: Int, z: Int, spawnY: Int, land: Set<Long>): Boolean {
		val waterY = HashMap<Long, Int>()
		val queue = ArrayDeque<Long>()
		val start = grottoColKey(x, z)
		val origin = columnFloor(level, x, z, spawnY) ?: return true
		if (origin.first != FloorCol.WATER) return true
		waterY[start] = origin.second
		queue.add(start)
		var steps = 0
		while (queue.isNotEmpty() && steps < 2000) {
			val key = queue.removeFirst()
			steps++
			val cx = (key shr 32).toInt()
			val cz = key.toInt()
			for (dx in -1..1) {
				for (dz in -1..1) {
					if (dx == 0 && dz == 0) continue
					if (dx != 0 && dz != 0) continue
					val next = grottoColKey(cx + dx, cz + dz)
					if (next in waterY) continue
					val col = columnFloor(level, cx + dx, cz + dz, spawnY) ?: return true
					if (col.first != FloorCol.WATER || !grottoSameFloor(col.second, spawnY)) continue
					waterY[next] = col.second
					queue.add(next)
				}
			}
		}
		if (steps >= 2000) return true
		return waterTouchesSpawnLand(spawnY, waterY, land)
	}

	private enum class FloorCol { NONE, LAND, WATER }

	/** Null when the column is not loaded. */
	private fun columnFloor(level: ClientLevel, x: Int, z: Int, spawnY: Int): Pair<FloorCol, Int>? {
		val probe = spawnY.coerceIn(level.minY, level.maxY - 1)
		if (!level.isLoaded(BlockPos(x, probe, z))) return null
		val low = if (spawnY <= 50) level.minY else 51
		val high = if (spawnY <= 50) min(50, level.maxY - 1) else level.maxY - 1
		for (y in high downTo low) {
			val state = level.getBlockState(BlockPos(x, y, z))
			if (state.isAir) continue
			val above = level.getBlockState(BlockPos(x, y + 1, z))
			val aboveOpen = above.isAir || !above.blocksMotion() || above.`is`(Blocks.LILY_PAD)
			if (!aboveOpen) continue
			if (state.`is`(Blocks.LILY_PAD) || above.`is`(Blocks.LILY_PAD)) {
				val feet = if (state.`is`(Blocks.LILY_PAD)) y else y + 1
				if (grottoSameFloor(feet, spawnY)) return FloorCol.LAND to feet
				continue
			}
			val fluid = state.fluidState
			if (!fluid.isEmpty && fluid.`is`(FluidTags.WATER) && fluid.isSource) {
				if (grottoSameFloor(y, spawnY)) return FloorCol.WATER to y
				continue
			}
			if (!state.blocksMotion()) continue
			val feet = y + 1
			if (feet > high || !grottoSameFloor(feet, spawnY)) continue
			return FloorCol.LAND to feet
		}
		return FloorCol.NONE to 0
	}

	private fun spotRemoved(spot: FishingSpot): Boolean {
		val id = "${spot.x},${spot.y},${spot.z}"
		return id in RouteRuntime.deletedSpots || id in RouteRuntime.skippedSpots
	}

	private fun rankIsland(
		here: kcl.spotfilter.client.data.FishingPlace,
		targets: Set<FishTarget>,
		order: List<FishTarget>
	): List<FishingSpot> {
		if (targets.isEmpty()) return emptyList()
		return SpotPool.all().mapNotNull { spot ->
			if (spot.place != here || spot.kind == SpotKind.GROTTO) return@mapNotNull null
			if (spot.stock == StockLevel.DEPLETED) return@mapNotNull null
			if (spotRemoved(spot)) return@mapNotNull null
			val xp = spot.perkValue(PerkType.XP_MAGNET)
			if (!islandSpotQualifies(
					spot.perkValue(PerkType.STRONG_HOOK),
					spot.perkValue(PerkType.WISE_HOOK),
					spot.perkValue(PerkType.ELUSIVE_CHANCE),
					spot.perkValue(PerkType.WAYFINDER_DATA),
					spot.perkValue(PerkType.PEARL_CHANCE),
					spot.perkValue(PerkType.TREASURE_CHANCE),
					spot.perkValue(PerkType.SPIRIT_CHANCE),
					targets,
					xp
				)
			) return@mapNotNull null
			val rank = normalRank(
				spot.perkValue(PerkType.STRONG_HOOK),
				spot.perkValue(PerkType.WISE_HOOK),
				spot.perkValue(PerkType.ELUSIVE_CHANCE),
				spot.perkValue(PerkType.WAYFINDER_DATA),
				spot.perkValue(PerkType.PEARL_CHANCE),
				spot.perkValue(PerkType.TREASURE_CHANCE),
				spot.perkValue(PerkType.SPIRIT_CHANCE),
				order,
				targets,
				xp
			) ?: return@mapNotNull null
			spot to rank
		}.sortedWith { left, right ->
			when {
				normalPrefer(left.second, right.second) -> -1
				normalPrefer(right.second, left.second) -> 1
				else -> 0
			}
		}.map { it.first }
	}

	private fun rankGrotto(
		here: kcl.spotfilter.client.data.FishingPlace,
		order: List<GrottoKind>,
		enabled: Set<GrottoKind>
	): List<FishingSpot> {
		return SpotPool.all().mapNotNull { spot ->
			if (spot.place != here) return@mapNotNull null
			if (spot.stock == StockLevel.DEPLETED) return@mapNotNull null
			if (spotRemoved(spot)) return@mapNotNull null
			val rank = grottoRank(spot, order, enabled) ?: return@mapNotNull null
			spot to rank
		}.sortedWith { left, right ->
			when {
				grottoPrefer(left.second, right.second) -> -1
				grottoPrefer(right.second, left.second) -> 1
				else -> 0
			}
		}.map { it.first }
	}

	fun overclockFor(spot: FishingSpot): Pair<String, OcTriple>? {
		val place = spot.place
		if (place?.kind == SpotKind.GROTTO || spot.kind == SpotKind.GROTTO) {
			val kind = grottoKindOf(
				spot.perkValue(PerkType.FISH_MAGNET),
				spot.perkValue(PerkType.ELUSIVE_CHANCE),
				spot.perkValue(PerkType.FISH_CHANCE),
				spot.perkValue(PerkType.PEARL_CHANCE),
				spot.perkValue(PerkType.TREASURE_CHANCE),
				spot.perkValue(PerkType.SPIRIT_CHANCE)
			) ?: return null
			if (kind !in RouteSettings.grottoTargets) return null
			return "grotto:${kind.name}" to OverclockFile.grotto(kind)
		}
		val xp = spot.perkValue(PerkType.XP_MAGNET)
		val lead = islandLead(
			spot.perkValue(PerkType.STRONG_HOOK),
			spot.perkValue(PerkType.WISE_HOOK),
			spot.perkValue(PerkType.ELUSIVE_CHANCE),
			spot.perkValue(PerkType.WAYFINDER_DATA),
			spot.perkValue(PerkType.PEARL_CHANCE),
			spot.perkValue(PerkType.TREASURE_CHANCE),
			spot.perkValue(PerkType.SPIRIT_CHANCE),
			RouteSettings.fishOrder,
			RouteSettings.fishTargets,
			xp
		) ?: return null
		return "fish:${lead.name}" to OverclockFile.fish(lead)
	}

	private fun grottoRank(spot: FishingSpot, order: List<GrottoKind>, enabled: Set<GrottoKind>): GrottoRank? {
		val kind = grottoKindOf(
			spot.perkValue(PerkType.FISH_MAGNET),
			spot.perkValue(PerkType.ELUSIVE_CHANCE),
			spot.perkValue(PerkType.FISH_CHANCE),
			spot.perkValue(PerkType.PEARL_CHANCE),
			spot.perkValue(PerkType.TREASURE_CHANCE),
			spot.perkValue(PerkType.SPIRIT_CHANCE)
		) ?: return null
		if (kind !in enabled) return null
		val type = order.indexOf(kind).let { if (it < 0) order.size else it }
		val cost = when (spot.stability) {
			StabilityCost.LOW -> 0
			StabilityCost.MEDIUM -> 1
			StabilityCost.HIGH -> 2
			null -> 3
		}
		return GrottoRank(cost, type, spot.grottoBonusScore())
	}

	private fun planSpot(
		level: ClientLevel,
		player: Player,
		spot: FishingSpot,
		biasLabel: String,
		prepared: Grid? = null
	): PlannedRoute {
		val label = spot.displayTitle()
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val flat = hypot(spot.x + 0.5 - player.x, spot.z + 0.5 - player.z)
		val spotPos = BlockPos(spot.x, spot.y.coerceIn(level.minY, level.maxY - 1), spot.z)
		if (flat > APPROACH_REACH || !level.isLoaded(spotPos)) {
			return approach(level, spot, label, biasLabel, px, py, pz, prepared)
		}
		val grid = prepared ?: sample(level, px, py, pz, spot.x, spot.y, spot.z)
		val look = displayPoint(level, spot)
		val water = waterTarget(grid, look.x, look.y, look.z)
		val solved = findCasts(
			grid,
			water.x,
			water.y,
			water.z,
			look.x,
			look.y,
			look.z
		)
		val visible = solved.filter { seesDisplay(level, player, it.stance, look) }
		val open = (if (visible.isNotEmpty()) visible else solved)
			.filter { castKey(it.stance) !in RouteRuntime.avoidCasts }
			.filter { "${it.stance.x},${it.stance.z}" !in RouteRuntime.failedColumns }
		val wx = floor(water.x).toInt()
		val wy = floor(water.y).toInt()
		val wz = floor(water.z).toInt()
		val casts = (open.ifEmpty { if (visible.isNotEmpty()) visible else solved }).filter {
			nearPlannedEnd(it.stance, wx, wy, wz, RouteSettings.arriveX, RouteSettings.arriveZ, RouteSettings.arriveY)
		}
		if (casts.isEmpty()) {
			val stand = nearestStand(grid, wx, wy, wz)
			val start = anchor(grid, px, py, pz)
			val route = if (stand != null && start != null) {
				findPath(grid, start, setOf(stand), penalty = remembered(spot.x, spot.z))
			} else {
				null
			}
			return PlannedRoute(label, biasLabel, route, null, "靠近鱼点", spot.x, spot.y, spot.z, waterX = water.x, waterZ = water.z)
		}
		val best = casts.minWith(
			compareBy(
				{ (it.stance.x - wx) * (it.stance.x - wx) + (it.stance.z - wz) * (it.stance.z - wz) },
				{ abs(it.stance.y - wy) }
			)
		)
		val start = anchor(grid, px, py, pz)
			?: return PlannedRoute(label, biasLabel, null, null, "脚下没有可站位置", spot.x, spot.y, spot.z)
		val route = findPath(grid, start, setOf(best.stance), penalty = remembered(spot.x, spot.z))
			?: return approach(level, spot, label, biasLabel, px, py, pz)
		val status = if (route.complete) "完整" else "路径不完整"
		return PlannedRoute(label, biasLabel, route, best, status, spot.x, spot.y, spot.z, casts, water.x, water.z)
	}

	/**
	 * Same search as a fishing route, aimed at a stored scan coordinate.
	 * Work is spread across ticks so the client thread does not pause.
	 */
	fun requestWalk(x: Int, y: Int, z: Int) {
		val key = "w:$x,$y,$z:${kcl.realwayfinder.nav.RouteMemory.revision(x, z)}"
		if (jobKey == key) return
		jobKey = key
		job = Job.Walk(x, y, z)
		fill = null
		held = null
	}

	/** Pick the fishing spot and, when it is loaded, the dry cast cell. Baritone does the walking. */
	fun chooseFish(level: ClientLevel, player: Player): PlannedRoute {
		val here = FishingWorld.current ?: return emptyPlan("不在钓鱼岛")
		val grotto = here.kind == SpotKind.GROTTO
		if (grotto && kcl.realwayfinder.nav.grottoNeedsScan(here.shortId, RouteSettings.scanned, IslandCycle.armedScan)) {
			return emptyPlan("扫点未完成")
		}
		if (!grotto) {
			val hold = islandHold(here)
			if (hold != null) return emptyPlan(hold)
		}
		val ranked = if (grotto) {
			rankGrotto(here, RouteSettings.grottoOrder, RouteSettings.grottoTargets)
		} else {
			rankIsland(here, RouteSettings.fishTargets, RouteSettings.fishOrder)
		}
		val spot = (if (grotto) firstOpen(level, ranked) else ranked.firstOrNull())
			?: return emptyPlan(if (grotto) "没有 grotto 点" else "没有鱼点")
		val label = spot.displayTitle()
		val bias = biasLabel(spot)
		val spotPos = BlockPos(spot.x, spot.y.coerceIn(level.minY, level.maxY - 1), spot.z)
		if (!level.isLoaded(spotPos)) {
			return PlannedRoute(label, bias, null, null, "靠近鱼点", spot.x, spot.y, spot.z, stand = landToward(level, player, spot))
		}
		return shorePlan(level, player, spot, label, bias)
	}

	/**
	 * A dry land cell toward the spot. The fishing column itself is never the goal.
	 * Baritone walks here until the real cast stance can be sampled.
	 */
	private fun landToward(level: ClientLevel, player: Player, spot: FishingSpot): Node? {
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val dist = hypot(spot.x + 0.5 - player.x, spot.z + 0.5 - player.z)
		val y = py.coerceIn(level.minY, level.maxY - 1)
		val aim = kcl.realwayfinder.nav.loadedApproach(px, pz, spot.x, spot.z, dist.coerceAtLeast(8.0)) { x, z ->
			level.isLoaded(BlockPos(x, y, z))
		}
		return pickLand(level, player, spot, aim.first, py, aim.second, 4.0)
			?: pickLand(level, player, spot, px, py, pz, 2.0)
	}

	private fun pickLand(
		level: ClientLevel,
		player: Player,
		spot: FishingSpot,
		cx: Int,
		cy: Int,
		cz: Int,
		progress: Double
	): Node? {
		val px = floor(player.x).toInt()
		val pz = floor(player.z).toInt()
		val playerDist = hypot(spot.x + 0.5 - player.x, spot.z + 0.5 - player.z)
		val grid = sampleBox(level, cx, cy, cz, 12, 24, 16)
		var best: Node? = null
		var bestScore = Double.MAX_VALUE
		for (dx in -12..12) {
			for (dz in -12..12) {
				val x = cx + dx
				val z = cz + dz
				if (x == spot.x && z == spot.z) continue
				if (x == px && z == pz) continue
				for (dy in -16..12) {
					val y = cy + dy
					if (!isLand(grid, x, y, z)) continue
					val nodeDist = hypot(spot.x + 0.5 - (x + 0.5), spot.z + 0.5 - (z + 0.5))
					if (nodeDist >= playerDist - progress) continue
					val score = nodeDist + abs(y - cy) * 0.25
					if (score < bestScore) {
						bestScore = score
						best = Node(x, y, z, Mode.LAND)
					}
				}
			}
		}
		return best
	}

	/**
	 * The spot chunk is loaded. Baritone walks to the nearest standable land
	 * (full block, lily, or slab). The water cell is not the goal.
	 * A cast solution is attached only when that same cell passes the existing cast rules.
	 */
	private fun shorePlan(level: ClientLevel, player: Player, spot: FishingSpot, label: String, bias: String): PlannedRoute {
		val look = displayPoint(level, spot)
		val sx = floor(look.x).toInt()
		val sy = floor(look.y).toInt()
		val sz = floor(look.z).toInt()
		val grid = sampleBox(level, sx, sy, sz, 24, 16, 16)
		val water = waterTarget(grid, look.x, look.y, look.z)
		val solved = findCasts(grid, water.x, water.y, water.z, look.x, look.y, look.z)
		val visible = solved.filter { seesDisplay(level, player, it.stance, look) }
		val open = (if (visible.isNotEmpty()) visible else solved)
			.filter { castKey(it.stance) !in RouteRuntime.avoidCasts }
			.filter { "${it.stance.x},${it.stance.z}" !in RouteRuntime.failedColumns }
		val wx = floor(water.x).toInt()
		val wy = floor(water.y).toInt()
		val wz = floor(water.z).toInt()
		val casts = (open.ifEmpty { if (visible.isNotEmpty()) visible else solved }).filter {
			nearPlannedEnd(it.stance, wx, wy, wz, RouteSettings.arriveX, RouteSettings.arriveZ, RouteSettings.arriveY)
		}
		val stand = fishingStand(level, spot)
		val matched = stand?.let { node ->
			casts.firstOrNull { it.stance.x == node.x && it.stance.z == node.z && abs(it.stance.y - node.y) <= 1 }
		}
		val status = if (stand != null) "完整" else "靠近鱼点"
		return PlannedRoute(label, bias, null, matched, status, spot.x, spot.y, spot.z, casts, water.x, water.z, stand = stand)
	}

	/**
	 * Nearest feet cell within the configured spot box.
	 * The cell is the top of a full block, a top slab, a bottom slab, a lily, or moss carpet.
	 * A lantern in the feet cell, the head cell, or the block under the feet is rejected.
	 */
	private fun fishingStand(level: ClientLevel, spot: FishingSpot): Node? {
		val dxMax = RouteSettings.standX.coerceIn(0, 64)
		val dyMax = RouteSettings.standY.coerceIn(0, 32)
		val dzMax = RouteSettings.standZ.coerceIn(0, 64)
		var best: Node? = null
		var bestDist = Int.MAX_VALUE
		var bestDy = Int.MAX_VALUE
		for (dx in -dxMax..dxMax) {
			for (dz in -dzMax..dzMax) {
				val dist = dx * dx + dz * dz
				if (dist > bestDist) continue
				val x = spot.x + dx
				val z = spot.z + dz
				for (dy in -dyMax..dyMax) {
					val y = spot.y + dy
					if ("$x,$y,$z" in RouteRuntime.droppedStances["${spot.x},${spot.y},${spot.z}"].orEmpty()) continue
					if (!fishingSurface(level, x, y, z)) continue
					if (!openAirBetween(x, y, z, spot.x, spot.y, spot.z) { bx, by, bz ->
						level.getBlockState(BlockPos(bx, by, bz)).isAir
					}) continue
					val ady = abs(dy)
					if (dist < bestDist || (dist == bestDist && ady < bestDy)) {
						bestDist = dist
						bestDy = ady
						best = Node(x, y, z, Mode.LAND)
					}
				}
			}
		}
		return best
	}

	private fun fishingSurface(level: ClientLevel, x: Int, y: Int, z: Int): Boolean {
		val feet = level.getBlockState(BlockPos(x, y, z))
		val head = level.getBlockState(BlockPos(x, y + 1, z))
		val below = level.getBlockState(BlockPos(x, y - 1, z))
		if (isLantern(feet) || isLantern(head) || isLantern(below)) return false
		if (!headRoom(head)) return false
		if (feet.`is`(Blocks.LILY_PAD) || isBottomSlab(feet)) return true
		if (!feet.isAir && !feet.`is`(Blocks.MOSS_CARPET)) return false
		return topSurface(level, BlockPos(x, y - 1, z), below)
	}

	private fun headRoom(state: BlockState): Boolean {
		if (isLantern(state)) return false
		if (state.isAir) return true
		return !state.blocksMotion()
	}

	private fun isLantern(state: BlockState): Boolean = state.block is LanternBlock

	private fun isBottomSlab(state: BlockState): Boolean =
		state.block is SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM

	private fun topSurface(level: ClientLevel, pos: BlockPos, state: BlockState): Boolean {
		if (state.isAir || isLantern(state) || state.`is`(Blocks.LILY_PAD) || state.`is`(Blocks.MOSS_CARPET)) return false
		if (state.block is SlabBlock) return state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM
		if (!state.blocksMotion()) return false
		val shape = state.getCollisionShape(level, pos)
		if (shape.isEmpty) return false
		return shape.max(Direction.Axis.Y) >= 0.999 && shape.min(Direction.Axis.Y) <= 0.01
	}

	/** Aim from where the player is standing toward the water under the spot. No cell eligibility check. */
	fun aimAtSpot(level: ClientLevel, player: Player, plan: PlannedRoute): CastSolution? {
		val fx = floor(player.x).toInt()
		val fy = floor(player.y + 1.0e-4).toInt()
		val fz = floor(player.z).toInt()
		val stance = Node(fx, fy, fz, Mode.LAND)
		val spot = SpotPool.all().firstOrNull { it.x == plan.spotX && it.y == plan.spotY && it.z == plan.spotZ }
		val look = if (spot != null) displayPoint(level, spot) else Vec3(plan.spotX + 0.5, plan.spotY + 0.5, plan.spotZ + 0.5)
		if (!look.x.isFinite() || !look.y.isFinite() || !look.z.isFinite()) return null
		val lx = floor(look.x).toInt()
		val ly = floor(look.y).toInt()
		val lz = floor(look.z).toInt()
		if (kotlin.math.abs(lx - fx) > 48 || kotlin.math.abs(ly - fy) > 32 || kotlin.math.abs(lz - fz) > 48) return null
		val grid = readGrid(
			level,
			min(fx, lx) - 3,
			(min(fy, ly) - 4).coerceAtLeast(level.minY),
			min(fz, lz) - 3,
			max(fx, lx) + 3,
			(max(fy, ly) + 4).coerceAtMost(level.maxY - 1),
			max(fz, lz) + 3
		)
		val water = waterTarget(grid, look.x, look.y, look.z)
		val eyeX = player.x
		val eyeY = player.eyeY
		val eyeZ = player.z
		val tried = RouteRuntime.triedAngles["${stance.x},${stance.y},${stance.z}"].orEmpty()
		val solved = solveCastFrom(grid, stance, eyeX, eyeY, eyeZ, water.x, water.z, tried) ?: return null
		return solved.copy(landX = water.x, landZ = water.z)
	}

	fun requestFish(level: ClientLevel, player: Player) {
		val here = FishingWorld.current ?: return
		val grotto = here.kind == SpotKind.GROTTO
		if (grotto && kcl.realwayfinder.nav.grottoNeedsScan(here.shortId, RouteSettings.scanned, IslandCycle.armedScan)) {
			val key = "f:wait:扫点未完成"
			if (jobKey == key) return
			jobKey = key
			job = Job.Empty("扫点未完成")
			fill = null
			held = null
			return
		}
		if (!grotto) {
			val hold = islandHold(here)
			if (hold != null) {
				val key = "f:wait:$hold"
				if (jobKey == key) return
				jobKey = key
				job = Job.Empty(hold)
				fill = null
				held = null
				return
			}
		}
		val ranked = if (grotto) {
			rankGrotto(here, RouteSettings.grottoOrder, RouteSettings.grottoTargets)
		} else {
			rankIsland(here, RouteSettings.fishTargets, RouteSettings.fishOrder)
		}
		val spot = if (grotto) firstOpen(level, ranked) else ranked.firstOrNull()
		val key = if (spot == null) {
			"f:none"
		} else {
			val box = aimBox(player, spot, level)
			"f:${spot.x},${spot.y},${spot.z}:${box.ax},${box.az}:${RouteRuntime.avoidCasts.hashCode()}:${RouteRuntime.failedColumns.hashCode()}:${RouteRuntime.skippedSpots.hashCode()}:${kcl.realwayfinder.nav.RouteMemory.revision(spot.x, spot.z)}"
		}
		if (jobKey == key) return
		jobKey = key
		job = if (spot == null) Job.Empty(if (grotto) "没有 grotto 点" else "没有鱼点") else Job.Fish(spot)
		fill = null
		held = null
	}

	/** One slice of the current request. Null until the route is ready. */
	fun pump(level: ClientLevel, player: Player): Pump? {
		val current = job ?: return null
		if (current is Job.Empty) {
			clearJob()
			return Pump.Fish(emptyPlan(current.status))
		}
		val grid = held ?: nextGrid(level, player, current) ?: return null
		if (held == null) {
			held = grid
			return null
		}
		if (path == null) {
			val staged = stage(grid, level, player, current)
			if (staged.second == null) {
				val ready = staged.first
				clearJob()
				return when (current) {
					is Job.Walk -> Pump.Walk(null)
					is Job.Fish -> Pump.Fish(ready ?: emptyPlan("没有鱼点"))
					is Job.Empty -> Pump.Fish(emptyPlan(current.status))
				}
			}
			draft = staged.first
			path = staged.second
			return null
		}
		if (path?.step(2_000) != true) return null
		val route = path?.route
		val base = draft
		clearJob()
		return when (current) {
			is Job.Walk -> Pump.Walk(route)
			is Job.Fish -> {
				val status = when {
					base?.cast != null && route?.complete == true -> "完整"
					base?.cast != null -> "路径不完整"
					else -> "靠近鱼点"
				}
				Pump.Fish((base ?: emptyPlan("没有鱼点")).copy(route = route, status = status))
			}
			is Job.Empty -> Pump.Fish(emptyPlan(current.status))
		}
	}

	fun cancel() {
		clearJob()
	}

	private fun clearJob() {
		job = null
		jobKey = ""
		fill = null
		held = null
		path = null
		draft = null
	}

	/** A search to run in slices, or a finished result when no search is needed. */
	private fun stage(
		grid: Grid,
		level: ClientLevel,
		player: Player,
		current: Job
	): Pair<PlannedRoute?, kcl.realwayfinder.nav.PathSearch?> {
		return when (current) {
			is Job.Walk -> {
				val px = floor(player.x).toInt()
				val py = floor(player.y + 1.0e-4).toInt()
				val pz = floor(player.z).toInt()
				val start = anchor(grid, px, py, pz) ?: return null to null
				val goals = walkGoals(grid, player, current.x, current.y, current.z)
				if (goals.isEmpty()) return null to null
				null to kcl.realwayfinder.nav.PathSearch(grid, start, goals, 20_000, remembered(current.x, current.z), null)
			}
			is Job.Fish -> stageFish(grid, level, player, current.spot)
			is Job.Empty -> emptyPlan(current.status) to null
		}
	}

	private fun nextGrid(level: ClientLevel, player: Player, current: Job): Grid? {
		val active = fill
		if (active == null) {
			val box = when (current) {
				is Job.Walk -> walkBox(player, current.x, current.y, current.z)
				is Job.Fish -> aimBox(player, current.spot, level)
				is Job.Empty -> return null
			}
			fill = Fill(level, box)
		}
		val ready = fill?.step(FILL_BUDGET)
		if (ready != null) fill = null
		return ready
	}

	private fun walkGoals(grid: Grid, player: Player, x: Int, y: Int, z: Int): Set<Node> {
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val dist = hypot(x + 0.5 - player.x, z + 0.5 - player.z)
		val goals = LinkedHashSet<Node>()
		if (dist > APPROACH_REACH) {
			val (gx, gz) = approachPoint(px, pz, x, z, APPROACH_REACH)
			for (dx in -6..6) for (dz in -6..6) for (dy in -8..8) {
				val cx = gx + dx
				val cy = py + dy
				val cz = gz + dz
				if (isLand(grid, cx, cy, cz)) goals.add(Node(cx, cy, cz, Mode.LAND))
			}
		} else {
			goals.addAll(kcl.realwayfinder.nav.scanStandGoals(x, y, z) { cx, cy, cz -> isLand(grid, cx, cy, cz) })
		}
		return goals
	}

	private fun stageFish(
		grid: Grid,
		level: ClientLevel,
		player: Player,
		spot: FishingSpot
	): Pair<PlannedRoute?, kcl.realwayfinder.nav.PathSearch?> {
		val label = spot.displayTitle()
		val bias = biasLabel(spot)
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val flat = hypot(spot.x + 0.5 - player.x, spot.z + 0.5 - player.z)
		val spotPos = BlockPos(spot.x, spot.y.coerceIn(level.minY, level.maxY - 1), spot.z)
		if (flat > APPROACH_REACH || !level.isLoaded(spotPos)) {
			return stageApproach(grid, level, player, spot, label, bias, px, py, pz)
		}
		val look = displayPoint(level, spot)
		val water = waterTarget(grid, look.x, look.y, look.z)
		val solved = findCasts(grid, water.x, water.y, water.z, look.x, look.y, look.z)
		val visible = solved.filter { seesDisplay(level, player, it.stance, look) }
		val open = (if (visible.isNotEmpty()) visible else solved)
			.filter { castKey(it.stance) !in RouteRuntime.avoidCasts }
			.filter { "${it.stance.x},${it.stance.z}" !in RouteRuntime.failedColumns }
		val wx = floor(water.x).toInt()
		val wy = floor(water.y).toInt()
		val wz = floor(water.z).toInt()
		val casts = (open.ifEmpty { if (visible.isNotEmpty()) visible else solved }).filter {
			nearPlannedEnd(it.stance, wx, wy, wz, RouteSettings.arriveX, RouteSettings.arriveZ, RouteSettings.arriveY)
		}
		val start = anchor(grid, px, py, pz)
			?: return PlannedRoute(label, bias, null, null, "脚下没有可站位置", spot.x, spot.y, spot.z) to null
		val goal = if (casts.isNotEmpty()) {
			casts.minWith(compareBy(
				{ (it.stance.x - wx) * (it.stance.x - wx) + (it.stance.z - wz) * (it.stance.z - wz) },
				{ abs(it.stance.y - wy) }
			)).stance
		} else {
			nearestStand(grid, wx, wy, wz)
		}
		if (goal == null) {
			return stageApproach(grid, level, player, spot, label, bias, px, py, pz)
		}
		val best = casts.firstOrNull { it.stance == goal }
		val draft = PlannedRoute(label, bias, null, best, "靠近鱼点", spot.x, spot.y, spot.z, casts, water.x, water.z)
		val search = kcl.realwayfinder.nav.PathSearch(grid, start, setOf(goal), 20_000, remembered(spot.x, spot.z))
		return draft to search
	}

	private fun stageApproach(
		grid: Grid,
		level: ClientLevel,
		player: Player,
		spot: FishingSpot,
		label: String,
		bias: String,
		px: Int,
		py: Int,
		pz: Int
	): Pair<PlannedRoute?, kcl.realwayfinder.nav.PathSearch?> {
		val aim = loadedApproach(level, px, py, pz, spot.x, spot.z)
		val goals = LinkedHashSet<Node>()
		for (dx in -6..6) for (dz in -6..6) for (dy in -8..8) {
			val x = aim.first + dx
			val y = py + dy
			val z = aim.second + dz
			if (isLand(grid, x, y, z)) goals.add(Node(x, y, z, Mode.LAND))
		}
		val draft = PlannedRoute(label, bias, null, null, "靠近鱼点", spot.x, spot.y, spot.z)
		val start = anchor(grid, px, py, pz) ?: return draft to null
		if (goals.isEmpty()) return draft to null
		return draft to kcl.realwayfinder.nav.PathSearch(grid, start, goals, 20_000, remembered(spot.x, spot.z))
	}

	private fun finishWalk(grid: Grid, player: Player, x: Int, y: Int, z: Int): Route? {
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val start = anchor(grid, px, py, pz) ?: return null
		val dist = hypot(x + 0.5 - player.x, z + 0.5 - player.z)
		val goals = LinkedHashSet<Node>()
		if (dist > APPROACH_REACH) {
			val (gx, gz) = approachPoint(px, pz, x, z, APPROACH_REACH)
			for (dx in -6..6) for (dz in -6..6) for (dy in -8..8) {
				val cx = gx + dx
				val cy = py + dy
				val cz = gz + dz
				if (isLand(grid, cx, cy, cz)) goals.add(Node(cx, cy, cz, Mode.LAND))
			}
		} else {
			goals.addAll(kcl.realwayfinder.nav.scanStandGoals(x, y, z) { cx, cy, cz -> isLand(grid, cx, cy, cz) })
		}
		if (goals.isEmpty()) return null
		return findPath(grid, start, goals, limit = 40_000, penalty = remembered(x, z))
	}

	/**
	 * Path toward the spot, at most [APPROACH_REACH] blocks.
	 * Unloaded chunks stay out of the goal set, so the route ends on the last loaded ground
	 * and the next plan continues once the player has walked into view.
	 */
	private fun approach(
		level: ClientLevel,
		spot: FishingSpot,
		label: String,
		biasLabel: String,
		px: Int,
		py: Int,
		pz: Int,
		prepared: Grid? = null
	): PlannedRoute {
		val aim = loadedApproach(level, px, py, pz, spot.x, spot.z)
		val grid = prepared ?: sample(level, px, py, pz, aim.first, py, aim.second)
		val start = anchor(grid, px, py, pz)
			?: return PlannedRoute(label, biasLabel, null, null, "脚下没有可站位置", spot.x, spot.y, spot.z)
		val goals = LinkedHashSet<Node>()
		for (dx in -6..6) {
			for (dz in -6..6) {
				for (dy in -8..8) {
					val x = aim.first + dx
					val y = py + dy
					val z = aim.second + dz
					if (isLand(grid, x, y, z)) goals.add(Node(x, y, z, Mode.LAND))
				}
			}
		}
		if (goals.isEmpty()) {
			return PlannedRoute(label, biasLabel, null, null, "靠近鱼点", spot.x, spot.y, spot.z)
		}
		val route = findPath(grid, start, goals, penalty = remembered(spot.x, spot.z))
		return PlannedRoute(label, biasLabel, route, null, "靠近鱼点", spot.x, spot.y, spot.z)
	}

	private fun sampleBox(level: ClientLevel, x: Int, y: Int, z: Int, xz: Int, down: Int, up: Int): Grid {
		val minX = x - xz
		val maxX = x + xz
		val minZ = z - xz
		val maxZ = z + xz
		val minY = (y - down).coerceAtLeast(level.minY)
		val maxY = (y + up).coerceAtMost(level.maxY - 1)
		return readGrid(level, minX, minY, minZ, maxX, maxY, maxZ)
	}

	private fun sample(level: ClientLevel, px: Int, py: Int, pz: Int, sx: Int, sy: Int, sz: Int): Grid {
		val minX = min(px, sx) - 32
		val maxX = max(px, sx) + 32
		val minZ = min(pz, sz) - 32
		val maxZ = max(pz, sz) + 32
		val minY = (min(py, sy) - 48).coerceAtLeast(level.minY)
		val maxY = (max(py, sy) + 32).coerceAtMost(level.maxY - 1)
		return readGrid(level, minX, minY, minZ, maxX, maxY, maxZ)
	}

	private fun readGrid(level: ClientLevel, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int): Grid {
		val sizeX = (maxX.toLong() - minX.toLong() + 1L).toInt()
		val sizeY = (maxY.toLong() - minY.toLong() + 1L).toInt()
		val sizeZ = (maxZ.toLong() - minZ.toLong() + 1L).toInt()
		if (kcl.realwayfinder.nav.Grid.cellCount(sizeX, sizeY, sizeZ) == null) {
			return Grid.build(minX, minY, minZ, 1, 1, 1) { _, _, _ -> Cell.UNKNOWN }
		}
		val chunks = HashMap<Long, Boolean>()
		return Grid.build(minX, minY, minZ, sizeX, sizeY, sizeZ) { x, y, z ->
			val key = (x shr 4).toLong() shl 32 or ((z shr 4).toLong() and 0xffffffffL)
			val loaded = chunks.getOrPut(key) { level.isLoaded(BlockPos(x, minY, z)) }
			if (!loaded) Cell.UNKNOWN else cellAt(level, x, y, z)
		}
	}

	private fun cellAt(level: ClientLevel, x: Int, y: Int, z: Int): Cell {
		val pos = BlockPos(x, y, z)
		val state = level.getBlockState(pos)
		if (state.isAir) return Cell.AIR
		if (state.`is`(Blocks.LILY_PAD)) return Cell.LILY
		val fluid = state.fluidState
		val water = !fluid.isEmpty && fluid.`is`(FluidTags.WATER)
		if (water && !fluid.isSource) return Cell.FLOW
		// Plants and open water have no collision. Skip the shape build; that call is what freezes the client.
		if (!state.blocksMotion()) return if (water) Cell.WATER else Cell.AIR
		val shape = state.getCollisionShape(level, pos)
		if (shape.isEmpty) return if (water) Cell.WATER else Cell.AIR
		val top = shape.max(Direction.Axis.Y)
		if (top > 1.01) return Cell.TALL
		if (top <= 0.6 && shape.min(Direction.Axis.Y) < 0.1) return Cell.SLAB
		return Cell.SOLID
	}

	/** The water point directly under the text display. X and Z stay on the display. */
	private fun waterTarget(grid: Grid, x: Double, y: Double, z: Double): Vec3 {
		val ix = floor(x).toInt()
		val iz = floor(z).toInt()
		val top = kcl.realwayfinder.nav.topWater(grid, ix, iz)
		if (top != null) return Vec3(x, top + 1.0, z)
		var bestY = y
		var bestD = Double.MAX_VALUE
		for (dx in -1..1) {
			for (dz in -1..1) {
				val near = kcl.realwayfinder.nav.topWater(grid, ix + dx, iz + dz) ?: continue
				val dist = hypot((ix + dx + 0.5) - x, (iz + dz + 0.5) - z)
				if (dist < bestD) {
					bestD = dist
					bestY = near + 1.0
				}
			}
		}
		return Vec3(x, bestY, z)
	}

	private fun displayPoint(level: ClientLevel, spot: FishingSpot): Vec3 {
		val entity = level.getEntity(spot.entityId)
		if (entity != null) return entity.boundingBox.center
		return Vec3(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5)
	}

	/** Collider ray from the standing eye to the text display. Water is ignored. */
	private fun seesDisplay(level: ClientLevel, player: Player, stance: Node, target: Vec3): Boolean {
		val eye = Vec3(stance.x + 0.5, stance.y + 1.62, stance.z + 0.5)
		val hit = level.clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
		if (hit.type == HitResult.Type.MISS) return true
		return hit.blockPos == BlockPos.containing(target)
	}

	private fun emptyPlan(status: String) = PlannedRoute("", "", null, null, status, 0, 0, 0)

	private fun remembered(goalX: Int, goalZ: Int): (kcl.realwayfinder.nav.Node, kcl.realwayfinder.nav.Node) -> Double =
		{ from, to -> kcl.realwayfinder.nav.RouteMemory.penalty(goalX, goalZ, from.x, from.z, to.x, to.z) }

	private const val APPROACH_REACH = 96.0
	private const val FILL_BUDGET = 24_000

	private var job: Job? = null
	private var jobKey = ""
	private var fill: Fill? = null
	private var held: Grid? = null
	private var path: kcl.realwayfinder.nav.PathSearch? = null
	private var draft: PlannedRoute? = null

	private sealed interface Job {
		class Walk(val x: Int, val y: Int, val z: Int) : Job
		class Fish(val spot: FishingSpot) : Job
		class Empty(val status: String) : Job
	}

	private class Fill(val level: ClientLevel, box: Box) {
		val minX = box.minX
		val minY = box.minY
		val minZ = box.minZ
		val sizeX = box.sizeX
		val sizeY = box.sizeY
		val sizeZ = box.sizeZ
		val cells = ByteArray(sizeX * sizeY * sizeZ)
		var index = 0

		fun step(budget: Int): Grid? {
			val end = min(cells.size, index + budget)
			val span = sizeX * sizeZ
			while (index < end) {
				val x = minX + index % sizeX
				val z = minZ + (index / sizeX) % sizeZ
				val y = minY + index / span
				cells[index] = cellAt(level, x, y, z).code
				index++
			}
			if (index < cells.size) return null
			return Grid(minX, minY, minZ, sizeX, sizeY, sizeZ, cells)
		}
	}

	private data class Box(
		val minX: Int,
		val minY: Int,
		val minZ: Int,
		val sizeX: Int,
		val sizeY: Int,
		val sizeZ: Int,
		val ax: Int,
		val az: Int
	)

	private fun walkBox(player: Player, x: Int, y: Int, z: Int): Box {
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val dist = hypot(x + 0.5 - player.x, z + 0.5 - player.z).coerceAtLeast(1.0)
		val (ax, az) = if (dist > APPROACH_REACH) approachPoint(px, pz, x, z, APPROACH_REACH) else x to z
		val ay = if (dist > APPROACH_REACH) py + ((y - py) * (APPROACH_REACH / dist)).toInt() else y
		return box(px, py, pz, ax, ay, az)
	}

	private fun aimBox(player: Player, spot: FishingSpot, level: ClientLevel): Box {
		val px = floor(player.x).toInt()
		val py = floor(player.y + 1.0e-4).toInt()
		val pz = floor(player.z).toInt()
		val dist = hypot(spot.x + 0.5 - player.x, spot.z + 0.5 - player.z)
		val loaded = level.isLoaded(BlockPos(spot.x, spot.y.coerceIn(level.minY, level.maxY - 1), spot.z))
		val far = dist > APPROACH_REACH || !loaded
		val (ax, az) = if (far) loadedApproach(level, px, py, pz, spot.x, spot.z) else spot.x to spot.z
		val ay = if (far) py + ((spot.y - py) * (APPROACH_REACH / dist.coerceAtLeast(1.0))).toInt() else spot.y
		return box(px, py, pz, ax, ay, az)
	}

	private fun box(px: Int, py: Int, pz: Int, sx: Int, sy: Int, sz: Int): Box {
		val minX = min(px, sx) - 16
		val maxX = max(px, sx) + 16
		val minZ = min(pz, sz) - 16
		val maxZ = max(pz, sz) + 16
		val minY = min(py, sy) - 24
		val maxY = max(py, sy) + 16
		return Box(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, sx, sz)
	}

	/** While another island is the duty, or every enabled island is finished, do not pick a spot here. */
	private fun islandHold(here: kcl.spotfilter.client.data.FishingPlace): String? {
		val order = IslandCycle.enabledOrder()
		if (order.isEmpty()) return null
		val pick = pickIsland(order, RouteSettings.scanned, RouteSettings.finished)
			?: return "启用岛屿都已钓完"
		if (here.shortId.equals(pick.id, ignoreCase = true)) return null
		return if (pick.job == kcl.realwayfinder.nav.IslandJob.SCAN) "前往 ${pick.id} 扫点" else "前往 ${pick.id} 钓鱼"
	}

	private fun loadedApproach(level: ClientLevel, px: Int, py: Int, pz: Int, sx: Int, sz: Int): Pair<Int, Int> {
		val y = py.coerceIn(level.minY, level.maxY - 1)
		return kcl.realwayfinder.nav.loadedApproach(px, pz, sx, sz, APPROACH_REACH) { x, z ->
			level.isLoaded(BlockPos(x, y, z))
		}
	}

	private fun biasLabel(spot: FishingSpot): String {
		val here = FishingWorld.current
		return if (here?.kind == SpotKind.GROTTO) "Grotto" else RouteSettings.fishTargets.joinToString("/") { it.name.lowercase() }
	}
}

sealed interface Pump {
	class Walk(val route: Route?) : Pump
	class Fish(val plan: PlannedRoute) : Pump
}

internal fun castKey(node: Node) = "${node.x},${node.y},${node.z}"

fun PlannedRoute.spotStillThere(): Boolean {
	if (spotLabel.isEmpty()) return true
	val here = FishingWorld.current ?: return false
	return SpotPool.all().any { spot ->
		spot.x == spotX && spot.y == spotY && spot.z == spotZ &&
			spot.place == here &&
			spot.stock != StockLevel.DEPLETED
	}
}
