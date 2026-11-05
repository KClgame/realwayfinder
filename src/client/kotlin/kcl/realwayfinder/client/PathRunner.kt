package kcl.realwayfinder.client

import com.mojang.blaze3d.platform.InputConstants
import kcl.realwayfinder.nav.PathExec
import kcl.realwayfinder.nav.Route
import kcl.realwayfinder.nav.approachAngle
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/**
 * Holds the movement keys for the current block-to-block move and eases the view onto it.
 */
class PathRunner {
	private val exec = PathExec()
	private var bound: Route? = null
	private var holding = false
	private var failed = false
	private var jumpHold = 0
	private val loops = kcl.realwayfinder.nav.AutoLoop()
	private var repeatHome = false
	private var prevJump = false
	private var prevSneak = false

	fun takeRepeat(): Boolean {
		val hit = repeatHome
		repeatHome = false
		return hit
	}

	fun clearRepeat() {
		loops.clear()
		repeatHome = false
		prevJump = false
		prevSneak = false
	}

	fun takeFailed(): Boolean {
		val hit = failed || exec.takeFailed()
		failed = false
		return hit
	}

	fun release(client: Minecraft) {
		bound = null
		exec.reset()
		jumpHold = 0
		if (!holding) return
		releaseKey(client, client.options.keyUp)
		releaseKey(client, client.options.keyJump)
		releaseKey(client, client.options.keySprint)
		releaseKey(client, client.options.keyShift)
		holding = false
	}

	fun tick(client: Minecraft, player: Player, route: Route?, screenOpen: Boolean) {
		if (screenOpen || route == null || route.steps.size < 2 || manual(client)) {
			release(client)
			return
		}
		if (route !== bound) {
			bound = route
			exec.reset()
		}
		val order = exec.tick(
			route.steps,
			player.x,
			player.y,
			player.z,
			player.onGround(),
			RouteSettings.lookAhead,
			player.isInWater
		)
		if (order.failed) {
			failed = true
			release(client)
			return
		}
		if (order.done) {
			release(client)
			return
		}
		val yawDelta = kotlin.math.abs(kcl.realwayfinder.nav.wrapDegrees(order.yaw - player.yRot))
		val yaw = if (yawDelta < 3f) player.yRot else approachAngle(player.yRot, order.yaw, RouteSettings.yawStep)
		val pitch = approachAngle(player.xRot, order.pitch, RouteSettings.pitchStep)
		applyLook(player, yaw, pitch)
		val remain = kotlin.math.abs(kcl.realwayfinder.nav.wrapDegrees(order.yaw - yaw))
		val inWater = player.isInWater
		val forward = inWater || order.water || (order.forward && remain <= 45f)
		val sprint = !inWater && !order.water && order.sprint && remain < 18f && forward
		if (order.dive) jumpHold = 0
		else if (order.jump && forward && !order.descend) jumpHold = 5
		else if (jumpHold > 0) jumpHold--
		client.options.keyUp.setDown(forward)
		val swimUp = if (order.dive) order.jump && !order.descend else jumpHold > 0 && !order.descend
		client.options.keyJump.setDown(swimUp)
		client.options.keySprint.setDown(sprint)
		val sneaking = order.dive && order.descend
		client.options.keyShift.setDown(sneaking)
		player.setSprinting(sprint)
		holding = true
		val jumping = swimUp
		val cell = "${kotlin.math.floor(player.x).toInt()},${kotlin.math.floor(player.z).toInt()}"
		if (loops.note(cell, jumping && !prevJump, sneaking && !prevSneak, forward)) repeatHome = true
		prevJump = jumping
		prevSneak = sneaking
	}

	private fun applyLook(player: Player, yaw: Float, pitch: Float) {
		player.yRotO = player.yRot
		player.xRotO = player.xRot
		player.setYRot(yaw)
		player.setXRot(pitch)
		player.setYHeadRot(yaw)
		player.setYBodyRot(yaw)
	}

	private fun manual(client: Minecraft): Boolean {
		val options = client.options
		return physical(client, options.keyUp) ||
			physical(client, options.keyDown) ||
			physical(client, options.keyLeft) ||
			physical(client, options.keyRight) ||
			physical(client, options.keyJump) ||
			physical(client, options.keyShift)
	}

	private fun physical(client: Minecraft, mapping: KeyMapping): Boolean {
		val key = InputConstants.getKey(mapping.saveString())
		if (key.type != InputConstants.Type.KEYSYM) return false
		return InputConstants.isKeyDown(client.window, key.value)
	}

	private fun releaseKey(client: Minecraft, mapping: KeyMapping) {
		if (!physical(client, mapping)) mapping.setDown(false)
	}
}
