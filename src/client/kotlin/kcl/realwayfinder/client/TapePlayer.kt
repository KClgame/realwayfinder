package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.nav.TickInput
import kcl.realwayfinder.nav.TickReplay
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput

/**
 * Replays one recorded tick at a time: view, movement keys, mouse buttons, hotbar, and inventory slot.
 */
class TapePlayer {
	private val replay = TickReplay()
	private var bound: List<TickInput>? = null
	private var holding = false
	private var waitTicks = 0
	private var clickedIndex = -1
	var done: Boolean = false
		private set
	var failed: Boolean = false
		private set

	fun reset() {
		replay.reset()
		bound = null
		done = false
		failed = false
		holding = false
		waitTicks = 0
		clickedIndex = -1
	}

	fun release(client: Minecraft) {
		if (!holding) return
		val options = client.options
		releaseKey(client, options.keyUp)
		releaseKey(client, options.keyDown)
		releaseKey(client, options.keyLeft)
		releaseKey(client, options.keyRight)
		releaseKey(client, options.keyJump)
		releaseKey(client, options.keySprint)
		releaseKey(client, options.keyShift)
		releaseKey(client, options.keyAttack)
		releaseKey(client, options.keyUse)
		holding = false
	}

	fun tick(client: Minecraft, player: Player, frames: List<TickInput>, screenOpen: Boolean) {
		if (frames !== bound) {
			bound = frames
			replay.reset()
			done = false
		}
		val frame = replay.peek(frames)
		if (frame == null) {
			done = true
			release(client)
			return
		}
		if (waitingForContainer(client, frame)) {
			waitTicks++
			applyBody(client, player, frame, clickSlots = false)
			if (waitTicks > 80) {
				waitTicks = 0
				replay.advance()
			}
			return
		}
		waitTicks = 0
		replay.advance()
		applyBody(client, player, frame, clickSlots = true)
	}

	/** A chest screen is opened by the server after the right-click. Hold this tick until it is there. */
	private fun waitingForContainer(client: Minecraft, frame: TickInput): Boolean {
		if (frame.screen.isEmpty() || frame.screen == "InventoryScreen") return false
		return client.gui.screen()?.javaClass?.simpleName != frame.screen
	}

	private fun applyBody(client: Minecraft, player: Player, frame: TickInput, clickSlots: Boolean) {
		player.yRotO = player.yRot
		player.xRotO = player.xRot
		player.setYRot(frame.yaw)
		player.setXRot(frame.pitch)
		player.setYHeadRot(frame.yaw)
		player.setYBodyRot(frame.yaw)
		player.inventory.setSelectedSlot(frame.hotbar.coerceIn(0, 8))
		syncScreen(client, player, frame.screen)
		val options = client.options
		options.keyUp.setDown(frame.forward)
		options.keyDown.setDown(frame.back)
		options.keyLeft.setDown(frame.left)
		options.keyRight.setDown(frame.right)
		options.keyJump.setDown(frame.jump)
		options.keySprint.setDown(frame.sprint)
		options.keyShift.setDown(frame.sneak)
		options.keyAttack.setDown(frame.attack)
		options.keyUse.setDown(frame.use)
		player.setSprinting(frame.sprint)
		if (clickedIndex != replay.index) {
			if (frame.attackClick) KeyMapping.click(InputConstants.getKey(options.keyAttack.saveString()))
			if (frame.useClick) KeyMapping.click(InputConstants.getKey(options.keyUse.saveString()))
			clickedIndex = replay.index
		}
		if (clickSlots && frame.slot >= 0 && containerReady(client, frame)) {
			val kind = frame.slotAction.takeIf { it.isNotEmpty() }?.let {
				runCatching { ContainerInput.valueOf(it) }.getOrNull()
			} ?: if (frame.sneak) ContainerInput.QUICK_MOVE else ContainerInput.PICKUP
			try {
				client.gameMode?.handleContainerInput(
					player.containerMenu.containerId,
					frame.slot,
					frame.slotButton,
					kind,
					player
				)
			} catch (_: RuntimeException) {
				client.gui.setScreen(null)
				failed = true
			}
		}
		holding = true
	}

	private fun containerReady(client: Minecraft, frame: TickInput): Boolean {
		if (frame.screen.isEmpty() || frame.screen == "InventoryScreen") return true
		return client.gui.screen()?.javaClass?.simpleName == frame.screen
	}

	private fun syncScreen(client: Minecraft, player: Player, screen: String) {
		val open = client.gui.screen()
		if (screen == "InventoryScreen" && open !is InventoryScreen) {
			client.gui.setScreen(InventoryScreen(player))
		} else if (screen.isEmpty() && open is AbstractContainerScreen<*>) {
			client.gui.setScreen(null)
		}
	}

	private fun releaseKey(client: Minecraft, mapping: KeyMapping) {
		val key = InputConstants.getKey(mapping.saveString())
		val held = key.type == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(client.window, key.value)
		if (!held) mapping.setDown(false)
	}
}
