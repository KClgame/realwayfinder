package kcl.realwayfinder.client

import kcl.realwayfinder.nav.HOOK_NAMES
import kcl.realwayfinder.nav.MAGNET_NAMES
import kcl.realwayfinder.nav.ROD_NAMES
import kcl.realwayfinder.nav.UNSTABLE_NAMES
import kcl.realwayfinder.nav.UnstablePick
import kcl.realwayfinder.nav.isUnstableItem
import kcl.realwayfinder.nav.menuLabel
import kcl.realwayfinder.nav.readUnstable
import kcl.realwayfinder.nav.clicksUntil
import kcl.realwayfinder.nav.isAnglrPanel
import kcl.realwayfinder.nav.isFishingNav
import kcl.realwayfinder.nav.isFishingSupplies
import kcl.realwayfinder.nav.islandMenuSlot
import kcl.realwayfinder.nav.isTravelCompass
import kcl.realwayfinder.nav.perkName
import kcl.realwayfinder.nav.placeTitle
import kcl.realwayfinder.nav.readPerk
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

/**
 * Opens the inventory and clicks echo shards. It never stops Baritone.
 * Movement keys that were held before the screen opened are put back while the screen is up.
 */
object MenuClicker {
	var hud: String = ""
		private set
	private var kind: Kind? = null
	private var step = Step.OPEN
	private var wait = 0
	private var opened = false
	private var destination = ""
	private var profileKey = ""
	private var wanted = OcTriple(kcl.realwayfinder.nav.HookPick.KEEP, kcl.realwayfinder.nav.MagnetPick.KEEP, kcl.realwayfinder.nav.RodPick.KEEP)
	private val clickQueue = ArrayList<Int>(8)
	private var clickGap = 0
	private var checks = 0
	private var unstableTries = 0
	private var unstableArmed = false
	private var lastFail = ""
	private var holdForward = false
	private var holdBack = false
	private var holdLeft = false
	private var holdRight = false
	private var holdJump = false
	private var holdSprint = false

	fun idle(): Boolean = kind == null

	fun showIdle(text: String) {
		if (kind == null) hud = text
	}

	fun holdingCast(): Boolean = kind == Kind.SUPPLIES

	fun startTravel(dest: String) {
		if (kind != null) return
		destination = dest
		kind = Kind.TRAVEL
		step = Step.OPEN
		wait = 0
		opened = false
		clickGap = 0
		hud = "前往 ${placeTitle(dest)}"
	}

	fun startSupplies(key: String, triple: OcTriple) {
		val hooksMatch = OcCache.matches(key, triple.hook.perkName(), triple.magnet.perkName(), triple.rod.perkName())
		if (kind != null || (hooksMatch && !unstableDue(triple.unstable))) return
		profileKey = key
		wanted = triple
		kind = Kind.SUPPLIES
		step = Step.OPEN
		wait = 0
		opened = false
		clickQueue.clear()
		clickGap = 0
		checks = 0
		unstableTries = 0
		unstableArmed = false
		hud = "Overclock 调整中"
	}

