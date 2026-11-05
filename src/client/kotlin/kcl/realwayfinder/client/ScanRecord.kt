package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.nav.ScriptRole
import kcl.realwayfinder.nav.TickInput
import kcl.spotfilter.client.data.FishingPlace
import kcl.spotfilter.client.data.SpotKind
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import org.lwjgl.glfw.GLFW

object ScanRecord {
	var recording: Boolean = false
		private set
	var recordingId: String? = null
		private set
	private val frames = ArrayList<TickInput>()
	private val loaded = HashMap<String, List<TickInput>>()
	private var attackWas = false
	private var useWas = false
	private val pendingClicks = ArrayDeque<Triple<Int, Int, String>>()

	fun places(): List<Pair<String, String>> = listOf(
		"I1" to "I1 岛",
		"I2" to "I2 岛",
		"I3" to "I3 岛",
		"I4" to "I4 岛",
		"I5" to "I5 岛",
		"I6" to "I6 岛",
		"I7" to "I7 岛",
		"I8" to "I8 岛",
		"I9" to "I9 岛",
		"grotto-Temperate" to "温带 Grotto",
		"grotto-Tropical" to "热带 Grotto",
		"grotto-Barren" to "荒芜 Grotto"
	)

	fun scriptKey(place: FishingPlace): String =
		if (place.kind == SpotKind.GROTTO) "grotto-${place.shortId}" else place.shortId.uppercase()

	fun id(placeKey: String, role: ScriptRole): String = "$placeKey-${role.file}"

	fun tape(place: FishingPlace, role: ScriptRole): List<TickInput>? = tape(scriptKey(place), role)

	fun tape(placeKey: String, role: ScriptRole): List<TickInput>? {
		val name = ReplayLibrary.binding(placeKey, role) ?: return null
		return ReplayLibrary.frames(name)
	}

	fun framesOf(name: String): Int = ReplayLibrary.frames(name)?.size ?: 0

	fun startNamed(player: Player, name: String) {
		val clean = name.trim()
		if (clean.isEmpty()) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Realwayfinder 请先填写回放名称"))
			return
		}
		frames.clear()
		recordingId = clean
		recording = true
		attackWas = false
		useWas = false
		pendingClicks.clear()
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Realwayfinder 开始录制 $clean"))
	}

	fun stop(player: Player) {
		if (!recording) return
		recording = false
		val name = recordingId ?: return
		ReplayLibrary.save(name, frames)
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Realwayfinder 已保存 ${frames.size} 帧 $name"))
		recordingId = null
	}

	fun noteContainerClick(slotId: Int, button: Int, action: String) {
		if (!recording || slotId < 0) return
		pendingClicks.add(Triple(slotId, button, action))
	}

	fun capture(client: Minecraft, player: Player) {
		if (!recording) return
		val window = client.window.handle()
		val screenOpen = client.gui.screen()
		val attackDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
		val useDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
		val attackClick = attackDown && !attackWas
		val useClick = useDown && !useWas
		attackWas = attackDown
		useWas = useDown
		val click = pendingClicks.removeFirstOrNull()
		val slot = click?.first ?: -1
		val slotButton = click?.second ?: 0
		val slotAction = click?.third ?: ""
		val item = if (slot >= 0) itemInOpenScreen(client, slot) else ""
		frames.add(
			TickInput(
				x = player.x,
				y = player.y,
				z = player.z,
				yaw = player.yRot,
				pitch = player.xRot,
				hotbar = player.inventory.selectedSlot,
				forward = down(client, client.options.keyUp),
				back = down(client, client.options.keyDown),
				left = down(client, client.options.keyLeft),
				right = down(client, client.options.keyRight),
				jump = down(client, client.options.keyJump),
				sprint = down(client, client.options.keySprint) || player.isSprinting,
				sneak = down(client, client.options.keyShift),
				attack = attackDown,
				use = useDown,
				attackClick = attackClick && screenOpen == null,
				useClick = useClick && screenOpen == null,
				slot = slot,
				item = item,
				screen = screenOpen?.javaClass?.simpleName ?: "",
				slotButton = slotButton,
				slotAction = slotAction
			)
		)
	}

	private fun itemInOpenScreen(client: Minecraft, slotId: Int): String {
		val menu = client.player?.containerMenu ?: return ""
		val slot = menu.slots.getOrNull(slotId) ?: return ""
		return itemId(slot)
	}

	fun itemId(slot: Slot): String {
		val stack = slot.item
		if (stack.isEmpty) return ""
		return BuiltInRegistries.ITEM.getKey(stack.item).toString()
	}

	fun loadAll() {
		ReplayLibrary.load()
	}

	private fun down(client: Minecraft, mapping: KeyMapping): Boolean {
		val key = InputConstants.getKey(mapping.saveString())
		if (key.type != InputConstants.Type.KEYSYM) return false
		return InputConstants.isKeyDown(client.window, key.value)
	}
}
