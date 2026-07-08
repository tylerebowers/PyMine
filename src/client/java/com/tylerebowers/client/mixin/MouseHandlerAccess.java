package com.tylerebowers.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Inject synthetic mouse events at the GLFW-callback level, so
 * vanilla constructs its own click events and screens receive
 * clicks/moves/drags like a physical mouse.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccess {

	@Invoker("onButton")
	void pymine$onButton(long window, MouseButtonInfo info, int action);

	@Invoker("onMove")
	void pymine$onMove(long window, double xpos, double ypos);
}