	/** Close one open screen. Does nothing when no screen is open, so it does not open the pause menu. */
	fun tapEscape(client: Minecraft) {
		val screen = client.gui.screen() ?: return
		screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0))
		if (client.gui.screen() != null) client.gui.setScreen(null)
	}

	fun cancel(client: Minecraft) {
		if (opened) client.gui.setScreen(null)
		clear()
	}

	fun tick(client: Minecraft, player: Player) {
		rememberKeys(client)
		val job = kind ?: return
		val screen = client.gui.screen()
		if (screen != null && !opened && screen !is InventoryScreen && step == Step.OPEN) {
			return
		}
		when (job) {
			Kind.TRAVEL -> tickTravel(client, player)
			Kind.SUPPLIES -> tickSupplies(client, player)
		}
	}

	private fun tickTravel(client: Minecraft, player: Player) {
		when (step) {
			Step.OPEN -> openInventory(client, player)
			Step.CLICK_COMPASS -> {
				if (holdGap()) return
				val found = leftClickFound(client, player) { isTravelCompass(it.hoverName.string, itemPath(it)) || it.`is`(Items.COMPASS) }
				if (!found) fail(client, player, "背包里没有指南针")
				else {
					step = Step.WAIT_NAV
					wait = 0
				}
			}
			Step.WAIT_NAV -> waitFor(client, player, "导航页没有打开") { menuOpen(client) }
			Step.CLICK_FISHING -> {
				if (holdGap()) return
				val found = leftClickFound(client, player) { isFishingNav(it.hoverName.string, lore(it), model(it)) }
				if (!found) fail(client, player, "导航页没有 Fishing")
				else {
					step = Step.WAIT_ISLAND
					wait = 0
				}
			}
			Step.WAIT_ISLAND -> {
				if (!menuOpen(client)) {
					stopWithoutClose()
					return
				}
				val index = islandMenuSlot(destination)
				if (index != null && player.containerMenu.slots.any { it.index == index }) {
					step = Step.CLICK_ISLAND
					clickGap = 20
					wait = 0
				}
			}
			Step.CLICK_ISLAND -> {
				if (holdGap()) return
				val index = islandMenuSlot(destination)
				if (index == null || client.gui.screen() !is AbstractContainerScreen<*>) {
					step = Step.WAIT_ISLAND
					return
				}
				leftClickIndex(client, player, index)
				stopWithoutClose()
			}
			else -> finish(client)
		}
	}

	private fun tickSupplies(client: Minecraft, player: Player) {
		when (step) {
			Step.OPEN -> openInventory(client, player)
			Step.CLICK_PANEL -> {
				val found = leftClickFound(client, player) { isAnglrPanel(it.hoverName.string, lore(it)) }
				if (!found) fail(client, player, "背包里没有 A.N.G.L.R. panel")
				else {
					step = Step.WAIT_PANEL
					wait = 0
				}
			}
			Step.WAIT_PANEL -> waitFor(client, player, "A.N.G.L.R. 面板没有打开") { menuOpen(client) }
			Step.CLICK_SUPPLIES -> {
				val found = leftClickFound(client, player) { isFishingSupplies(it.hoverName.string, lore(it)) }
				if (!found) fail(client, player, "面板里没有 Fishing Supplies")
				else {
					step = Step.WAIT_SUPPLIES
					wait = 0
				}
			}
			Step.WAIT_SUPPLIES -> waitFor(client, player, "Fishing Supplies 没有打开") {
				title(client).contains("FISHING SUPPLIES", ignoreCase = true)
			}
			Step.ADJUST -> announce(client, player)
			Step.BURST -> burst(client, player)
			Step.VERIFY -> verify(client, player)
			Step.UNSTABLE -> tickUnstable(client, player)
			Step.CLOSE -> pressEscape(client)
			else -> finish(client)
		}
	}

	private fun announce(client: Minecraft, player: Player) {
		val hook = readAt(player, 12)
		val magnet = readAt(player, 13)
		val rod = readAt(player, 14)
		val hookClicks = plannedClicks(hook, wanted.hook.perkName(), HOOK_NAMES)
		val magnetClicks = plannedClicks(magnet, wanted.magnet.perkName(), MAGNET_NAMES)
		val rodClicks = plannedClicks(rod, wanted.rod.perkName(), ROD_NAMES)
		val plan = listOf(
			"Hook" to hookClicks,
			"Magnet" to magnetClicks,
			"Rod" to rodClicks
		).filter { it.second > 0 }.joinToString("，") { "${it.first} 右键 ${it.second}" }
		val suffix = if (plan.isEmpty()) "" else "（$plan）"
		player.sendSystemMessage(Component.literal(
			"Realwayfinder Overclock Hook=${shown(hook)} Magnet=${shown(magnet)} Rod=${shown(rod)}$suffix"
		))
		clickQueue.clear()
		repeat(hookClicks) { clickQueue.add(12) }
		repeat(magnetClicks) { clickQueue.add(13) }
		repeat(rodClicks) { clickQueue.add(14) }
		clickGap = 0
		step = if (clickQueue.isEmpty()) Step.VERIFY else Step.BURST
		wait = 0
	}

	/** After the clicks, read the three perks again. A swallowed click leaves a gap, so keep correcting. */
	private fun verify(client: Minecraft, player: Player) {
		if (clickGap > 0) {
			clickGap--
			if (clickGap > 0) return
		}
		if (!title(client).contains("FISHING SUPPLIES", ignoreCase = true)) {
			fail(client, player, "核对时 Fishing Supplies 已关闭")
			return
		}
		val hook = readAt(player, 12)
		val magnet = readAt(player, 13)
		val rod = readAt(player, 14)
		val hookClicks = plannedClicks(hook, wanted.hook.perkName(), HOOK_NAMES)
		val magnetClicks = plannedClicks(magnet, wanted.magnet.perkName(), MAGNET_NAMES)
		val rodClicks = plannedClicks(rod, wanted.rod.perkName(), ROD_NAMES)
		val unread = (wanted.hook.perkName() != null && hook == null) ||
			(wanted.magnet.perkName() != null && magnet == null) ||
			(wanted.rod.perkName() != null && rod == null)
		if (!unread && hookClicks + magnetClicks + rodClicks == 0) {
			OcCache.store(profileKey, hook, magnet, rod)
			hud = "Overclock 相符"
			player.sendSystemMessage(Component.literal(
				"Realwayfinder Overclock 核对 Hook=${shown(hook)} Magnet=${shown(magnet)} Rod=${shown(rod)}"
			))
			unstableTries = 0
			unstableArmed = false
			clickGap = 0
			step = if (wanted.unstable == UnstablePick.OFF) Step.CLOSE else Step.UNSTABLE
			return
		}
		checks++
		if (checks > 4) {
			fail(client, player, "Overclock 核对后仍是 Hook=${shown(hook)} Magnet=${shown(magnet)} Rod=${shown(rod)}")
			return
		}
		if (unread) {
			clickGap = 20
			return
		}
		player.sendSystemMessage(Component.literal(
			"Realwayfinder Overclock 不符，继续 Hook 右键 $hookClicks，Magnet 右键 $magnetClicks，Rod 右键 $rodClicks"
		))
		clickQueue.clear()
		repeat(hookClicks) { clickQueue.add(12) }
		repeat(magnetClicks) { clickQueue.add(13) }
		repeat(rodClicks) { clickQueue.add(14) }
		clickGap = 0
		step = Step.BURST
	}

	/**
	 * Right click cycles the five bonuses. Shift-left enables it.
	 * The item text supplies the duration and cooldown. The next enable is enable time plus both.
	 */
	private fun tickUnstable(client: Minecraft, player: Player) {
		if (clickGap > 0) {
			clickGap--
			if (clickGap > 0) return
		}
		if (!title(client).contains("FISHING SUPPLIES", ignoreCase = true)) {
			fail(client, player, "Unstable 时 Fishing Supplies 已关闭")
			return
		}
		val slot = player.containerMenu.slots.firstOrNull { candidate ->
			val stack = candidate.item
			!stack.isEmpty && isUnstableItem(stack.hoverName.string, lore(stack))
		}
		if (slot == null) {
			UnstableSchedule.holdUntil(System.currentTimeMillis() + 30_000L)
			player.sendSystemMessage(Component.literal("Realwayfinder 没有 Unstable Overclock"))
			step = Step.CLOSE
			return
		}
		val read = readUnstable(lore(slot.item))
		UnstableSchedule.absorb(read)
		val screen = client.gui.screen() as? AbstractContainerScreen<*>
		if (screen == null) {
			fail(client, player, "Unstable 界面不在")
			return
		}
		if (read.phase != kcl.realwayfinder.nav.UnstablePhase.READY) {
			val now = System.currentTimeMillis()
			val waitMs = (UnstableSchedule.readyAtMs - now).coerceAtLeast(0L)
			val state = if (read.phase == kcl.realwayfinder.nav.UnstablePhase.ACTIVE) "启用中" else "冷却中"
			player.sendSystemMessage(Component.literal("Realwayfinder Unstable $state，下次 ${waitText(waitMs)}"))
			step = Step.CLOSE
			return
		}
		val wantedName = wanted.unstable.perkName()
		val currentName = read.perk?.perkName()
		if (currentName != wantedName) {
			unstableTries++
			if (currentName == null || unstableTries > UNSTABLE_NAMES.size) {
				player.sendSystemMessage(Component.literal("Realwayfinder 没读到 Unstable 加成 ${shown(currentName)}"))
				step = Step.CLOSE
				return
			}
			rightClickSlot(screen, slot)
			clickGap = 20
			return
		}
		if (!unstableArmed) {
			containerClick(client, player, slot.index, 0, ContainerInput.QUICK_MOVE)
			unstableArmed = true
			clickGap = 20
			return
		}
		player.sendSystemMessage(Component.literal("Realwayfinder Unstable 已启用 ${wanted.unstable.menuLabel()}"))
		step = Step.CLOSE
	}

	private fun containerClick(client: Minecraft, player: Player, index: Int, button: Int, type: ContainerInput) {
		try {
			client.gameMode?.handleContainerInput(player.containerMenu.containerId, index, button, type, player)
		} catch (_: RuntimeException) {
			fail(client, player, "Unstable 点击失败")
		}
	}

	/** One right-click, then one second before the next, so the server counts each cycle. */
	private fun burst(client: Minecraft, player: Player) {
		if (clickGap > 0) {
			clickGap--
			if (clickGap > 0) return
		}
		if (clickQueue.isEmpty()) {
			step = Step.VERIFY
			return
		}
		val slotIndex = clickQueue.removeAt(0)
		val slot = player.containerMenu.slots.firstOrNull { it.index == slotIndex }
		val screen = client.gui.screen()
		if (slot == null || screen !is AbstractContainerScreen<*>) {
			fail(client, player, "Overclock 槽位不在")
			return
		}
		rightClickSlot(screen, slot)
		clickGap = 20
		if (clickQueue.isEmpty()) step = Step.VERIFY
	}

	/** Left click one chest slot by its menu index. Island icons use the fixed 6-row layout. */
	private fun leftClickIndex(client: Minecraft, player: Player, index: Int) {
		try {
			client.gameMode?.handleContainerInput(
				player.containerMenu.containerId,
				index,
				0,
				ContainerInput.PICKUP,
				player
			)
		} catch (_: RuntimeException) {
			fail(client, player, "界面点击失败")
		}
	}

	private fun leftClickSlot(screen: AbstractContainerScreen<*>, slot: Slot) {
		val (left, top) = guiOrigin(screen)
		val event = MouseButtonEvent(
			(left + slot.x + 8).toDouble(),
			(top + slot.y + 8).toDouble(),
			MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)
		)
		screen.mouseClicked(event, false)
	}

	private fun stopWithoutClose() {
		opened = false
		hud = ""
		clear()
	}

	private fun rightClickSlot(screen: AbstractContainerScreen<*>, slot: Slot) {
		val (left, top) = guiOrigin(screen)
		val event = MouseButtonEvent(
			(left + slot.x + 8).toDouble(),
			(top + slot.y + 8).toDouble(),
			MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0)
		)
		screen.mouseClicked(event, false)
	}

	private fun pressEscape(client: Minecraft) {
		val screen = client.gui.screen()
		if (screen != null) screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0))
		if (client.gui.screen() != null) client.gui.setScreen(null)
		finish(client)
	}

	private fun openInventory(client: Minecraft, player: Player) {
		if (client.gui.screen() !is InventoryScreen) {
			client.gui.setScreen(InventoryScreen(player))
			opened = true
			wait = 0
			return
		}
		wait++
		if (wait < 2) return
		step = if (kind == Kind.TRAVEL) Step.CLICK_COMPASS else Step.CLICK_PANEL
		if (kind == Kind.TRAVEL) clickGap = 20
		wait = 0
	}

	/** One second between map clicks. A gap of zero means the click may happen now. */
	private fun holdGap(): Boolean {
		if (clickGap <= 0) return false
		clickGap--
		return clickGap > 0
	}

	private fun waitFor(client: Minecraft, player: Player, message: String, ready: () -> Boolean) {
		if (ready()) {
			val next = when (step) {
				Step.WAIT_NAV -> Step.CLICK_FISHING
				Step.WAIT_ISLAND -> Step.CLICK_ISLAND
				Step.WAIT_PANEL -> Step.CLICK_SUPPLIES
				Step.WAIT_SUPPLIES -> Step.ADJUST
				else -> step
			}
			if (next == Step.CLICK_FISHING || next == Step.CLICK_ISLAND) clickGap = 20
			step = next
			wait = 0
			return
		}
		wait++
		if (wait > 40) fail(client, player, message)
	}

	private fun finish(client: Minecraft) {
		if (opened) client.gui.setScreen(null)
		val keepHud = hud
		clear()
		hud = if (keepHud == "Overclock 相符") keepHud else ""
	}

	private fun fail(client: Minecraft, player: Player, message: String) {
		if (message != lastFail) {
			lastFail = message
			player.sendSystemMessage(Component.literal("Realwayfinder $message"))
		}
		hud = ""
		if (opened || client.gui.screen() != null) client.gui.setScreen(null)
		clear()
	}

	private fun clear() {
		kind = null
		opened = false
		step = Step.OPEN
		wait = 0
	}

	/** Left click. Island icons and every menu step except Overclock use this. */
	private fun leftClickFound(client: Minecraft, player: Player, test: (ItemStack) -> Boolean): Boolean {
		val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
		for (slot in player.containerMenu.slots) {
			val stack = slot.item
			if (stack.isEmpty || !test(stack)) continue
			leftClickSlot(screen, slot)
			return true
		}
		return false
	}

	private fun menuOpen(client: Minecraft): Boolean {
		val screen = client.gui.screen()
		return screen is AbstractContainerScreen<*> && screen !is InventoryScreen
	}

	private fun title(client: Minecraft): String {
		val screen = client.gui.screen()
		return if (screen is AbstractContainerScreen<*>) screen.title.string else ""
	}

	private fun readAt(player: Player, slot: Int): String? {
		val stack = player.containerMenu.slots.firstOrNull { it.index == slot }?.item ?: return null
		if (stack.isEmpty) return null
		return readPerk(lore(stack), model(stack))
	}

	private fun rememberKeys(client: Minecraft) {
		val options = client.options
		if (client.gui.screen() == null) {
			holdForward = options.keyUp.isDown
			holdBack = options.keyDown.isDown
			holdLeft = options.keyLeft.isDown
			holdRight = options.keyRight.isDown
			holdJump = options.keyJump.isDown
			holdSprint = options.keySprint.isDown
			return
		}
		if (kind == null) return
		if (holdForward) options.keyUp.setDown(true)
		if (holdBack) options.keyDown.setDown(true)
		if (holdLeft) options.keyLeft.setDown(true)
		if (holdRight) options.keyRight.setDown(true)
		if (holdJump) options.keyJump.setDown(true)
		if (holdSprint) options.keySprint.setDown(true)
	}

	private enum class Kind { TRAVEL, SUPPLIES }

	private enum class Step {
		OPEN,
		CLICK_COMPASS,
		WAIT_NAV,
		CLICK_FISHING,
		WAIT_ISLAND,
		CLICK_ISLAND,
		CLICK_PANEL,
		WAIT_PANEL,
		CLICK_SUPPLIES,
		WAIT_SUPPLIES,
		ADJUST,
		BURST,
		VERIFY,
		UNSTABLE,
		CLOSE
	}
}

