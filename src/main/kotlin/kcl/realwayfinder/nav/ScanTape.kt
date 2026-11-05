package kcl.realwayfinder.nav

/**
 * One client tick of input. Replay applies this tick as recorded.
 * Position is stored so the start can be checked. Movement itself is the keys and the view.
 */
data class TickInput(
	val x: Double,
	val y: Double,
	val z: Double,
	val yaw: Float,
	val pitch: Float,
	val hotbar: Int,
	val forward: Boolean,
	val back: Boolean,
	val left: Boolean,
	val right: Boolean,
	val jump: Boolean,
	val sprint: Boolean,
	val sneak: Boolean,
	val attack: Boolean,
	val use: Boolean,
	val attackClick: Boolean = false,
	val useClick: Boolean = false,
	val slot: Int = -1,
	val item: String = "",
	val screen: String = "",
	val slotButton: Int = 0,
	val slotAction: String = ""
)

class TickReplay {
	var index: Int = 0
		private set

	fun reset() {
		index = 0
	}

	fun tick(frames: List<TickInput>): TickInput? {
		val frame = peek(frames) ?: return null
		advance()
		return frame
	}

	fun peek(frames: List<TickInput>): TickInput? = frames.getOrNull(index)

	fun advance() {
		index++
	}
}

fun parseTape(text: String): List<TickInput> {
	val frames = ArrayList<TickInput>()
	for (line in text.lineSequence()) {
		if (line.isBlank() || line.startsWith("#")) continue
		val parts = line.trim().split(' ')
		if (parts.size < 16) continue
		frames.add(
			TickInput(
				x = parts[0].toDoubleOrNull() ?: continue,
				y = parts[1].toDoubleOrNull() ?: continue,
				z = parts[2].toDoubleOrNull() ?: continue,
				yaw = parts[3].toFloatOrNull() ?: continue,
				pitch = parts[4].toFloatOrNull() ?: continue,
				hotbar = parts[5].toIntOrNull() ?: 0,
				forward = parts[6] == "1",
				back = parts[7] == "1",
				left = parts[8] == "1",
				right = parts[9] == "1",
				jump = parts[10] == "1",
				sprint = parts[11] == "1",
				sneak = parts[12] == "1",
				attack = parts[13] == "1",
				use = parts[14] == "1",
				attackClick = parts[15] == "1",
				useClick = parts.getOrNull(16) == "1",
				slot = parts.getOrNull(17)?.toIntOrNull() ?: -1,
				item = parts.getOrNull(18)?.let { if (it == "-") "" else it } ?: "",
				screen = parts.getOrNull(19)?.let { if (it == "-") "" else it } ?: "",
				slotButton = parts.getOrNull(20)?.toIntOrNull() ?: 0,
				slotAction = parts.getOrNull(21)?.let { if (it == "-") "" else it } ?: ""
			)
		)
	}
	return frames
}

fun formatTape(frames: List<TickInput>): String = buildString {
	append("# tick x y z yaw pitch hotbar wasd jump sprint sneak attack use attackClick useClick slot item screen\n")
	for (frame in frames) {
		append(frame.x)
		append(' ')
		append(frame.y)
		append(' ')
		append(frame.z)
		append(' ')
		append(frame.yaw)
		append(' ')
		append(frame.pitch)
		append(' ')
		append(frame.hotbar)
		append(' ')
		append(bit(frame.forward))
		append(' ')
		append(bit(frame.back))
		append(' ')
		append(bit(frame.left))
		append(' ')
		append(bit(frame.right))
		append(' ')
		append(bit(frame.jump))
		append(' ')
		append(bit(frame.sprint))
		append(' ')
		append(bit(frame.sneak))
		append(' ')
		append(bit(frame.attack))
		append(' ')
		append(bit(frame.use))
		append(' ')
		append(bit(frame.attackClick))
		append(' ')
		append(bit(frame.useClick))
		append(' ')
		append(frame.slot)
		append(' ')
		append(frame.item.ifEmpty { "-" }.replace(' ', '_'))
		append(' ')
		append(frame.screen.ifEmpty { "-" })
		append(' ')
		append(frame.slotButton)
		append(' ')
		append(frame.slotAction.ifEmpty { "-" })
		append('\n')
	}
}

private fun bit(on: Boolean): Char = if (on) '1' else '0'
