package kcl.realwayfinder.client

import kcl.realwayfinder.Realwayfinder
import kcl.realwayfinder.nav.CurvePoint
import kcl.realwayfinder.nav.EdgeKind
import kcl.realwayfinder.nav.Mode
import kcl.realwayfinder.nav.Node
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import kcl.spotfilter.client.ui.LinePart
import kcl.spotfilter.client.ui.SpotLines
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.ARGB
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.hypot
import kotlin.math.roundToInt

object RouteOverlay {
	fun register() {
		HudElementRegistry.addLast(Realwayfinder.id("route"), RouteOverlay::extract)
		LevelRenderEvents.BEFORE_GIZMOS.register { markGoal() }
	}

	private fun markGoal() {
		val plan = RouteRuntime.plan ?: return
		val route = plan.route ?: return
		if (!route.complete && plan.spotLabel != "扫点" && plan.spotLabel != "回出生点") return
		val end = route.nodes.lastOrNull() ?: return
		if (end.mode == Mode.SWIM) return
		val x = end.x.toDouble()
		val y = goalSurface(end)
		val z = end.z.toDouble()
		Gizmos.rect(
			Vec3(x, y + 0.015, z),
			Vec3(x + 1.0, y + 0.045, z + 1.0),
			Direction.UP,
			GizmoStyle.strokeAndFill(0xFF55FF55.toInt(), 2.5f, 0x8055FF55.toInt())
		)
	}

	/** Feet plane. A lily pad's top sits inside its own cell, so the cover follows that collision. */
	private fun goalSurface(end: Node): Double {
		val level = Minecraft.getInstance().level ?: return end.y.toDouble()
		val pos = BlockPos(end.x, end.y, end.z)
		val state = level.getBlockState(pos)
		if (!state.`is`(Blocks.LILY_PAD)) return end.y.toDouble()
		val shape = state.getCollisionShape(level, pos)
		if (shape.isEmpty) return end.y + 0.09375
		return end.y + shape.max(Direction.Axis.Y)
	}

	fun extract(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
		val client = Minecraft.getInstance()
		if (client.player == null || client.level == null) return
		val label = RouteRuntime.plan?.spotLabel
		if (!RouteSettings.enabled && label != "回放" && label != "扫点" && label != "回出生点") return
		graphics.nextStratum()
		drawSpotPanel(graphics, client)
		val plan = RouteRuntime.plan ?: return
		val route = plan.route
		if (route == null) return
		val drawn = if (route.curve.size >= 2) {
			route.curve.map { it.drawAt() to it.kind }
		} else if (route.steps.size >= 2) {
			route.steps.map { it.node.drawAt() to it.kind }
		} else {
			emptyList()
		}
		if (drawn.size >= 2) drawRoute(graphics, client, drawn)
	}

	private fun drawSpotPanel(graphics: GuiGraphicsExtractor, client: Minecraft) {
		val font = client.font
		val plan = RouteRuntime.plan
		val ranked = RoutePlanner.queue()
		val scanLabel = when (plan?.spotLabel) {
			"回放" -> "${plan.spotLabel} ${plan.biasLabel}"
			"扫点" -> "扫点 ${plan.biasLabel}"
			"回出生点" -> "回出生点"
			else -> null
		}
		val scanning = scanLabel != null
		val current = if (!scanning && plan != null && plan.spotLabel.isNotEmpty()) {
			ranked.firstOrNull { it.x == plan.spotX && it.y == plan.spotY && it.z == plan.spotZ }
		} else {
			null
		}
		val afterCurrent = if (current == null) 0 else ranked.indexOf(current) + 1
		val queued = if (scanning || current == null) ranked else ranked.drop(afterCurrent)
		val shownCurrent = if (scanning || current == null) queued.getOrNull(0) else current
		val shownNext = if (scanning || current == null) queued.getOrNull(1) else queued.getOrNull(0)
		val rows = listOf("当前点位" to shownCurrent, "下一个点位" to shownNext)
		val line = SpotLines.lineH(font)
		var blockW = font.width("当前点位")
		var blockH = 0
		val menuLine = MenuClicker.hud
		if (scanning) {
			blockW = maxOf(blockW, font.width(scanLabel))
			blockH += line
		}
		if (menuLine.isNotEmpty()) {
			blockW = maxOf(blockW, font.width(menuLine))
			blockH += line
		}
		val missing = Component.literal("无").withStyle(Style.EMPTY.withColor(0xAAAAAA))
		val parts = rows.map { (title, spot) ->
			val lineParts = if (spot == null) listOf(LinePart(text = missing)) else SpotLines.compactParts(spot)
			blockW = maxOf(blockW, font.width(title), SpotLines.width(font, lineParts))
			blockH += line * 2
			title to lineParts
		}
		val pad = 6
		val panelW = blockW + pad * 2
		val panelH = blockH + pad * 2
		val x = if (RouteSettings.hudX >= 0) RouteSettings.hudX else (graphics.guiWidth() - panelW - 8).coerceAtLeast(4)
		val y = if (RouteSettings.hudY >= 0) RouteSettings.hudY else 8
		graphics.fill(x, y, x + panelW, y + panelH, ARGB.color(160, 0, 0, 0))
		var cursor = y + pad
		val textX = x + pad
		if (scanning) {
			graphics.text(font, Component.literal(scanLabel), textX, cursor, 0xFF55FF55.toInt(), false)
			cursor += line
		}
		if (menuLine.isNotEmpty()) {
			graphics.text(font, Component.literal(menuLine), textX, cursor, 0xFF55FF55.toInt(), false)
			cursor += line
		}
		for ((title, lineParts) in parts) {
			graphics.text(font, Component.literal(title), textX, cursor, 0xFFAAAAAA.toInt(), false)
			cursor += line
			SpotLines.draw(graphics, font, lineParts, textX, cursor)
			cursor += line
		}
	}

