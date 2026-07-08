package com.tylerebowers.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenCursorMixin {

	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
	private void pymine$drawCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
	                               float tickProgress, CallbackInfo ci) {
		// 7x7 white + outline
		graphics.fill(mouseX - 2, mouseY - 4, mouseX + 2, mouseY + 4, 0xFFFFFFFF);
		graphics.fill(mouseX - 4, mouseY - 2, mouseX + 4, mouseY + 2, 0xFFFFFFFF);
		// 5x5 red + inner
		graphics.fill(mouseX - 1, mouseY - 3, mouseX + 1, mouseY + 3, 0xFFFF0000);
		graphics.fill(mouseX - 3, mouseY - 1, mouseX + 3, mouseY + 1, 0xFFFF0000);

	}
}
