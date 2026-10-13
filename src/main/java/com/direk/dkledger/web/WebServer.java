package com.direk.dkledger.web;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.command.LedgerCommand;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The web dashboard: a small web server inside dkLedger (Java's own, no extra libraries) that serves one page
 * and read-only JSON. Nothing on it can change the economy.
 * <p>
 * Sign-in is a one-time link from /ledger web; the session is a cookie. Data is read on the main thread (like
 * /ledger does) and the web threads wait for it, so the server never blocks on a slow browser.
 */
public final class WebServer {

    public static final String COOKIE = "dkledger_session";
    private static final int MAX_FAILED_LOGINS_PER_MINUTE = 10;

    private final DkLedger plugin;
    private final WebSessions sessions = new WebSessions(5 * 60_000L, 12 * 3_600_000L);
    private final WebData data;
    private final Map<String, int[]> failedLogins = new ConcurrentHashMap<>();
    private volatile long failedWindow;
    private HttpServer server;
    private ExecutorService pool;
    private volatile String publicUrl = "";
    private volatile boolean secureCookie;
    private volatile boolean trustProxy;
    private volatile int sessionHours = 12;
    private String running = "";

    public WebServer(DkLedger plugin) {
        this.plugin = plugin;
        this.data = new WebData(plugin);
    }

    public WebSessions sessions() {
        return sessions;
    }

    public boolean running() {
        return server != null;
    }

    /**
     * Starts, restarts or stops the server to match config.yml. Returns a problem to show, or null.
     * Main thread.
     */
    public String configure(boolean enabled, String bind, int port, String url, int sessionHours, boolean proxy) {
        this.sessionHours = Math.max(1, sessionHours);
        sessions.configure(5 * 60_000L, this.sessionHours * 3_600_000L);
        this.trustProxy = proxy;
        String base = url == null || url.isBlank() ? "http://localhost:" + port : url.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.publicUrl = base;
        this.secureCookie = base.toLowerCase(Locale.ROOT).startsWith("https://");
        String wanted = enabled ? bind + ":" + port : "";
        if (wanted.equals(running) && (server != null) == enabled) return null;
        stop();
        if (!enabled) return null;
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 32);
            AtomicInteger n = new AtomicInteger();
            pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "dkLedger-web-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(pool);
            s.createContext("/", this::handle);
            s.start();
            server = s;
            running = wanted;
            plugin.getLogger().info("Web dashboard on port " + port + " (" + publicUrl + "). Sign in with /ledger web.");
            return null;
        } catch (IOException | RuntimeException e) {
            stop();
            return "The web dashboard couldn't start on " + bind + ":" + port + ": " + e.getMessage()
                    + ". Is the port free and given to this server (Pterodactyl: an allocation)?";
        } catch (LinkageError e) {
            stop();
            return "The web dashboard needs Java's built-in web server (module jdk.httpserver), which this Java doesn't have.";
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        running = "";
    }

    /** A login link for this person. */
    public String loginLink(UUID player, String name) {
        return publicUrl + "/login?key=" + sessions.issueKey(player, name, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ requests

    private void handle(HttpExchange ex) {
        try (ex) {
            security(ex.getResponseHeaders());
            String path = ex.getRequestURI().getPath();
            // Behind an HTTPS proxy, a visit straight to the port (plain http) is sent to the https address.
            if (trustProxy && secureCookie && !"https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"))
                    && ex.getRequestHeaders().getFirst("CF-Connecting-IP") == null) {
                ex.getResponseHeaders().set("Location", publicUrl + (path.startsWith("/") ? path : "/"));
                ex.sendResponseHeaders(301, -1);
                return;
            }
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if (path.equals("/logout") && method.equals("POST")) {
                if (!sameOrigin(ex)) {
                    send(ex, 403, "text/plain", "Forbidden");
                    return;
                }
                sessions.logout(cookie(ex));
                ex.getResponseHeaders().add("Set-Cookie", cookieHeader("", 0));
                send(ex, 204, null, null);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain", "Method not allowed");
                return;
            }
            switch (path) {
                case "/", "/index.html" -> resource(ex, "web/index.html", "text/html; charset=utf-8");
                case "/app.js" -> resource(ex, "web/app.js", "text/javascript; charset=utf-8");
                case "/app.css" -> resource(ex, "web/app.css", "text/css; charset=utf-8");
                case "/favicon.ico" -> send(ex, 204, null, null);
                case "/login" -> login(ex);
                default -> {
                    if (path.startsWith("/api/")) api(ex, path.substring(5));
                    else send(ex, 404, "text/plain", "Not found");
                }
            }
        } catch (RuntimeException | IOException e) {
            plugin.getLogger().log(Level.FINE, "Web request failed", e);
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String ip = client(ex);
        if (tooManyFailures(ip)) {
            redirect(ex, "/?signin=busy");
            return;
        }
        Optional<String> token = sessions.redeem(query(ex).get("key"), System.currentTimeMillis());
        if (token.isEmpty()) {
            failed(ip);
            redirect(ex, "/?signin=expired");
            return;
        }
        ex.getResponseHeaders().add("Set-Cookie", cookieHeader(token.get(), sessionHours * 3600));
        redirect(ex, "/"); // the key leaves the address bar (and the browser history) right away
    }

    private void api(HttpExchange ex, String what) throws IOException {
        Optional<WebSessions.Session> session = sessions.session(cookie(ex), System.currentTimeMillis());
        if (session.isEmpty()) {
            json(ex, 401, Json.obj("error", "signin"));
            return;
        }
        WebSessions.Session s = session.get();
        Map<String, String> q = query(ex);
        int days = days(q.get("days"));
        Supplier<CompletableFuture<Object>> work = switch (what) {
            case "me" -> () -> data.me(s);
            case "overview" -> () -> data.overview(days);
            case "sources" -> () -> data.sources(days);
            case "top" -> () -> data.top(days);
            case "items" -> () -> data.items(days);
            case "reasons" -> () -> data.reasons(days);
            case "alerts" -> data::alerts;
            case "player" -> () -> data.player(q.get("name"));
            default -> null;
        };
        if (work == null) {
            json(ex, 404, Json.obj("error", "unknown"));
            return;
        }
        Object result;
        try {
            result = onMain(s, work).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            json(ex, 503, Json.obj("error", "busy"));
            return;
        } catch (Exception e) {
            if (causedBy(e, SecurityException.class)) {
                sessions.logout(s.player());
                json(ex, 401, Json.obj("error", "signin"));
                return;
            }
            plugin.getLogger().log(Level.WARNING, "Dashboard request /api/" + what + " failed", e);
            json(ex, 500, Json.obj("error", "failed"));
            return;
        }
        if (result == null && what.equals("player")) {
            json(ex, 404, Json.obj("error", "noplayer"));
            return;
        }
        json(ex, 200, result);
    }

    /**
     * Starts the work on the main thread. First checks that the person still has dkledger.admin (when they're
     * online; offline, their session simply runs out).
     */
    private CompletableFuture<Object> onMain(WebSessions.Session s, Supplier<CompletableFuture<Object>> work) {
        CompletableFuture<CompletableFuture<Object>> hop = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (s.player() != null) {
                    Player p = Bukkit.getPlayer(s.player());
                    if (p != null && !p.hasPermission(LedgerCommand.ADMIN)) throw new SecurityException("no permission");
                }
                hop.complete(work.get());
            } catch (RuntimeException e) {
                hop.completeExceptionally(e);
            }
        });
        return hop.thenCompose(f -> f);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
        return false;
    }

    private static int days(String text) {
        try {
            return Math.max(1, Math.min(90, Integer.parseInt(text)));
        } catch (RuntimeException e) {
            return 7;
        }
    }

    private static void security(Headers h) {
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
    }

    private String cookieHeader(String value, int maxAge) {
        return COOKIE + "=" + value + "; Path=/; Max-Age=" + Math.max(0, maxAge) + "; HttpOnly; SameSite=Lax"
                + (secureCookie ? "; Secure" : "");
    }

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String p = part.strip();
                if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    /** Logout must come from the dashboard itself, not from another site. */
    private static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // the cookie is SameSite=Lax, so another site's post has no session anyway
        String host = ex.getRequestHeaders().getFirst("Host");
        try {
            String originHost = URI.create(origin).getAuthority();
            return host != null && host.equalsIgnoreCase(originHost);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // a broken value is skipped
            }
        }
        return out;
    }

    private String client(HttpExchange ex) {
        if (trustProxy) {
            // X-Real-IP is set by nginx itself (a visitor can't fake it through the proxy); X-Forwarded-For is the fallback.
            String forwarded = ex.getRequestHeaders().getFirst("CF-Connecting-IP");
            if (forwarded == null) forwarded = ex.getRequestHeaders().getFirst("X-Real-IP");
            if (forwarded == null) forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
        }
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean tooManyFailures(String ip) {
        long minute = System.currentTimeMillis() / 60_000;
        if (minute != failedWindow) {
            failedWindow = minute;
            failedLogins.clear();
        }
        int[] n = failedLogins.get(ip);
        return n != null && n[0] >= MAX_FAILED_LOGINS_PER_MINUTE;
    }

    private void failed(String ip) {
        failedLogins.computeIfAbsent(ip, k -> new int[1])[0]++;
    }

    private void resource(HttpExchange ex, String name, String type) throws IOException {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                send(ex, 404, "text/plain", "Not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            write(ex, 200, body);
        }
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(303, -1);
    }

    private static void json(HttpExchange ex, int status, Object value) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        write(ex, status, Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        if (text == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        write(ex, status, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange ex, int status, byte[] body) throws IOException {
        boolean head = ex.getRequestMethod().equalsIgnoreCase("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