	private fun drawRoute(graphics: GuiGraphicsExtractor, client: Minecraft, steps: List<Pair<Vec3, EdgeKind>>) {
		val camera = client.gameRenderer.mainCamera()
		val cam = camera.position()
		val forward = camera.forwardVector()
		val width = graphics.guiWidth()
		val height = graphics.guiHeight()
		val zoom = zoomScale()
		var previous: Pair<Float, Float>? = null
		for ((world, kind) in steps) {
			val dx = world.x - cam.x
			val dy = world.y - cam.y
			val dz = world.z - cam.z
			val depth = dx * forward.x() + dy * forward.y() + dz * forward.z()
			val ndc = client.gameRenderer.projectPointToScreen(world)
			val onScreen = depth > 0.05 && ndc.x.isFinite() && ndc.y.isFinite()
			val point = if (onScreen) {
				val sx = ((ndc.x * zoom + 1.0) * 0.5 * width).toFloat()
				val sy = ((1.0 - ndc.y * zoom) * 0.5 * height).toFloat()
				sx to sy
			} else {
				null
			}
			val from = previous
			if (from != null && point != null) {
				line(graphics, from.first, from.second, point.first, point.second, color(kind))
			}
			previous = point
		}
	}

	private fun line(graphics: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
		val steps = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toInt().coerceAtLeast(1)
		val count = steps.coerceAtMost(400)
		for (i in 0..count) {
			val t = i / count.toDouble()
			val x = (x0 + (x1 - x0) * t).roundToInt()
			val y = (y0 + (y1 - y0) * t).roundToInt()
			if (x < 0 || y < 0 || x >= graphics.guiWidth() || y >= graphics.guiHeight()) continue
			graphics.fill(x, y, x + 2, y + 2, color)
		}
	}

	private fun color(kind: EdgeKind): Int = when (kind) {
		EdgeKind.WALK -> 0xFFFFFFFF.toInt()
		EdgeKind.JUMP -> 0xFFFFFF55.toInt()
		EdgeKind.SPRINT_JUMP -> 0xFFFFAA00.toInt()
		EdgeKind.FALL -> 0xFFFF8800.toInt()
		EdgeKind.FLOAT -> 0xFF55FFFF.toInt()
		EdgeKind.SWIM -> 0xFF4488FF.toInt()
		EdgeKind.SHORE -> 0xFF55FF55.toInt()
	}

	private fun Node.drawAt(): Vec3 {
		val lift = if (mode == Mode.SWIM) 0.9 else 0.15
		return Vec3(x + 0.5, y + lift, z + 0.5)
	}

	private fun CurvePoint.drawAt(): Vec3 {
		val lift = if (kind == EdgeKind.SWIM) 0.9 else 0.15
		return Vec3(x, y + lift, z)
	}

	private fun zoomScale(): Float {
		val method = zooming ?: return 1f
		return try {
			if (method.invoke(null) != true) return 1f
			val level = zoomLevel?.getFloat(null) ?: 0.23f
			if (level > 1.0e-4f) 1f / level else 1f
		} catch (_: Throwable) {
			1f
		}
	}

	private val zooming: Method?
	private val zoomLevel: Field?

	init {
		var method: Method? = null
		var field: Field? = null
		try {
			val clazz = Class.forName("com.logicalgeekboy.logical_zoom.LogicalZoom")
			method = clazz.getMethod("isZooming")
			field = clazz.getField("zoomLevel")
		} catch (_: Throwable) {
		}
		zooming = method
		zoomLevel = field
	}
}
