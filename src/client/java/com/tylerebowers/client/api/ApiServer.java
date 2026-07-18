package com.tylerebowers.client.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny localhost HTTP server that exposes {@link PymineController}.
 * Endpoints (all JSON unless noted):
 *   GET  /frame -> image/png screenshot of the framebuffer
 *   GET  /state -> status blob (menu open, cursor, rotation, ...)
 *   POST /key {key, hold?, ticks?} -> keyboard/mouse-button control
 *   POST /look {pitch, yaw, relative?}
 *   POST /cursor {x, y, relative?} -> move GUI cursor (menus only)
 *   POST /release_all
 */
public final class ApiServer {

    private static final Gson GSON = new Gson();
    private static HttpServer server;

    private ApiServer() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        int port = Integer.getInteger("pymine.port", 8765);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Pymine: failed to bind API port " + port, e);
        }
        PymineController controller = PymineController.get();

        server.createContext("/frame", exchange -> handle(exchange, () -> {
            byte[] png = controller.frame();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            return null;
        }));

        server.createContext("/state", exchange -> handle(exchange, () -> {
            sendJson(exchange, 200, GSON.toJson(controller.state()));
            return null;
        }));

        server.createContext("/key", exchange -> handle(exchange, () -> {
            JsonObject body = readBody(exchange);
            String key = body.get("key").getAsString();
            Boolean hold = body.has("hold") && !body.get("hold").isJsonNull()
                    ? body.get("hold").getAsBoolean() : null;
            Integer ticks = body.has("ticks") && !body.get("ticks").isJsonNull()
                    ? body.get("ticks").getAsInt() : null;
            controller.key(key, hold, ticks);
            ok(exchange);
            return null;
        }));

        server.createContext("/look", exchange -> handle(exchange, () -> {
            JsonObject body = readBody(exchange);
            double pitch = body.has("pitch") ? body.get("pitch").getAsDouble() : 0;
            double yaw = body.has("yaw") ? body.get("yaw").getAsDouble() : 0;
            boolean relative = !body.has("relative") || body.get("relative").getAsBoolean();
            controller.look(pitch, yaw, relative);
            ok(exchange);
            return null;
        }));

        server.createContext("/cursor", exchange -> handle(exchange, () -> {
            JsonObject body = readBody(exchange);
            double x = body.get("x").getAsDouble();
            double y = body.get("y").getAsDouble();
            boolean relative = body.has("relative") && body.get("relative").getAsBoolean();
            controller.cursor(x, y, relative);
            ok(exchange);
            return null;
        }));

        server.createContext("/stats", exchange -> handle(exchange, () -> {
            int waitMs = 250;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("wait=")) {
                        waitMs = Integer.parseInt(part.substring(5));
                    }
                }
            }
            sendJson(exchange, 200, GSON.toJson(controller.stats(waitMs)));
            return null;
        }));

        server.createContext("/lock", exchange -> handle(exchange, () -> {
            JsonObject body = readBody(exchange);
            boolean locked = body.has("locked") && body.get("locked").getAsBoolean();
            controller.setInputLocked(locked);
            ok(exchange);
            return null;
        }));

        server.createContext("/release_all", exchange -> handle(exchange, () -> {
            controller.releaseAll();
            ok(exchange);
            return null;
        }));

        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "pymine-api");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[Pymine] API listening on http://127.0.0.1:" + port);
    }

    // ------------------------------------------------------------------

    private interface Handler {
        Void run() throws Exception;
    }

    private static void handle(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.run();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", msg);
            sendJson(exchange, 400, GSON.toJson(err));
        } finally {
            exchange.close();
        }
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void ok(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, GSON.toJson(Map.of("ok", true)));
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
