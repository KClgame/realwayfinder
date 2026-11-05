package kcl.realwayfinder.nav

enum class Cell(val code: Byte) {
	AIR(0),
	SOLID(1),
	WATER(2),
	UNKNOWN(3),
	/** Fence, wall, or any collision taller than one block. A one-block jump cannot clear it. */
	TALL(4),
	/** Lily pad. Thin floor inside this cell; the block below is water. */
	LILY(5),
	/** Bottom slab or similar half block. Step onto it; do not treat it as a full cube. */
	SLAB(6),
	/** Flowing water. A cast cannot pass through it. */
	FLOW(7);

	companion object {
		fun from(code: Byte): Cell = entries[code.toInt()]
	}
}

class Grid(
	val originX: Int,
	val originY: Int,
	val originZ: Int,
	val sizeX: Int,
	val sizeY: Int,
	val sizeZ: Int,
	private val cells: ByteArray
) {
	init {
		require(cells.size == sizeX * sizeY * sizeZ)
	}

	fun get(x: Int, y: Int, z: Int): Cell {
		val ix = x - originX
		val iy = y - originY
		val iz = z - originZ
		if (ix !in 0 until sizeX || iy !in 0 until sizeY || iz !in 0 until sizeZ) return Cell.UNKNOWN
		return Cell.from(cells[(iy * sizeZ + iz) * sizeX + ix])
	}

	companion object {
		fun build(
			originX: Int,
			originY: Int,
			originZ: Int,
			sizeX: Int,
			sizeY: Int,
			sizeZ: Int,
			fill: (Int, Int, Int) -> Cell
		): Grid {
			val cells = ByteArray(sizeX * sizeY * sizeZ)
			var i = 0
			for (y in 0 until sizeY) {
				for (z in 0 until sizeZ) {
					for (x in 0 until sizeX) {
						cells[i++] = fill(originX + x, originY + y, originZ + z).code
					}
				}
			}
			return Grid(originX, originY, originZ, sizeX, sizeY, sizeZ, cells)
		}
	}
}
