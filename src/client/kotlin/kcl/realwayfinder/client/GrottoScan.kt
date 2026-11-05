package kcl.realwayfinder.client

import kcl.realwayfinder.nav.ScanCol
import kcl.realwayfinder.nav.ScanPoint
import kcl.realwayfinder.nav.SurfaceSample
import kcl.realwayfinder.nav.grottoScanStops
import kcl.realwayfinder.nav.pickInterior
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Reads loaded columns inside the entity-load square. Walls do not hide water.
 * Chunks that are not loaded are left out of this plan.
 */
class GrottoScan(
	private val level: ClientLevel,
	private val minX: Int,
	private val maxX: Int,
	private val minZ: Int,
	private val maxZ: Int,
	private val minY: Int,
	private val maxY: Int,
	private val spawn: ScanPoint
) {
	private var x = minX
	private var z = minZ
	private val cols = ArrayList<ScanCol>()
	private val loadedChunk = HashMap<Long, Boolean>()
	private val visited = HashSet<Long>()
	private var planned: List<ScanPoint>? = null

	fun ready(): List<ScanPoint>? = planned

	fun step(budget: Int) {
		if (planned != null) return
		var left = budget
		while (left > 0 && z <= maxZ) {
			val col = read(x, z)
			if (col != null) cols.add(col)
			left--
			x++
			if (x > maxX) {
				x = minX
				z++
			}
		}
		if (z > maxZ) planned = grottoScanStops(spawn, cols, minX, maxX, minZ, maxZ, visited = visited)
	}

	private fun read(x: Int, z: Int): ScanCol? {
		val chunk = (x shr 4).toLong() shl 32 or ((z shr 4).toLong() and 0xffffffffL)
		val ok = loadedChunk.getOrPut(chunk) { level.isLoaded(BlockPos(x, spawn.y, z)) }
		if (!ok) return null
		visited.add(x.toLong() shl 32 or (z.toLong() and 0xffffffffL))
		val samples = ArrayList<SurfaceSample>()
		for (y in maxY downTo minY) {
			val state = level.getBlockState(BlockPos(x, y, z))
			if (state.isAir) continue
			val above = level.getBlockState(BlockPos(x, y + 1, z))
			val aboveOpen = above.isAir || !above.blocksMotion() || above.`is`(Blocks.LILY_PAD)
			if (!aboveOpen) continue
			if (state.`is`(Blocks.LILY_PAD)) {
				samples.add(SurfaceSample(y, true))
				continue
			}
			val fluid = state.fluidState
			if (!fluid.isEmpty && fluid.`is`(FluidTags.WATER)) {
				if (fluid.isSource) samples.add(SurfaceSample(y, true))
				continue
			}
			if (!state.blocksMotion()) continue
			samples.add(SurfaceSample(y, false))
		}
		val chosen = pickInterior(samples, spawn.y) ?: return null
		return ScanCol(x, z, chosen.y + 1, chosen.water)
	}
}
