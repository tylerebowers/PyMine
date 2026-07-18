package com.tylerebowers.client.api;

import com.tylerebowers.client.mixin.KeyMappingAccessor;
import com.tylerebowers.client.mixin.KeyboardHandlerAccess;
import com.tylerebowers.client.mixin.MouseHandlerAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * When NO screen is open, controls are driven through the vanilla
 * KeyMappings (options.keyUp, keyAttack, ...). "isDown" style controls
 * (movement, jump, sneak, sprint, attack, use) are re-asserted every
 * client tick while held so that vanilla's KeyMapping.releaseAll()
 * (called on screen changes) can't silently drop a hold. Click-consuming
 * controls (E, Q, hotbar, and the first click of attack/use) additionally
 * bump the private KeyMapping.clickCount via an accessor mixin.
 *
 * When a screen IS open, input is injected at the GLFW-callback level
 * (KeyboardHandler.keyPress / MouseHandler.onButton / onMove) so that
 * vanilla builds its own KeyEvent/MouseButtonEvent objects and the full
 * screen logic (slot clicks, drags, double-click stacking, ESC, number
 * keys over slots, ...) behaves exactly like a human at the keyboard.
 */
public final class PymineController {

    private static final PymineController INSTANCE = new PymineController();

    public static PymineController get() {
        return INSTANCE;
    }

    private final Minecraft mc = Minecraft.getInstance();

    public static final int DEFAULT_PRESS_TICKS = 4;

    private boolean initialized = false;

    private final Map<String, Boolean> held = new ConcurrentHashMap<>();
    private final Map<String, Integer> timedPresses = new ConcurrentHashMap<>();

    /** While true, real keyboard/mouse input to the game window is discarded. */
    private volatile boolean inputLocked = false;
    /** Set (render thread only) while WE are injecting synthetic events, so the
     * lock mixins can tell API input apart from the user's physical input. */
    private boolean injecting = false;

    private static final Map<String, Integer> GLFW_CODES = new HashMap<>();

    static {
        GLFW_CODES.put("w", GLFW.GLFW_KEY_W);
        GLFW_CODES.put("a", GLFW.GLFW_KEY_A);
        GLFW_CODES.put("s", GLFW.GLFW_KEY_S);
        GLFW_CODES.put("d", GLFW.GLFW_KEY_D);
        GLFW_CODES.put("e", GLFW.GLFW_KEY_E);
        GLFW_CODES.put("q", GLFW.GLFW_KEY_Q);
        GLFW_CODES.put("f", GLFW.GLFW_KEY_F);
        GLFW_CODES.put("shift", GLFW.GLFW_KEY_LEFT_SHIFT);
        GLFW_CODES.put("sprint", GLFW.GLFW_KEY_LEFT_CONTROL);
        GLFW_CODES.put("space", GLFW.GLFW_KEY_SPACE);
        GLFW_CODES.put("esc", GLFW.GLFW_KEY_ESCAPE);
        for (int i = 1; i <= 9; i++) {
            GLFW_CODES.put(String.valueOf(i), GLFW.GLFW_KEY_1 + (i - 1));
        }
    }

    private PymineController() {
    }

