package com.direk.dkledger.web;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may see the dashboard. There are no passwords: /ledger web gives a one-time login key (in a link) that
 * works once, for a few minutes. Opening it starts a session, kept in a cookie, that lasts a set number of hours.
 * Only hashes of keys and sessions are kept, and everything is in memory: a restart signs everyone out.
 * Thread-safe; plain Java so it can be tested.
 */
public final class WebSessions {

    /** A signed-in person. {@code player} is null for the console. */
    public record Session(UUID player, String name, long expires) {
    }

    private record Key(UUID player, String name, long expires) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Key> keys = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile long keyMillis;
    private volatile long sessionMillis;

    public WebSessions(long keyMillis, long sessionMillis) {
        configure(keyMillis, sessionMillis);
    }

    public void configure(long keyMillis, long sessionMillis) {
        this.keyMillis = keyMillis;
        this.sessionMillis = sessionMillis;
    }

    /** A new one-time login key for this person. Their older unused keys stop working. */
    public String issueKey(UUID player, String name, long now) {
        keys.values().removeIf(k -> java.util.Objects.equals(k.player, player) && k.name.equals(name));
        String key = token();
        keys.put(hash(key), new Key(player, name, now + keyMillis));
        return key;
    }

    /**
     * Uses up a login key and starts a session. Returns the session's token (for the cookie), or empty if the
     * key is wrong, used or too old.
     */
    public Optional<String> redeem(String key, long now) {
        if (key == null || key.isEmpty() || key.length() > 100) return Optional.empty();
        Key k = keys.remove(hash(key));
        if (k == null || k.expires < now) return Optional.empty();
        String token = token();
        sessions.put(hash(token), new Session(k.player, k.name, now + sessionMillis));
        return Optional.of(token);
    }

    public Optional<Session> session(String token, long now) {
        if (token == null || token.isEmpty() || token.length() > 100) return Optional.empty();
        String h = hash(token);
        Session s = sessions.get(h);
        if (s == null) return Optional.empty();
        if (s.expires < now) {
            sessions.remove(h);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void logout(String token) {
        if (token != null) sessions.remove(hash(token));
    }

    /** Ends one person's sessions (when they lose permission). */
    public void logout(UUID player) {
        if (player != null) sessions.values().removeIf(s -> player.equals(s.player));
    }

    /** Signs everyone out and voids every unused key. Returns how many sessions ended. */
    public int logoutAll() {
        int n = sessions.size();
        sessions.clear();
        keys.clear();
        return n;
    }

    public int active(long now) {
        cleanup(now);
        return sessions.size();
    }

    public void cleanup(long now) {
        keys.values().removeIf(k -> k.expires < now);
        sessions.values().removeIf(s -> s.expires < now);
    }

    /** 32 random bytes as URL-safe text. */
    static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
