package kcl.realwayfinder.nav

import kcl.realwayfinder.RouteLock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTest {
	@Test
	fun completeRouteStaysUntilStuckOrInvalid() {
		assertTrue(!RouteLock.shouldReplace(true, true, false, 199, false, false))
		assertTrue(RouteLock.shouldReplace(true, true, false, 200, false, false))
		assertTrue(!RouteLock.shouldReplace(true, true, false, 200, true, false))
		assertTrue(!RouteLock.shouldReplace(true, true, false, 200, false, true))
		assertTrue(RouteLock.shouldReplace(true, false, false, 0, false, false))
		assertTrue(RouteLock.shouldReplace(true, true, true, 0, true, true))
		assertTrue(RouteLock.shouldReplace(false, false, false, 0, true, true))
		assertTrue(!RouteLock.shouldReplace(false, true, false, 200, false, false))
	}

	@Test
	fun waterSagWithinOneBlockStaysOnRoute() {
		val track = listOf(
			TrackPoint(0.5, 64.0, 0.5, true),
			TrackPoint(8.5, 64.0, 0.5, true)
		)
		assertTrue(!offRoute(4.0, 63.1, 0.5, track))
		assertTrue(offRoute(4.0, 62.5, 0.5, track))
		assertTrue(!offRoute(4.0, 64.0, 3.5, track))
		assertTrue(offRoute(4.0, 64.0, 6.0, track))
	}

	@Test
	fun grottoPrefersLowCostThenTypeThenBonus() {
		val lowMagnet = GrottoRank(0, 0, 10)
		val lowElusive = GrottoRank(0, 1, 90)
		val highMagnet = GrottoRank(2, 0, 90)
		val lowMagnetRich = GrottoRank(0, 0, 40)
		assertTrue(grottoPrefer(lowMagnet, highMagnet))
		assertTrue(grottoPrefer(lowMagnet, lowElusive))
		assertTrue(grottoPrefer(lowMagnetRich, lowMagnet))
		assertTrue(!grottoPrefer(highMagnet, lowElusive))
	}

	@Test
	fun normalPriorityBeatsAHigherLowerType() {
		val order = FishTarget.entries
		val enabled = setOf(FishTarget.WISE, FishTarget.PEARL, FishTarget.WAYFINDER)
		val wise = normalRank(10, 15, -1, 50, 80, -1, -1, order, enabled)
		val pearl = normalRank(-1, -1, -1, -1, 90, -1, -1, order, enabled)
		val richerWise = normalRank(10, 40, -1, -1, -1, -1, -1, order, enabled)
		assertNotNull(wise)
		assertNotNull(pearl)
		assertNotNull(richerWise)
		assertTrue(normalPrefer(wise, pearl))
		assertTrue(normalPrefer(richerWise, wise))
		assertNull(normalRank(10, 40, 5, 8, 80, 1, -1, order, setOf(FishTarget.SPIRIT)))
	}

	@Test
	fun hourlyClearMatchesSpotFilterAndServerText() {
		assertTrue(hourlyClear("clear spots"))
		assertTrue(hourlyClear("SpotFilter: island spots refreshed"))
		assertTrue(!hourlyClear("SpotFilter: grotto spots refreshed"))
	}

	@Test
	fun slabIsAWalkableStep() {
		val grid = Grid.build(0, 0, 0, 4, 4, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 -> Cell.SOLID
				x == 2 && y == 1 -> Cell.SLAB
				else -> Cell.AIR
			}
		}
		assertTrue(isLand(grid, 1, 1, 1))
		assertTrue(isLand(grid, 2, 1, 1))
		val route = findPath(grid, Node(0, 1, 1, Mode.LAND), setOf(Node(2, 1, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.none { it.kind == EdgeKind.JUMP || it.kind == EdgeKind.SPRINT_JUMP })
	}

	@Test
	fun pathExecAdvancesWhenFeetEnterTheDest() {
		val steps = listOf(
			RouteStep(Node(0, 64, 0, Mode.LAND), EdgeKind.WALK),
			RouteStep(Node(1, 64, 0, Mode.LAND), EdgeKind.WALK),
			RouteStep(Node(2, 65, 0, Mode.LAND), EdgeKind.JUMP)
		)
		val exec = PathExec()
		val first = exec.tick(steps, 0.5, 64.0, 0.5, true)
		assertTrue(first.forward)
		assertTrue(!first.jump)
		exec.tick(steps, 1.5, 64.0, 0.5, true)
		assertEquals(2, exec.index)
		val jump = exec.tick(steps, 1.2, 64.0, 0.5, true)
		assertTrue(jump.jump)
		val far = exec.tick(steps, 1.2, 64.0, 12.0, true)
		assertTrue(far.failed)
	}

	@Test
	fun floatHoldsJumpAndWalkDoesNot() {
		assertTrue(shouldJump(EdgeKind.FLOAT, 3.0, true, 0.0))
		assertTrue(!shouldJump(EdgeKind.WALK, 0.2, true, 0.0))
		assertTrue(shouldJump(EdgeKind.JUMP, 1.5, true, 1.0))
		assertTrue(!shouldJump(EdgeKind.JUMP, 2.0, true, 1.0))
	}

	@Test
	fun temperateDiscoveryTextStartsEntry() {
		assertTrue(temperateDiscovery("You've discovered a Temperate Grotto! Travel there through your navigator."))
		assertTrue(hubPrompt("Enter /hub to leave"))
		assertTrue(hubPrompt("§cEnter /hub"))
		assertTrue(!hubPrompt("Enter the grotto"))
		assertTrue(tropicalDiscovery("You've discovered a Tropical Grotto! Travel there through your navigator."))
		assertTrue(!temperateDiscovery("You've discovered a Tropical Grotto! Travel there through your navigator."))
		assertTrue(!tropicalDiscovery("You've discovered a Temperate Grotto! Travel there through your navigator."))
		assertTrue(grottoClosed("The grotto has closed."))
		assertTrue(grottoClosed("The Temperate Grotto collapsed."))
		assertTrue(!grottoClosed("You've discovered a Temperate Grotto! Travel there through your navigator."))
		assertTrue(!grottoClosed("SpotFilter: island spots refreshed"))
		assertTrue(!temperateDiscovery("SpotFilter: island spots refreshed"))
	}

	@Test
	fun grottoStartFollowsTheNearestMap() {
		val points = listOf(
			MapStart(-7446, -4385, -7446, 35, -4385),
			MapStart(12000, 4000, 12040, 40, 3980)
		)
		val near = matchMapStart(points, -7300.0, -4200.0)
		assertNotNull(near)
		assertEquals(-7446, near.x)
		assertNull(matchMapStart(points, 0.0, 0.0, maxDist = 500.0))
	}

	@Test
	fun fishRankPrefersSumThenShare() {
		val wise = FishBias.WISE
		val even = fishRank(50, 50, wise)
		val wiseHeavy = fishRank(40, 60, wise)
		val bigger = fishRank(60, 60, wise)
		assertNotNull(even)
		assertNotNull(wiseHeavy)
		assertNotNull(bigger)
		assertTrue(wiseHeavy > even)
		assertTrue(bigger > wiseHeavy)
		assertTrue(fishRank(70, 30, FishBias.STRONG)!! > fishRank(30, 70, FishBias.STRONG)!!)
		assertNull(fishRank(0, 0, wise))
	}

	@Test
	fun flatWalkReachesLandGoal() {
		val grid = flat(8, 1)
		val start = Node(0, 1, 0, Mode.LAND)
		val goal = Node(5, 1, 0, Mode.LAND)
		val route = findPath(grid, start, setOf(goal))
		assertNotNull(route)
		assertTrue(route.complete)
		assertEquals(goal, route.nodes.last())
		assertTrue(route.steps.drop(1).all { it.kind == EdgeKind.WALK })
	}

	@Test
	fun approachPointStopsShortOfAFarSpot() {
		assertEquals(80 to 0, approachPoint(0, 0, 200, 0, 80.0))
		assertEquals(50 to 0, approachPoint(0, 0, 50, 0, 80.0))
		assertEquals(40 to 30, approachPoint(0, 0, 80, 60, 50.0))
	}

	@Test
	fun openFieldPast150BlocksStillReaches() {
		val grid = Grid.build(0, 0, 0, 200, 4, 200) { _, y, _ ->
			if (y == 0) Cell.SOLID else Cell.AIR
		}
		val start = Node(1, 1, 1, Mode.LAND)
		val goal = Node(190, 1, 170, Mode.LAND)
		val route = findPath(grid, start, setOf(goal))
		assertNotNull(route)
		assertTrue(route.complete)
		assertEquals(goal, route.nodes.last())
	}

	@Test
	fun fallAnyHeightIsAllowed() {
		val grid = Grid.build(0, 0, 0, 4, 16, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x == 0 && y == 9 -> Cell.SOLID
				x == 1 && y == 1 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(0, 10, 1, Mode.LAND), setOf(Node(1, 2, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.any { it.kind == EdgeKind.FALL })
	}

	@Test
	fun oneBlockStepIsJumpAndTwoBlockWallIsNotAPath() {
		val step = Grid.build(0, 0, 0, 3, 6, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x == 0 && y == 0 -> Cell.SOLID
				x == 1 && y <= 1 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val jumped = findPath(step, Node(0, 1, 1, Mode.LAND), setOf(Node(1, 2, 1, Mode.LAND)))
		assertNotNull(jumped)
		assertTrue(jumped.complete)
		assertTrue(jumped.steps.any { it.kind == EdgeKind.JUMP })

		val wall = Grid.build(0, 0, 0, 4, 6, 3) { x, y, z ->
			when {
				y == 0 && (x == 0 || x == 2) && z == 1 -> Cell.SOLID
				x == 1 && y <= 2 -> Cell.SOLID
				z != 1 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val blocked = findPath(wall, Node(0, 1, 1, Mode.LAND), setOf(Node(2, 1, 1, Mode.LAND)))
		assertTrue(blocked == null || !blocked.complete)
	}

	@Test
	fun shallowWaterDoesNotSwimAndDeepWaterDoes() {
		val shallow = Grid.build(0, 0, 0, 1, 6, 1) { _, y, _ ->
			if (y <= 1) Cell.WATER else Cell.AIR
		}
		assertTrue(waterDepth(shallow, 0, 0) <= 2)
		assertTrue(isSwim(shallow, 0, 0, 0))
		assertTrue(!isSwim(shallow, 0, 1, 0))

		val deep = Grid.build(0, 0, 0, 1, 8, 1) { _, y, _ ->
			if (y <= 3) Cell.WATER else Cell.AIR
		}
		assertTrue(waterDepth(deep, 0, 0) > 2)
		assertTrue(isSwim(deep, 0, 2, 0))
		assertTrue(!isSwim(deep, 0, 3, 0))
	}

	@Test
	fun shoreAllowsFlushBankAndRejectsOneBlockLip() {
		val grid = Grid.build(0, 0, 0, 4, 8, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x == 1 && y <= 4 -> Cell.WATER
				x == 0 && y == 4 -> Cell.SOLID
				x == 2 && y <= 5 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val start = floatNode(grid, 1, 1)
		assertNotNull(start)
		val route = findPath(grid, start, setOf(Node(0, 5, 1, Mode.LAND), Node(2, 6, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertEquals(Node(0, 5, 1, Mode.LAND), route.nodes.last())
		assertTrue(route.steps.any { it.kind == EdgeKind.SHORE })
		assertTrue(route.steps.none { it.kind == EdgeKind.SWIM })
	}

	@Test
	fun escapeLeavesTheBlockedRouteInsideTheCube() {
		val grid = Grid.build(0, 0, 0, 3, 4, 3) { x, y, z ->
			when {
				z == 0 -> Cell.SOLID
				y == 0 -> Cell.SOLID
				x == 1 && z == 1 && y == 1 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val out = escapeStep(grid, Node(1, 1, 2, Mode.LAND), setOf(2 to 2))
		assertNotNull(out)
		assertTrue(kotlin.math.abs(out.x - 1) <= 1)
		assertTrue(kotlin.math.abs(out.y - 1) <= 1)
		assertTrue(kotlin.math.abs(out.z - 2) <= 1)
		assertTrue(out.x != 2 || out.z != 2)
	}

	@Test
	fun waterIsTheOnlyWayOffALonePad() {
		val lone = Grid.build(0, 0, 0, 3, 4, 3) { x, y, z ->
			when {
				x == 1 && z == 1 && y == 0 -> Cell.SOLID
				y == 0 -> Cell.WATER
				else -> Cell.AIR
			}
		}
		assertTrue(mustLeaveOnWater(lone, Node(1, 1, 1, Mode.LAND)))
		val shore = Grid.build(0, 0, 0, 3, 4, 3) { x, y, z ->
			when {
				y == 0 && x <= 1 -> Cell.SOLID
				y == 0 -> Cell.WATER
				else -> Cell.AIR
			}
		}
		assertTrue(!mustLeaveOnWater(shore, Node(0, 1, 1, Mode.LAND)))
	}

	@Test
	fun lilyPadIsStandableGround() {
		val grid = Grid.build(0, 0, 0, 5, 6, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x == 0 && y == 0 -> Cell.SOLID
				x >= 1 && y == 0 -> Cell.WATER
				x in 1..3 && y == 1 -> Cell.LILY
				else -> Cell.AIR
			}
		}
		assertTrue(isLand(grid, 2, 1, 1))
		assertTrue(!isLand(grid, 2, 2, 1))
		val route = findPath(grid, Node(0, 1, 1, Mode.LAND), setOf(Node(3, 1, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertEquals(Node(3, 1, 1, Mode.LAND), route.nodes.last())
		assertTrue(route.steps.none { it.kind == EdgeKind.SWIM })
	}

	@Test
	fun deepWaterShortcutLosesToALandDetour() {
		val grid = Grid.build(0, 0, 0, 9, 8, 5) { x, y, z ->
			when {
				z !in 0..4 -> Cell.SOLID
				z == 2 && x in 3..5 && y <= 4 -> Cell.WATER
				y == 0 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(1, 1, 2, Mode.LAND), setOf(Node(7, 1, 2, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.none { it.kind == EdgeKind.SWIM })
	}

	@Test
	fun landDetourBeatsASurfaceCrossing() {
		val grid = Grid.build(0, 0, 0, 10, 8, 5) { x, y, z ->
			when {
				z !in 0..2 -> Cell.SOLID
				z == 1 && x in 3..6 && y <= 3 -> Cell.WATER
				y <= 3 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(1, 4, 1, Mode.LAND), setOf(Node(8, 4, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.none { it.kind == EdgeKind.FLOAT || it.kind == EdgeKind.SWIM })
	}

	@Test
	fun crossesDeepWaterOnTheSurfaceInsteadOfDiving() {
		val grid = Grid.build(0, 0, 0, 10, 8, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x in 1..7 && y <= 3 -> Cell.WATER
				y <= 3 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(0, 4, 1, Mode.LAND), setOf(Node(8, 4, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.any { it.kind == EdgeKind.FLOAT })
		assertTrue(route.steps.none { it.kind == EdgeKind.SWIM })
		val floats = route.steps.filter { it.kind == EdgeKind.FLOAT }
		if (floats.size >= 2) {
			val dx = floats[1].node.x - floats[0].node.x
			val dz = floats[1].node.z - floats[0].node.z
			assertTrue(floats.zipWithNext().all { (a, b) -> b.node.x - a.node.x == dx && b.node.z - a.node.z == dz })
		}
	}

	@Test
	fun diveLooksAlongTheTunnelInsteadOfSurfacing() {
		val steps = listOf(
			RouteStep(Node(0, 4, 0, Mode.LAND), EdgeKind.WALK),
			RouteStep(Node(1, 2, 0, Mode.SWIM), EdgeKind.SWIM),
			RouteStep(Node(2, 2, 0, Mode.SWIM), EdgeKind.SWIM),
			RouteStep(Node(2, 2, 3, Mode.SWIM), EdgeKind.SWIM),
			RouteStep(Node(8, 4, 0, Mode.LAND), EdgeKind.SHORE)
		)
		assertTrue(plannedDive(steps, 1.2, 2.0, 0.2))
		val aim = straightAim(steps, 2)
		assertEquals(2.5, aim.first, 0.01)
		assertEquals(0.5, aim.second, 0.01)
		val order = PathExec().tick(steps, 1.2, 2.0, 0.2, false, 2.0, true)
		assertTrue(order.dive)
		assertTrue(order.pitch > 0f)
		val surface = listOf(
			RouteStep(Node(0, 5, 0, Mode.LAND), EdgeKind.WALK),
			RouteStep(Node(1, 5, 0, Mode.FLOAT), EdgeKind.FLOAT),
			RouteStep(Node(4, 5, 0, Mode.LAND), EdgeKind.SHORE)
		)
		assertTrue(!plannedDive(surface, 1.2, 5.0, 0.2))
	}

	@Test
	fun divesOnlyToPassUnderALowCeiling() {
		val grid = Grid.build(0, 0, 0, 12, 8, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				x in 1..10 && y <= 3 -> Cell.WATER
				x in 3..8 && y in 4..5 -> Cell.SOLID
				x == 0 && y <= 3 -> Cell.SOLID
				x == 11 && y <= 3 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(0, 4, 1, Mode.LAND), setOf(Node(11, 4, 1, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertTrue(route.steps.any { it.kind == EdgeKind.SWIM })
	}

	@Test
	fun interiorIgnoresTheSkyRoof() {
		val roof = SurfaceSample(70, false)
		val floor = SurfaceSample(41, false)
		val chosen = pickInterior(listOf(roof, floor), spawnY = 42)
		assertNotNull(chosen)
		assertEquals(41, chosen.y)
		assertNull(pickInterior(listOf(SurfaceSample(88, false)), spawnY = 42))
		val pond = pickInterior(listOf(SurfaceSample(81, true)), spawnY = 74)
		assertNotNull(pond)
		assertTrue(pond.water)
	}

	@Test
	fun aDrySpotGoesHomeThenIsSkipped() {
		assertEquals(BiteResult.WAIT, biteResult(0, 30 * 20 - 1, caught = false))
		assertEquals(BiteResult.HOME, biteResult(0, 30 * 20, caught = false))
		assertEquals(BiteResult.WAIT, biteResult(1, 40 * 20 - 1, caught = false))
		assertEquals(BiteResult.SKIP, biteResult(1, 40 * 20, caught = false))
		assertEquals(BiteResult.WAIT, biteResult(0, 30 * 20, caught = true))
	}

	@Test
	fun islandFiltersRequireHookSumAndOtherChances() {
		val hooks = setOf(FishTarget.WISE, FishTarget.ELUSIVE)
		assertTrue(islandSpotQualifies(20, 10, -1, -1, -1, -1, -1, hooks))
		assertTrue(islandSpotQualifies(0, 0, 5, -1, -1, -1, -1, hooks))
		assertTrue(!islandSpotQualifies(20, 0, -1, -1, -1, -1, -1, hooks))
		assertTrue(islandSpotQualifies(0, 0, -1, -1, 10, -1, -1, setOf(FishTarget.PEARL)))
		assertTrue(!islandSpotQualifies(0, 0, -1, -1, 10, -1, -1, setOf(FishTarget.STRONG)))
	}

	@Test
	fun waterBehindTheY50BorderIsNotScanned() {
		val cols = listOf(
			ScanCol(0, 0, 40, water = false),
			ScanCol(1, 0, 40, water = false),
			ScanCol(2, 0, 60, water = false),
			ScanCol(3, 0, 60, water = false),
			ScanCol(4, 0, 59, water = true),
			ScanCol(0, 2, 40, water = true)
		)
		val stops = grottoScanStops(ScanPoint(0, 40, 0), cols, 0, 4, 0, 2, radius = 60.0, maxStops = 4)
		assertEquals(1, stops.size)
		assertEquals(0, stops.first().x)
	}

	@Test
	fun grottoScanCoversShoreWaterFromSpawn() {
		val cols = ArrayList<ScanCol>()
		for (x in 0..100) {
			for (z in 0..30) {
				val pond = x in 90..94 && z in 10..14
				cols.add(ScanCol(x, z, if (pond) 11 else 12, water = pond))
			}
		}
		val stops = grottoScanStops(ScanPoint(0, 12, 0), cols, 0, 100, 0, 30, radius = 60.0, maxStops = 4)
		assertEquals(0, stops.first().x)
		assertTrue(stops.size >= 2)
		val stand = stops[1]
		val dx = (92 - stand.x).toDouble()
		val dz = (12 - stand.z).toDouble()
		assertTrue(dx * dx + dz * dz <= 60.0 * 60.0)
	}

	@Test
	fun fishingEndAllowsTheTouchingTwoByTwo() {
		val end = Node(4, 10, 6, Mode.LAND)
		assertTrue(nearPlannedEnd(end, 4, 10, 6))
		assertTrue(nearPlannedEnd(end, 5, 10, 7))
		assertTrue(nearPlannedEnd(end, 3, 10, 5))
		assertTrue(nearPlannedEnd(end, 4, 9, 6))
		assertTrue(nearPlannedEnd(end, 4, 11, 6))
		assertTrue(nearPlannedEnd(end, 4, 8, 6))
		assertTrue(nearPlannedEnd(end, 4, 12, 6))
		assertTrue(!nearPlannedEnd(end, 6, 10, 6))
		assertTrue(!nearPlannedEnd(end, 4, 7, 6))
		assertTrue(!nearPlannedEnd(end, 4, 13, 6))
		assertTrue(nearPlannedEnd(end, 7, 10, 6, xSlop = 3, zSlop = 1, ySlop = 2))
		assertTrue(!nearPlannedEnd(end, 7, 14, 6, xSlop = 3, zSlop = 1, ySlop = 2))
		val shore = Node(5, 10, 6, Mode.LAND)
		val far = Node(8, 10, 6, Mode.LAND)
		val spot = Node(4, 10, 6, Mode.LAND)
		assertTrue(nearPlannedEnd(shore, spot.x, spot.y, spot.z, xSlop = 1, zSlop = 1, ySlop = 2))
		assertTrue(!nearPlannedEnd(far, spot.x, spot.y, spot.z, xSlop = 1, zSlop = 1, ySlop = 2))
	}

	@Test
	fun fiveRepeatedAutoMovesGoHome() {
		val loop = AutoLoop()
		repeat(4) { assertTrue(!loop.note("3,4", jumpStart = true, sneakStart = false, moving = true)) }
		assertTrue(loop.note("3,4", jumpStart = true, sneakStart = false, moving = true))
		val circle = AutoLoop()
		val cells = listOf("0,0", "1,0", "1,1")
		repeat(4) { cells.forEach { assertTrue(!circle.note(it, jumpStart = false, sneakStart = false, moving = true)) } }
		assertTrue(!circle.note("0,0", jumpStart = false, sneakStart = false, moving = true))
		assertTrue(!circle.note("1,0", jumpStart = false, sneakStart = false, moving = true))
		assertTrue(circle.note("1,1", jumpStart = false, sneakStart = false, moving = true))
	}

	@Test
	fun aRouteThatFailsTwiceIsRemembered() {
		RouteMemory.clear()
		val edge = listOf(0 to 0, 1 to 0)
		assertEquals(0.0, RouteMemory.penalty(9, 8, 0, 0, 1, 0))
		assertTrue(!RouteMemory.noteFailure(9, 8, edge))
		assertEquals(20.0, RouteMemory.penalty(9, 8, 0, 0, 1, 0))
		assertTrue(RouteMemory.noteFailure(9, 8, edge))
		assertEquals(80.0, RouteMemory.penalty(9, 8, 0, 0, 1, 0))
		assertTrue(RouteMemory.hasFailure(9, 8))
		val saved = RouteMemory.export()
		RouteMemory.clear()
		RouteMemory.load(saved)
		assertEquals(80.0, RouteMemory.penalty(9, 8, 0, 0, 1, 0))
		RouteMemory.clear()
	}

	@Test
	fun hookSumBeatsEitherSideThenBreaksTiesByPriority() {
		val both = setOf(FishTarget.STRONG, FishTarget.WISE)
		val strongFirst = listOf(FishTarget.STRONG, FishTarget.WISE)
		val wiseFirst = listOf(FishTarget.WISE, FishTarget.STRONG)
		val thin = normalRank(30, 0, -1, -1, -1, -1, -1, strongFirst, both)
		val balanced = normalRank(20, 20, -1, -1, -1, -1, -1, strongFirst, both)
		assertNotNull(thin)
		assertNotNull(balanced)
		assertTrue(normalPrefer(balanced, thin))
		val strongHeavy = normalRank(30, 10, -1, -1, -1, -1, -1, strongFirst, both)
		val wiseHeavy = normalRank(20, 20, -1, -1, -1, -1, -1, strongFirst, both)
		assertNotNull(strongHeavy)
		assertNotNull(wiseHeavy)
		assertTrue(normalPrefer(strongHeavy, wiseHeavy))
		val strongHeavyWise = normalRank(30, 10, -1, -1, -1, -1, -1, wiseFirst, both)
		val wiseHeavyWise = normalRank(20, 20, -1, -1, -1, -1, -1, wiseFirst, both)
		assertNotNull(strongHeavyWise)
		assertNotNull(wiseHeavyWise)
		assertTrue(normalPrefer(wiseHeavyWise, strongHeavyWise))
	}

	@Test
	fun wiseAndStrongRankByTheirSum() {
		val order = listOf(FishTarget.WISE, FishTarget.STRONG, FishTarget.ELUSIVE)
		val wise = normalRank(10, 15, -1, -1, -1, -1, -1, order, setOf(FishTarget.WISE))
		assertNotNull(wise)
		assertEquals(25, wise.value)
		assertNull(normalRank(-1, -1, 4, -1, -1, -1, -1, order, setOf(FishTarget.WISE)))
		val strong = normalRank(12, 8, -1, -1, -1, -1, -1, order, setOf(FishTarget.STRONG))
		assertNotNull(strong)
		assertEquals(20, strong.value)
	}

	@Test
	fun islandPickScansThenFishesThenSkipsFinished() {
		val order = listOf("I3", "I2", "I1")
		assertEquals(IslandPick("I3", IslandJob.SCAN), pickIsland(order, emptySet(), emptySet()))
		assertEquals(IslandPick("I3", IslandJob.FISH), pickIsland(order, setOf("I3"), emptySet()))
		assertEquals(IslandPick("I2", IslandJob.SCAN), pickIsland(order, setOf("I3"), setOf("I3")))
		assertEquals(
			IslandPick("I1", IslandJob.FISH),
			pickIsland(order, setOf("I3", "I2", "I1"), setOf("I3", "I2"))
		)
		assertNull(pickIsland(order, setOf("I3", "I2", "I1"), setOf("I3", "I2", "I1")))
	}

	@Test
	fun landStuckUsesNetMovementOverThreeSeconds() {
		assertTrue(!landBarelyMoved(0.4, 0.0, 0.4, 59))
		assertTrue(landBarelyMoved(0.8, 0.5, 0.4, 60))
		assertTrue(!landBarelyMoved(1.2, 0.5, 0.4, 60))
	}

	@Test
	fun farSpotUsesALoadedStepTowardIt() {
		val aim = loadedApproach(0, 0, 200, 0, 96.0) { x, _ -> x <= 40 }
		assertTrue(aim.first <= 40)
		assertTrue(aim.first >= 8)
	}

	@Test
	fun builtInRingsMatchTheIslandTables() {
		val first = BuiltInRoutes.island("I1")
		assertNotNull(first)
		assertEquals(-207, first.first().x)
		assertEquals(153, first.first().z)
		assertEquals(7, first.size)
		assertEquals(-328, first[1].x)
		assertEquals(151, first[1].y)
		assertEquals(210, first[1].z)
		assertEquals(-352, first[3].x)
		assertEquals(314, first[3].z)
		assertEquals(-206, first.last().x)
		assertEquals(153, first.last().y)
		assertEquals(382, first.last().z)
		assertEquals(8, BuiltInRoutes.island("I2")?.size)
		assertEquals(8, BuiltInRoutes.island("I3")?.size)
		val island4 = BuiltInRoutes.island("I4")
		assertNotNull(island4)
		assertEquals(22, island4.size)
		assertEquals(778, island4.first().x)
		assertEquals(152, island4.first().y)
		assertEquals(1758, island4.first().z)
		assertEquals(901, island4.last().x)
		assertEquals(1754, island4.last().z)
		val grotto = BuiltInRoutes.matchGrotto(2800.0, -6400.0)
		assertNotNull(grotto)
		assertEquals(2803, grotto.spawn.x)
		val third = BuiltInRoutes.matchGrotto(1770.0, -6450.0)
		assertNotNull(third)
		assertEquals(1779, third.spawn.x)
		assertEquals("grotto_temperate_3", BuiltInRoutes.matchGrotto(1724.0, -6474.0)?.name)
		val fourth = BuiltInRoutes.matchGrotto(700.0, -6450.0)
		assertNotNull(fourth)
		assertEquals("grotto_temperate_4", fourth.name)
		assertEquals(695, fourth.spawn.x)
		val temperate = BuiltInRoutes.matchGrotto(-7400.0, -4400.0)
		assertNotNull(temperate)
		assertEquals(-7467, temperate.spawn.x)
	}

	@Test
	fun fenceIsNotAOneBlockJump() {
		val grid = Grid.build(0, 0, 0, 4, 6, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 && (x == 0 || x == 2) -> Cell.SOLID
				x == 1 && y == 1 -> Cell.TALL
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(0, 1, 1, Mode.LAND), setOf(Node(2, 1, 1, Mode.LAND)))
		assertTrue(route == null || !route.complete)
		assertTrue(!clearLook(grid, Node(0, 1, 1, Mode.LAND), 2.5, 2.0, 1.5))
	}

	@Test
	fun castRequiresAClearLookAtTheDisplay() {
		val open = Grid.build(0, 0, 0, 6, 6, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 && x == 0 -> Cell.SOLID
				y == 0 && x >= 2 -> Cell.WATER
				else -> Cell.AIR
			}
		}
		val seen = findCasts(open, 4.5, 1.0, 1.5, lookX = 4.5, lookY = 1.6, lookZ = 1.5, radius = 4)
		assertTrue(seen.any { it.stance.x == 0 && it.stance.y == 1 })

		val walled = Grid.build(0, 0, 0, 6, 6, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 && x == 0 -> Cell.SOLID
				y == 0 && x >= 2 -> Cell.WATER
				x == 1 && y in 1..3 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val hidden = findCasts(walled, 4.5, 1.0, 1.5, lookX = 4.5, lookY = 1.6, lookZ = 1.5, radius = 4)
		assertTrue(hidden.none { it.stance.x == 0 })
	}

	@Test
	fun castArcCannotCrossABlockOrFlowingWater() {
		val wall = Grid.build(0, 0, 0, 8, 8, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 && x == 0 -> Cell.SOLID
				y == 0 && x >= 4 -> Cell.WATER
				x == 2 && y in 1..5 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		assertTrue(findCasts(wall, 5.5, 1.0, 1.5).none { it.stance.x == 0 })
		val flow = Grid.build(0, 0, 0, 8, 8, 3) { x, y, z ->
			when {
				z != 1 -> Cell.SOLID
				y == 0 && x == 0 -> Cell.SOLID
				y == 0 && x >= 4 -> Cell.WATER
				x == 2 && y in 1..4 -> Cell.FLOW
				else -> Cell.AIR
			}
		}
		assertTrue(findCasts(flow, 5.5, 1.0, 1.5).none { it.stance.x == 0 })
	}

	@Test
	fun bobberMustLandWithinOneBlockOfTheDisplay() {
		assertTrue(bobberOnSpot(5.2, 2.4, 5.0, 2.0))
		assertTrue(!bobberOnSpot(6.2, 2.0, 5.0, 2.0))
	}

	@Test
	fun castCellCannotBeWater() {
		val grid = Grid.build(0, 0, 0, 4, 4, 4) { x, y, _ ->
			when {
				y == 0 && x == 1 -> Cell.WATER
				y == 0 -> Cell.SOLID
				y == 1 && x == 2 -> Cell.LILY
				else -> Cell.AIR
			}
		}
		assertTrue(dryCastCell(grid, 0, 1, 0))
		assertTrue(!dryCastCell(grid, 1, 1, 0))
		assertTrue(!dryCastCell(grid, 2, 1, 0))
	}

	@Test
	fun castStandsOnDryLandInsideTheDisk() {
		val grid = Grid.build(0, 0, 0, 10, 6, 5) { x, y, z ->
			when {
				z !in 1..3 -> Cell.SOLID
				y == 0 && x >= 3 -> Cell.WATER
				y == 0 && x <= 1 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val casts = findCasts(grid, centerX = 5.5, centerY = 1.0, centerZ = 2.5, radius = 8)
		assertTrue(casts.isNotEmpty())
		assertTrue(casts.all { isLand(grid, it.stance.x, it.stance.y, it.stance.z) })
		assertTrue(casts.none { grid.get(it.stance.x, it.stance.y, it.stance.z) == Cell.WATER })
	}

	@Test
	fun openGroundCurveUsesArbitrarySlopesAndBendsALot() {
		val grid = Grid.build(0, 0, 0, 24, 4, 18) { _, y, _ ->
			if (y == 0) Cell.SOLID else Cell.AIR
		}
		val route = findPath(grid, Node(1, 1, 3, Mode.LAND), setOf(Node(21, 1, 8, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		val curve = route.curve
		assertTrue(curve.size > 24, curve.size.toString())
		assertEquals(1.5, curve.first().x, 0.05)
		assertEquals(3.5, curve.first().z, 0.05)
		assertEquals(21.5, curve.last().x, 0.05)
		assertEquals(8.5, curve.last().z, 0.05)
		assertTrue(curveDeviation(curve) > 2.0, curveDeviation(curve).toString())
		assertTrue(curve.any { !onFortyFive(heading(it, curve)) }, "curve stays on 45 degree slopes")
		val turn = totalTurn(curve)
		assertTrue(turn.total > 120.0, turn.toString())
		assertTrue(turn.maxStep < 55.0, turn.toString())
		assertTrue(curve.all { bodyOnLand(grid, it) })
	}

	@Test
	fun curveAroundAWallStaysOffTheWall() {
		val grid = Grid.build(0, 0, 0, 16, 4, 14) { x, y, z ->
			when {
				y == 0 -> Cell.SOLID
				x == 7 && z <= 7 && y in 1..2 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val route = findPath(grid, Node(1, 1, 2, Mode.LAND), setOf(Node(14, 1, 2, Mode.LAND)))
		assertNotNull(route)
		assertTrue(route.complete)
		assertEquals(Node(14, 1, 2, Mode.LAND), route.nodes.last())
		assertTrue(route.curve.isNotEmpty())
		assertTrue(route.curve.none { overlapsWall(it) })
		assertTrue(route.curve.all { bodyOnLand(grid, it) })
	}

	private fun heading(point: CurvePoint, curve: List<CurvePoint>): Double {
		val index = curve.indexOf(point).coerceAtLeast(0)
		val next = curve[(index + 1).coerceAtMost(curve.lastIndex)]
		return Math.toDegrees(atan2(next.z - point.z, next.x - point.x))
	}

	private fun onFortyFive(degrees: Double): Boolean {
		val remainder = abs(degrees % 45.0)
		return min(remainder, 45.0 - remainder) < 2.0
	}

	private data class Turn(val total: Double, val maxStep: Double)

	private fun totalTurn(curve: List<CurvePoint>): Turn {
		var total = 0.0
		var maxStep = 0.0
		var previous = 0.0
		for (index in 1 until curve.lastIndex) {
			val dx = curve[index].x - curve[index - 1].x
			val dz = curve[index].z - curve[index - 1].z
			if (hypot(dx, dz) < 1.0e-4) continue
			val facing = Math.toDegrees(atan2(dz, dx))
			if (index > 1) {
				var delta = (facing - previous) % 360.0
				if (delta > 180.0) delta -= 360.0
				if (delta < -180.0) delta += 360.0
				total += abs(delta)
				if (abs(delta) > maxStep) maxStep = abs(delta)
			}
			previous = facing
		}
		return Turn(total, maxStep)
	}

	private fun curveDeviation(curve: List<CurvePoint>): Double {
		val start = curve.first()
		val end = curve.last()
		val dx = end.x - start.x
		val dz = end.z - start.z
		val length = hypot(dx, dz).coerceAtLeast(1.0e-6)
		var max = 0.0
		for (point in curve) {
			val cross = abs((point.x - start.x) * dz - (point.z - start.z) * dx) / length
			if (cross > max) max = cross
		}
		return max
	}

	@Test
	fun bodyOnTheStanceEdgeStillCounts() {
		val stance = Node(4, 10, 6, Mode.LAND)
		assertTrue(bodyAtStance(4.5, 10.0, 6.5, stance))
		assertTrue(bodyAtStance(5.9, 12.0, 7.9, stance))
		assertTrue(!bodyAtStance(6.2, 10.0, 6.5, stance))
		assertTrue(!bodyAtStance(4.5, 13.0, 6.5, stance))
	}

	@Test
	fun scanStandUsesTheMarkerHeight() {
		val goals = scanStandGoals(0, 64, 0) { x, y, z ->
			(x == 0 && z == 0 && y == 64) || (x == 1 && z == 0 && y == 80)
		}
		assertEquals(setOf(Node(0, 64, 0, Mode.LAND)), goals)
	}

	@Test
	fun temperateWaterMustShareTheSpawnFloor() {
		val spawn = ScanPoint(0, 40, 0)
		val land = mapOf(grottoColKey(0, 0) to 40, grottoColKey(1, 0) to 40, grottoColKey(4, 0) to 40)
		val reach = reachableSpawnLand(spawn, land)
		assertTrue(grottoColKey(1, 0) in reach)
		assertTrue(grottoColKey(4, 0) !in reach)
		assertTrue(waterTouchesSpawnLand(40, mapOf(grottoColKey(2, 0) to 40), reach))
		assertTrue(!waterTouchesSpawnLand(40, mapOf(grottoColKey(2, 0) to 60), reach))
		assertTrue(!waterTouchesSpawnLand(40, mapOf(grottoColKey(4, 0) to 40), reach))
		assertTrue(!grottoSameFloor(60, 40))
		assertTrue(grottoSameFloor(50, 40))
	}

	@Test
	fun sealedWaterIsIgnoredAndAnOpeningKeepsTheSpot() {
		val sealed = Grid.build(0, 0, 0, 3, 3, 3) { x, y, z ->
			if (x == 1 && y == 1 && z == 1) Cell.WATER else Cell.SOLID
		}
		assertTrue(sealedWater(sealed, 1, 1, 1))
		val open = Grid.build(0, 0, 0, 3, 3, 3) { x, y, z ->
			when {
				x == 1 && y == 1 && z == 1 -> Cell.WATER
				x == 1 && y == 2 && z == 1 -> Cell.AIR
				else -> Cell.SOLID
			}
		}
		assertTrue(!sealedWater(open, 1, 1, 1))
		val lily = Grid.build(0, 0, 0, 3, 3, 3) { x, y, z ->
			when {
				x == 1 && y == 1 && z == 1 -> Cell.WATER
				x == 1 && y == 2 && z == 1 -> Cell.LILY
				else -> Cell.SOLID
			}
		}
		assertTrue(!sealedWater(lily, 1, 1, 1))
	}

	@Test
	fun nearestLandIsTheFishingStandAndWallsAreNotGoals() {
		val grid = Grid.build(0, 0, 0, 8, 4, 5) { x, y, z ->
			when {
				y == 0 && x == 4 -> Cell.WATER
				y == 0 -> Cell.SOLID
				x == 3 && z == 0 && y in 1..2 -> Cell.SOLID
				else -> Cell.AIR
			}
		}
		val stand = nearestStand(grid, 4, 1, 2)
		assertNotNull(stand)
		assertEquals(3, stand.x)
		assertEquals(1, stand.y)
		assertEquals(2, stand.z)
		assertTrue(isLand(grid, stand.x, stand.y, stand.z))
		assertNull(findPath(grid, Node(0, 1, 2, Mode.LAND), setOf(Node(3, 1, 0, Mode.LAND))))
		val around = findPath(grid, Node(0, 1, 0, Mode.LAND), setOf(Node(6, 1, 0, Mode.LAND)))
		assertNotNull(around)
		assertTrue(around.complete)
		assertTrue(around.nodes.none { it.x == 3 && it.z == 0 })
		val clipped = around.nodes.zipWithNext().filter { (from, to) ->
			from.mode == to.mode && from.y == to.y && !canConnect(grid, from, to)
		}
		assertTrue(clipped.isEmpty(), around.nodes.toString() + " clipped " + clipped)
	}

	@Test
	fun ceilingSwimsInThroughTheLowHole() {
		val grid = Grid.build(0, 0, 0, 6, 6, 2) { x, y, z ->
			when {
				y == 0 -> Cell.SOLID
				z == 0 && x == 1 && y == 1 -> Cell.SOLID
				z == 0 && x == 2 && (y == 1 || y == 2) -> Cell.SOLID
				z == 1 && x in 0..4 && (y == 1 || y == 2) -> Cell.WATER
				else -> Cell.AIR
			}
		}
		val start = Node(0, 1, 0, Mode.LAND)
		val goal = setOf(Node(3, 1, 0, Mode.LAND))
		val over = findPath(grid, start, goal)
		val under = findPath(grid, start, goal, ceiling = 3)
		assertNotNull(over)
		assertNotNull(under)
		assertTrue(over.nodes.any { it.y >= 3 })
		assertTrue(under.nodes.none { it.y >= 3 })
		assertTrue(under.steps.any { it.kind == EdgeKind.SWIM })
	}

	private fun bodyOnLand(grid: Grid, point: CurvePoint): Boolean {
		val minX = floor(point.x - 0.3).toInt()
		val maxX = floor(point.x + 0.299).toInt()
		val minZ = floor(point.z - 0.3).toInt()
		val maxZ = floor(point.z + 0.299).toInt()
		val y = point.y.toInt()
		for (x in minX..maxX) {
			for (z in minZ..maxZ) {
				if (!isLand(grid, x, y, z)) return false
			}
		}
		return true
	}

	private fun overlapsWall(point: CurvePoint): Boolean {
		val minX = floor(point.x - 0.3).toInt()
		val maxX = floor(point.x + 0.299).toInt()
		val minZ = floor(point.z - 0.3).toInt()
		val maxZ = floor(point.z + 0.299).toInt()
		for (x in minX..maxX) {
			for (z in minZ..maxZ) {
				if (x == 7 && z <= 7) return true
			}
		}
		return false
	}

	@Test
	fun lowWaterSinksWhenTheCeilingIsUnderTwoBlocks() {
		assertEquals(2.0, waterHeadroom(listOf(1.0, 1.0)))
		assertEquals(1.0, waterHeadroom(listOf(1.0, 0.0)))
		assertEquals(1.5, waterHeadroom(listOf(1.0, 0.5)))
		assertEquals(0.0, waterHeadroom(listOf(0.0)))
		assertEquals(0.5, waterHeadroom(listOf(0.5)))
		assertTrue(lowWaterPassable(2.0, 1))
		assertTrue(lowWaterPassable(1.0, 1))
		assertTrue(lowWaterPassable(0.0, 2))
		assertTrue(!lowWaterPassable(0.0, 1))
	}

	@Test
	fun standAndSpotMustHaveOnlyAirBetweenThem() {
		val blocked = setOf(1 to 0)
		val air: (Int, Int, Int) -> Boolean = { x, y, z -> !(y == 1 && blocked.any { it.first == x && it.second == z }) }
		assertTrue(openAirBetween(0, 1, 0, 1, 1, 0, air))
		assertTrue(!openAirBetween(0, 1, 0, 2, 1, 0, air))
		val clear: (Int, Int, Int) -> Boolean = { _, _, _ -> true }
		assertTrue(openAirBetween(0, 1, 0, 4, 1, 0, clear))
		val spotIsWater: (Int, Int, Int) -> Boolean = { x, y, z -> !(x == 3 && y == 1 && z == 0) }
		assertTrue(openAirBetween(0, 1, 0, 3, 1, 0, spotIsWater))
	}

	@Test
	fun gridRefusesASizeThatOverflowsTheArray() {
		assertNull(Grid.cellCount(0, 4, 4))
		assertNull(Grid.cellCount(50_000, 400, 50_000))
		assertEquals(64, Grid.cellCount(4, 4, 4))
	}

	@Test
	fun overclockTextIsTheFixedPerkLine() {
		assertEquals("Strong Hook", readPerkLine(listOf("Cost", "Overclocked Perk:", "> Strong Hook")))
		assertNull(readPerkLine(listOf("Overclocked Perk:", "")))
		assertNull(readPerkLine(listOf("Overclocked Perk:", "> Not A Perk")))
		assertEquals("Strong Hook", readPerk(listOf("Overclocked Perk:", "> Strong Hook"), "fishing/overclock/wise_hook"))
		assertEquals("Wise Hook", readPerk(emptyList(), "mcc:island_interface/fishing/overclock/wise_hook_glow"))
		assertEquals(1, clicksUntil(HOOK_NAMES, "Lucky Hook", "Strong Hook"))
		assertEquals(4, clicksUntil(HOOK_NAMES, "Strong Hook", "Lucky Hook"))
		assertEquals(0, clicksUntil(HOOK_NAMES, "Strong Hook", "Strong Hook"))
		assertEquals(HookPick.WISE, advance(HookPick.entries, HookPick.STRONG))
		assertEquals(HookPick.KEEP, advance(HookPick.entries, HookPick.LUCKY))
	}

	@Test
	fun overclockProfilesStayOnTheirOwnRows() {
		assertEquals(IslandOc.FISH, islandOc(FishTarget.WISE))
		assertEquals(IslandOc.FISH, islandOc(FishTarget.STRONG))
		assertEquals(IslandOc.FISH, islandOc(FishTarget.ELUSIVE))
		assertEquals(IslandOc.XP_WAYFINDER, islandOc(FishTarget.XP))
		assertEquals(IslandOc.XP_WAYFINDER, islandOc(FishTarget.WAYFINDER))
		val order = listOf(FishTarget.WISE, FishTarget.XP, FishTarget.PEARL)
		val enabled = setOf(FishTarget.WISE, FishTarget.XP)
		assertEquals(FishTarget.XP, islandLead(0, 0, -1, -1, -1, -1, -1, order, enabled, xp = 40))
		assertTrue(islandSpotQualifies(0, 0, -1, -1, -1, -1, -1, setOf(FishTarget.XP), xp = 10))
		assertTrue(!islandSpotQualifies(0, 0, -1, -1, -1, -1, -1, setOf(FishTarget.XP), xp = 0))
		assertEquals(GrottoKind.MAGNET_FISH, grottoKindOf(200, 5, 1, 1, 1, 1))
		assertEquals(GrottoKind.PEARL, grottoKindOf(0, -1, -1, 4, -1, -1))
	}

	@Test
	fun travelGoesToTheGrottoBeforeAnUnfinishedIsland() {
		assertEquals("TEMPERATE", travelDestination("Temperate", listOf("I1", "I2"), emptySet(), emptySet()))
		assertEquals("I2", travelDestination("", listOf("I1", "I2"), setOf("I1", "I2"), setOf("I1")))
		assertEquals("I1", travelDestination("", listOf("I1", "I2"), emptySet(), emptySet()))
		assertNull(travelDestination("", listOf("I1"), setOf("I1"), setOf("I1")))
		assertTrue(lobbyScoreboard("§aMCCI: MAIN ISLAND"))
		assertTrue(!lobbyScoreboard("MCCI: Verdant Woods"))
		assertEquals("I1", placeId("Verdant Woods", emptyList(), "island_interface/fishing/island/floral_forest"))
		assertEquals("I1", placeId("", emptyList(), "mcc:island_interface/fishing/island/verdant_woods_glow"))
		assertTrue(isEchoShard("minecraft:echo_shard"))
		assertTrue(isEchoShard("item.minecraft.echo_shard"))
		assertTrue(!isEchoShard("minecraft:compass"))
		assertEquals("I4", echoShardPlace("tropical overgrowth", emptyList()))
		assertEquals(20, islandMenuSlot("I1"))
		assertEquals(24, islandMenuSlot("temperate"))
		assertEquals(33, islandMenuSlot("TROPICAL"))
		assertEquals(42, islandMenuSlot("barren"))
		assertEquals("I1", echoShardPlace("VERDANT WOODS", emptyList()))
		assertEquals("TROPICAL", echoShardPlace("Tropical", emptyList()))
		assertNull(echoShardPlace("Shop", emptyList()))
		assertTrue(isFishingNav("Fishing", emptyList(), "island_interface/navigator/island"))
		assertTrue(!isFishingNav("Shop", emptyList(), "island_interface/navigator/shop"))
		assertTrue(isAnglrPanel("A.N.G.L.R. Panel", emptyList()))
		assertTrue(isFishingSupplies("Fishing Supplies", emptyList()))
		assertTrue(isTravelCompass("Compass", "minecraft:compass"))
		val travel = travelScript()
		assertEquals(listOf("open-inventory", "click-compass", "click-fishing", "click-island", "close"), travel)
		assertTrue("baritone-stop" !in travel)
		val supplies = suppliesScript(1, 0, 4)
		assertEquals("open-inventory", supplies.first())
		assertEquals("click-anglr-panel", supplies[1])
		assertEquals("click-fishing-supplies", supplies[2])
		assertEquals(1, supplies.count { it == "right-hook" })
		assertEquals(4, supplies.count { it == "right-rod" })
		assertTrue("baritone-stop" !in supplies)
	}

	private fun flat(length: Int, ground: Int) = Grid.build(0, 0, 0, length, ground + 4, 3) { x, y, z ->
		when {
			z != 0 -> Cell.SOLID
			y == ground - 1 && x < length -> Cell.SOLID
			else -> Cell.AIR
		}
	}
}
