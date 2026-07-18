package com.tylerebowers.client.mixin;

import com.tylerebowers.client.api.PymineController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When input lock is enabled (POST /lock), every REAL keyboard/mouse event
 * from the OS is discarded at the GLFW-callback entry points, so a user
 * clicking or typing into the game window cannot disturb the agent.
 *
 * API-driven input still works because PymineController raises its
 * "injecting" flag around its own synthetic calls into these same methods
 * (both real callbacks and our injections run on the render thread, so a
 * plain flag is race-free).
 *
 * Escape hatch: the PAUSE/BREAK key always unlocks (polled directly from
 * GLFW key state in PymineController.onClientTick, so it bypasses these
 * cancelled callbacks). Handlers capture no target arguments on purpose —
 * mixin allows arg-less @Inject handlers, which keeps this immune to any
 * future event-object signature changes.
 */
public final class InputLockMixin {

	private InputLockMixin() {
	}

	@Mixin(MouseHandler.class)
	public static class MouseLock {

		@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
		private void pymine$lockButton(CallbackInfo ci) {
			if (PymineController.get().shouldBlockUserInput()) {
				ci.cancel();
			}
		}

		@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
		private void pymine$lockMove(CallbackInfo ci) {
			if (PymineController.get().shouldBlockUserInput()) {
				ci.cancel();
			}
		}

		@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
		private void pymine$lockScroll(CallbackInfo ci) {
			if (PymineController.get().shouldBlockUserInput()) {
				ci.cancel();
			}
		}
	}

	@Mixin(KeyboardHandler.class)
	public static class KeyboardLock {

		@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
		private void pymine$lockKey(CallbackInfo ci) {
			if (PymineController.get().shouldBlockUserInput()) {
				ci.cancel();
			}
		}

		@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
		private void pymine$lockChar(CallbackInfo ci) {
			if (PymineController.get().shouldBlockUserInput()) {
				ci.cancel();
			}
		}
	}
}
