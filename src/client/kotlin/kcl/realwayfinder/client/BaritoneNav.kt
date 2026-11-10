package kcl.realwayfinder.client

import baritone.api.BaritoneAPI
import baritone.api.event.events.PathEvent
import baritone.api.event.listener.AbstractGameEventListener
import baritone.api.pathing.goals.GoalBlock
import baritone.api.pathing.goals.GoalNear
import net.minecraft.core.BlockPos

/**
 * Baritone walks. Realwayfinder only chooses the block.
 * The 26.2 sources live in third_party/baritone and are packed into this jar.
 */
object BaritoneNav {
	private var ready = false
	private var listening = false
	private var goalX = Int.MIN_VALUE
	private var goalY = 0
	private var goalZ = 0
	private var goalRange = -1
	private var reportArrival = false
	private var arrival: BlockPos? = null
	private var dropOnFail = false
	private var pathFailed = false

	fun go(x: Int, y: Int, z: Int, range: Int = 0, report: Boolean = false, dropOnFail: Boolean = false) {
		configure()
		reportArrival = report
		if (x == goalX && y == goalY && z == goalZ && range == goalRange && this.dropOnFail == dropOnFail) return
		this.dropOnFail = dropOnFail
		pathFailed = false
		goalX = x
		goalY = y
		goalZ = z
		goalRange = range
		val goal = if (range <= 0) GoalBlock(x, y, z) else GoalNear(BlockPos(x, y, z), range)
		BaritoneAPI.getProvider().primaryBaritone.customGoalProcess.setGoalAndPath(goal)
	}

	/** Baritone's PathEvent.AT_GOAL for the goal started with report = true. One read clears it. */
	fun takeArrival(): BlockPos? {
		val hit = arrival
		arrival = null
		return hit
	}

	/** The current fishing goal had no path. One read clears it. Scan goals do not set this. */
	fun takePathFailure(): BlockPos? {
		if (!pathFailed || !dropOnFail || goalX == Int.MIN_VALUE) {
			pathFailed = false
			return null
		}
		pathFailed = false
		return BlockPos(goalX, goalY, goalZ)
	}

	fun stop() {
		goalX = Int.MIN_VALUE
		goalRange = -1
		reportArrival = false
		if (!ready) return
		BaritoneAPI.getProvider().primaryBaritone.pathingBehavior.cancelEverything()
	}

	/** A goal is set, a path exists, or a calculation is still running. */
	fun busy(): Boolean {
		if (!ready) return false
		val behavior = BaritoneAPI.getProvider().primaryBaritone.pathingBehavior
		return behavior.isPathing || behavior.hasPath() || behavior.goal != null || behavior.inProgress.isPresent
	}

	fun calculating(): Boolean {
		if (!ready) return false
		return BaritoneAPI.getProvider().primaryBaritone.pathingBehavior.inProgress.isPresent
	}

	private fun configure() {
		if (!listening) {
			listening = true
			BaritoneAPI.getProvider().primaryBaritone.gameEventHandler.registerEventListener(object : AbstractGameEventListener {
				override fun onPathEvent(event: PathEvent) {
					if (event == PathEvent.AT_GOAL && reportArrival && goalX != Int.MIN_VALUE) {
						arrival = BlockPos(goalX, goalY, goalZ)
					}
					if (event == PathEvent.CALC_FAILED && dropOnFail && goalX != Int.MIN_VALUE) {
						pathFailed = true
					}
				}
			})
		}
		if (ready) return
		val settings = BaritoneAPI.getSettings()
		settings.allowBreak.value = false
		settings.allowPlace.value = false
		settings.allowParkourPlace.value = false
		settings.allowInventory.value = false
		settings.allowWaterBucketFall.value = false
		settings.rightClickContainerOnArrival.value = false
		settings.chatControl.value = false
		settings.chatControlAnyway.value = false
		settings.prefixControl.value = false
		settings.echoCommands.value = false
		settings.notificationOnPathComplete.value = false
		settings.freeLook.value = false
		settings.allowSprint.value = true
		settings.sprintInWater.value = false
		settings.allowWalkOnMagmaBlocks.value = false
		settings.assumeWalkOnLava.value = false
		ready = true
	}
}
