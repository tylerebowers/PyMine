package com.tylerebowers.client.mixin;

import com.tylerebowers.client.api.PymineController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(at = @At("HEAD"), method = "tick")
	private void pymine$onTick(CallbackInfo info) {
		PymineController.get().onClientTick();
	}
}
