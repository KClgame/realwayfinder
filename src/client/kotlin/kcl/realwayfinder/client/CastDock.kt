package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.nav.CastSolution
import kcl.realwayfinder.nav.Node
import kcl.realwayfinder.nav.bobberOnSpot
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import kotlin.math.abs

/**
 * After the player stands on the goal: wait 2s, turn onto the cast angles over 2.5s, then throw.
 */
class CastDock {
	private enum class Phase { IDLE, WAIT, TURN, CONFIRM, DONE }

	private var phase = Phase.IDLE
	private var ticks = 0
	private var fromYaw = 0f
	private var fromPitch = 0f
	private var toldMissingRod = false
	private var pendingClick = false
	private var thrown: CastSolution? = null
	private var missed: Node? = null
	private var held: CastSolution? = null
	private var away = 0

	val active: Boolean get() = phase == Phase.WAIT || phase == Phase.TURN || phase == Phase.CONFIRM

	fun takeMiss(): Node? {
		val hit = missed
		missed = null
		return hit
	}

	fun clear() {
		phase = Phase.IDLE
		ticks = 0
		toldMissingRod = false
		pendingClick = false
		thrown = null
		held = null
		away = 0
	}

	fun tick(
		client: Minecraft,
		player: Player,
		onGoal: Boolean,
		cast: CastSolution?,
		screenOpen: Boolean,
		waterX: Double,
		waterZ: Double
	) {
		if (pendingClick && !player.isUsingItem) {
			pendingClick = false
			clickUse(client)
		}
		val usable = cast ?: held
		val present = onGoal && usable != null
		if (present) {
			away = 0
			held = usable
		} else if (phase == Phase.DONE && onGoal) {
			return
		} else if (held != null && phase != Phase.IDLE && phase != Phase.DONE && away < AWAY_TICKS) {
			away++
		} else {
			if (phase != Phase.DONE || !onGoal) clear()
			return
		}
		val aim = held ?: return
		when (phase) {
			Phase.IDLE -> {
				phase = Phase.WAIT
				ticks = 0
			}
			Phase.WAIT -> {
				ticks++
				if (ticks >= WAIT_TICKS) beginTurn(player, aim)
			}
			Phase.TURN -> {
				ticks++
				val linear = (ticks / TURN_TICKS.toFloat()).coerceIn(0f, 1f)
				look(player, aim, smooth(linear))
				if (ticks >= TURN_TICKS) {
					look(player, aim, 1f)
					throwRod(client, player)
					thrown = aim
					ticks = 0
					phase = Phase.CONFIRM
				}
			}
			Phase.CONFIRM -> {
				ticks++
				val hook = player.fishing
				if (hook != null && (hook.isInWater || ticks >= 15)) {
					if (!waterX.isNaN() && bobberOnSpot(hook.x, hook.z, waterX, waterZ)) {
						phase = Phase.DONE
						return
					}
					if (hook.isInWater || ticks >= 40) {
						missed = thrown?.stance
						phase = Phase.IDLE
						ticks = 0
						thrown = null
						held = null
					}
					return
				}
				if (ticks >= 40) {
					missed = thrown?.stance
					phase = Phase.IDLE
					ticks = 0
					thrown = null
					held = null
				}
			}
			Phase.DONE -> Unit
		}
	}

	private fun beginTurn(player: Player, cast: CastSolution) {
		holdRod(player)
		fromYaw = player.yRot
		fromPitch = player.xRot
		ticks = 0
		phase = Phase.TURN
		look(player, cast, 0f)
	}

	private fun look(player: Player, cast: CastSolution, t: Float) {
		val yaw = lerpYaw(fromYaw, cast.yaw, t)
		val pitch = fromPitch + (cast.pitch - fromPitch) * t
		player.yRotO = player.yRot
		player.xRotO = player.xRot
		player.setYRot(yaw)
		player.setXRot(pitch)
		player.setYHeadRot(yaw)
		player.setYBodyRot(yaw)
	}

	private fun holdRod(player: Player): Boolean {
		if (player.mainHandItem.`is`(Items.FISHING_ROD)) return true
		for (slot in 0..8) {
			if (!player.inventory.getItem(slot).`is`(Items.FISHING_ROD)) continue
			player.inventory.setSelectedSlot(slot)
			return true
		}
		if (!toldMissingRod) {
			toldMissingRod = true
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal("没有鱼竿"))
		}
		return false
	}

	/** One right-click on the bound use key. The next client tick's key handler throws the rod. */
	private fun throwRod(client: Minecraft, player: Player) {
		if (player.fishing != null) return
		if (!holdRod(player)) return
		if (player.isUsingItem) {
			releaseUse(client)
			pendingClick = true
			return
		}
		clickUse(client)
	}

	private fun clickUse(client: Minecraft) {
		val screen = client.gui.screen()
		if (screen != null) {
			val player = client.player ?: return
			client.gameMode?.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
			return
		}
		KeyMapping.click(InputConstants.getKey(client.options.keyUse.saveString()))
	}

	private fun releaseUse(client: Minecraft) {
		val key = InputConstants.getKey(client.options.keyUse.saveString())
		val held = key.type == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(client.window, key.value)
		if (!held) KeyMapping.set(key, false)
	}

	private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

	private fun lerpYaw(from: Float, to: Float, t: Float): Float {
		var delta = (to - from) % 360f
		if (delta > 180f) delta -= 360f
		if (delta < -180f) delta += 360f
		return from + delta * t
	}

	companion object {
		const val WAIT_TICKS = 40
		const val TURN_TICKS = 50
		/** Brief steps off the cell, lily bob, or a block-edge flicker do not restart the wait. */
		const val AWAY_TICKS = 20
	}
}

internal fun movedHorizontally(previousX: Double, previousZ: Double, x: Double, z: Double): Boolean {
	if (previousX.isNaN() || previousZ.isNaN()) return true
	return abs(x - previousX) > 0.05 || abs(z - previousZ) > 0.05
}