    public void onClientTick() {
        if (!initialized) {
            initialized = true;
            mc.options.pauseOnLostFocus = false;
        }

        // Escape hatch: physical PAUSE/BREAK key always unlocks user input,
        // even though every normal key event is being discarded. Uses GLFW's
        // cached key state directly, so it works without the event callbacks.
        if (inputLocked && GLFW.glfwGetKey(mc.getWindow().handle(),
                GLFW.GLFW_KEY_PAUSE) == GLFW.GLFW_PRESS) {
            inputLocked = false;
        }

        boolean screenOpen = currentScreen() != null;

        if (!screenOpen) {
            for (String name : held.keySet()) {
                KeyMapping km = mapping(name);
                if (km != null) {
                    km.setDown(true);
                }
            }
        }

        Iterator<Map.Entry<String, Integer>> it = timedPresses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            int remaining = e.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
                if (!held.containsKey(e.getKey())) {
                    setControlDown(e.getKey(), false);
                }
            } else {
                e.setValue(remaining);
            }
        }
    }

    // Public API

    /**
     * @param key one of: w a s d e q f shift sprint space esc ml mr 1..9
     * @param hold null = press once (auto release), true = press & hold, false = release
     * @param ticks press duration in client ticks for one-shot presses (null = default)
     */
    public void key(String key, Boolean hold, Integer ticks) {
        final String k = key.toLowerCase();
        onGameThread(() -> {
            if (hold == null) {
                if (currentScreen() != null || "esc".equals(k)) {
                    injectPressRelease(k);
                } else {
                    setControlDown(k, true);
                    timedPresses.put(k, ticks != null ? Math.max(1, ticks) : DEFAULT_PRESS_TICKS);
                }
            } else if (hold) {
                held.put(k, true);
                timedPresses.remove(k);
                setControlDown(k, true);
            } else {
                held.remove(k);
                timedPresses.remove(k);
                setControlDown(k, false);
            }
            return null;
        });
    }

    /** Lock or unlock the user's physical keyboard/mouse input to the game
     * window. API-driven input keeps working either way. */
    public void setInputLocked(boolean locked) {
        inputLocked = locked;
    }

    public boolean isInputLocked() {
        return inputLocked;
    }

    /** Called by the input-lock mixins for every real GLFW input event. */
    public boolean shouldBlockUserInput() {
        return inputLocked && !injecting;
    }

    /**
     * Camera control. Pitch is clamped to Minecraft's [-90, 90] range
     * (straight up / straight down); yaw is free and wraps.
     */
    public void look(double pitch, double yaw, boolean relative) {
        onGameThread(() -> {
            LocalPlayer player = mc.player;
            if (player == null) {
                throw new IllegalStateException("No player (not in a world yet)");
            }
            float newYaw;
            float newPitch;
            if (relative) {
                newYaw = (float) (player.getYRot() + yaw);
                newPitch = (float) (player.getXRot() + pitch);
            } else {
                newYaw = (float) yaw;
                newPitch = (float) pitch;
            }
            player.setYRot(Mth.wrapDegrees(newYaw));
            player.setXRot(Mth.clamp(newPitch, -90.0F, 90.0F));
            return null;
        });
    }

    /**
     * Move the mouse cursor while a screen (inventory, crafting, ...) is open.
     * Coordinates are in GUI-scaled pixels, i.e. the same coordinate space the red cursor dot is 
     * drawn in and the same space slot positions live in.
     */
    public void cursor(double x, double y, boolean relative) {
        onGameThread(() -> {
            Screen screen = currentScreen();
            if (screen == null) {
                throw new IllegalStateException("cursor() only works while a menu/screen is open");
            }
            double scale = mc.getWindow().getGuiScale();
            double targetGuiX;
            double targetGuiY;
            if (relative) {
                targetGuiX = mc.mouseHandler.xpos() / scale + x;
                targetGuiY = mc.mouseHandler.ypos() / scale + y;
            } else {
                targetGuiX = x;
                targetGuiY = y;
            }
            targetGuiX = Mth.clamp(targetGuiX, 0, mc.getWindow().getGuiScaledWidth() - 1);
            targetGuiY = Mth.clamp(targetGuiY, 0, mc.getWindow().getGuiScaledHeight() - 1);
            injecting = true;
            try {
                ((MouseHandlerAccess) mc.mouseHandler).pymine$onMove(
                        mc.getWindow().handle(), targetGuiX * scale, targetGuiY * scale);
            } finally {
                injecting = false;
            }
            return null;
        });
    }

    public void releaseAll() {
        onGameThread(() -> {
            for (String k : held.keySet()) {
                setControlDown(k, false);
            }
            for (String k : timedPresses.keySet()) {
                setControlDown(k, false);
            }
            held.clear();
            timedPresses.clear();
            return null;
        });
    }

    public byte[] frame() {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        Path tmp = Files.createTempFile("pymine-frame", ".png");
                        try {
                            image.writeToFile(tmp);
                            future.complete(Files.readAllBytes(tmp));
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Screenshot failed: " + e.getMessage(), e);
        }
    }

    /**
     * Snapshot of the player's statistics (the esc -> Statistics data).
     * Only non-zero entries are included. Stats live on the server, so this
     * first sends a REQUEST_STATS packet (what the vanilla stats screen does)
     * and waits waitMs for the reply to land before reading.
     */
    public Map<String, Map<String, Integer>> stats(int waitMs) {
        onGameThread(() -> {
            if (mc.getConnection() == null || mc.player == null) {
                throw new IllegalStateException("No player (not in a world yet)");
            }
            mc.getConnection().send(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.REQUEST_STATS));
            return null;
        });
        try {
            Thread.sleep(Math.max(0, Math.min(waitMs, 5000)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return onGameThread(() -> {
            LocalPlayer player = mc.player;
            if (player == null) {
                throw new IllegalStateException("No player (not in a world yet)");
            }
            StatsCounter counter = player.getStats();
            Map<String, Map<String, Integer>> out = new TreeMap<>();
            for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
                Map<String, Integer> section = collectStats(type, counter);
                if (!section.isEmpty()) {
                    out.put(String.valueOf(BuiltInRegistries.STAT_TYPE.getKey(type)), section);
                }
            }
            return out;
        });
    }

    private <T> Map<String, Integer> collectStats(StatType<T> type, StatsCounter counter) {
        Map<String, Integer> section = new TreeMap<>();
        for (T value : type.getRegistry()) {
            int v = counter.getValue(type, value);
            if (v != 0) {
                section.put(String.valueOf(type.getRegistry().getKey(value)), v);
            }
        }
        return section;
    }

    public Map<String, Object> state() {
        return onGameThread(() -> {
            Map<String, Object> out = new HashMap<>();
            Screen screen = currentScreen();
            out.put("in_menu", screen != null);
            out.put("screen", screen != null ? screen.getClass().getSimpleName() : null);
            double scale = mc.getWindow().getGuiScale();
            out.put("cursor_x", mc.mouseHandler.xpos() / scale);
            out.put("cursor_y", mc.mouseHandler.ypos() / scale);
            out.put("gui_width", mc.getWindow().getGuiScaledWidth());
            out.put("gui_height", mc.getWindow().getGuiScaledHeight());
            out.put("frame_width", mc.getWindow().getWidth());
            out.put("frame_height", mc.getWindow().getHeight());
            LocalPlayer p = mc.player;
            out.put("has_player", p != null);
            if (p != null) {
                out.put("yaw", p.getYRot());
                out.put("pitch", p.getXRot());
                out.put("alive", p.isAlive());
            }
            out.put("held", held.keySet().toArray(new String[0]));
            out.put("input_locked", inputLocked);
            return out;
        });
    }

    // Internals

    private Screen currentScreen() {
        return mc.gui.screen();
    }

    private void setControlDown(String key, boolean down) {
        if (key.equals("ml") || key.equals("mr")) {
            int button = key.equals("ml") ? GLFW.GLFW_MOUSE_BUTTON_LEFT : GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            if (currentScreen() != null) {
                injecting = true;
                try {
                    ((MouseHandlerAccess) mc.mouseHandler).pymine$onButton(
                            mc.getWindow().handle(), new MouseButtonInfo(button, 0),
                            down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
                } finally {
                    injecting = false;
                }
            } else {
                KeyMapping km = key.equals("ml") ? mc.options.keyAttack : mc.options.keyUse;
                km.setDown(down);
                if (down) {
                    bumpClick(km);
                }
            }
            return;
        }

        if (currentScreen() != null) {
            Integer code = GLFW_CODES.get(key);
            if (code != null) {
                injectKey(code, down);
            }
            return;
        }

        KeyMapping km = mapping(key);
        if (km == null) {
            throw new IllegalArgumentException("Unknown key: " + key);
        }
        km.setDown(down);
        if (down) {
            bumpClick(km);
        }
    }

    private void injectPressRelease(String key) {
        if (key.equals("ml") || key.equals("mr")) {
            setControlDown(key, true);
            setControlDown(key, false);
            return;
        }
        Integer code = GLFW_CODES.get(key);
        if (code == null) {
            throw new IllegalArgumentException("Unknown key: " + key);
        }
        injectKey(code, true);
        injectKey(code, false);
    }

    private void injectKey(int glfwCode, boolean down) {
        injecting = true;
        try {
            ((KeyboardHandlerAccess) mc.keyboardHandler).pymine$keyPress(
                    mc.getWindow().handle(),
                    down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE,
                    new KeyEvent(glfwCode, 0, 0));
        } finally {
            injecting = false;
        }
    }

    private void bumpClick(KeyMapping km) {
        KeyMappingAccessor acc = (KeyMappingAccessor) km;
        acc.pymine$setClickCount(acc.pymine$getClickCount() + 1);
    }

    private KeyMapping mapping(String key) {
        return switch (key) {
            case "w" -> mc.options.keyUp;
            case "a" -> mc.options.keyLeft;
            case "s" -> mc.options.keyDown;
            case "d" -> mc.options.keyRight;
            case "space" -> mc.options.keyJump;
            case "shift" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "e" -> mc.options.keyInventory;
            case "q" -> mc.options.keyDrop;
            case "f" -> mc.options.keySwapOffhand;
            case "1", "2", "3", "4", "5", "6", "7", "8", "9" ->
                    mc.options.keyHotbarSlots[Integer.parseInt(key) - 1];
            default -> null;
        };
    }

    /** Run on the render thread and wait for the result. */
    private <T> T onGameThread(Supplier<T> task) {
        if (mc.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }
}
