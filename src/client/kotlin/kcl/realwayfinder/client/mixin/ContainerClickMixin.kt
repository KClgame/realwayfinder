package kcl.realwayfinder.client.mixin

import kcl.realwayfinder.client.ScanRecord
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Records the click the screen actually performed: pickup, shift-move, swap, drop, or drag.
 * That is the same path chests, the player inventory, and crafting tables use.
 */
@Mixin(AbstractContainerScreen::class)
abstract class ContainerClickMixin {
	@Inject(
		method = ["slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V"],
		at = [At("HEAD")]
	)
	private fun realwayfinderRecordSlotClick(slot: Slot?, slotId: Int, button: Int, action: ContainerInput, ci: CallbackInfo) {
		ScanRecord.noteContainerClick(slotId, button, action.name)
	}
}