private fun unstableDue(pick: UnstablePick): Boolean =
	pick != UnstablePick.OFF && UnstableSchedule.ready()

private fun waitText(ms: Long): String {
	val seconds = (ms / 1000L).coerceAtLeast(0L)
	val minutes = seconds / 60L
	val rest = seconds % 60L
	return if (minutes > 0L) "${minutes}分${rest}秒" else "${rest}秒"
}

private fun lore(stack: ItemStack): List<String> {
	val lore = stack.get(DataComponents.LORE) ?: return emptyList()
	return lore.lines().map { it.string }
}

private fun model(stack: ItemStack): String = stack.get(DataComponents.ITEM_MODEL)?.path ?: ""

private fun itemPath(stack: ItemStack): String = "${stack.item.descriptionId} ${stack.item}"

private fun shown(name: String?): String = name ?: "未读到"

private fun plannedClicks(current: String?, target: String?, order: List<String>): Int {
	if (current == null || target == null || current == target) return 0
	return clicksUntil(order, current, target).coerceAtLeast(0)
}

private fun guiOrigin(screen: Screen): Pair<Int, Int> {
	var type: Class<*>? = screen.javaClass
	while (type != null) {
		val left = type.declaredFields.firstOrNull { it.name == "leftPos" }
		val top = type.declaredFields.firstOrNull { it.name == "topPos" }
		if (left != null && top != null) {
			left.isAccessible = true
			top.isAccessible = true
			return left.getInt(screen) to top.getInt(screen)
		}
		type = type.superclass
	}
	return 0 to 0
}
