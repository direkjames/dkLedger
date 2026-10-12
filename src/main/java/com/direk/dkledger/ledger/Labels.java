package com.direk.dkledger.ledger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reasons other plugins attach to money changes ("quest: daily_3", "afk zone: tier 2"), so the ledger knows
 * why money moved and not only which plugin moved it. Two ways:
 * <ul>
 *   <li>a scope: every change made on the same thread inside it gets the reason (Vault calls are immediate);</li>
 *   <li>an expectation: the player's next change within a few seconds gets the reason (for money that moves
 *       later, e.g. through a command or dkBank).</li>
 * </ul>
 * Thread-safe. Plain Java so it can be tested.
 */
public final class Labels {

    public static final int MAX_LENGTH = 64;

    private final ThreadLocal<Deque<String>> scopes = ThreadLocal.withInitial(ArrayDeque::new);
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final long expectMillis;

    private record Pending(String reason, long until) {
    }

    public Labels(long expectMillis) {
        this.expectMillis = expectMillis;
    }

    /** Starts a scope on this thread. Close it (try-with-resources) when the money calls are done. */
    public Scope open(String reason) {
        String r = clean(reason);
        Deque<String> stack = scopes.get();
        stack.push(r);
        return new Scope(stack, r);
    }

    /** The player's next change within the expect time gets this reason. */
    public void expect(UUID player, String reason, long now) {
        String r = clean(reason);
        if (r.isEmpty()) pending.remove(player);
        else pending.put(player, new Pending(r, now + expectMillis));
    }

    /** The reason for a change happening now: this thread's scope first, then the player's expectation (used up). */
    public String reasonFor(UUID player, long now) {
        Deque<String> stack = scopes.get();
        if (!stack.isEmpty() && !stack.peek().isEmpty()) return stack.peek();
        Pending p = pending.remove(player);
        if (p == null) return "";
        return p.until >= now ? p.reason : "";
    }

    /** Drops expectations nobody used (run now and then). */
    public void forgetOld(long now) {
        pending.values().removeIf(p -> p.until < now);
    }

    static String clean(String reason) {
        if (reason == null) return "";
        String r = reason.strip().replace('\t', ' ').replace('\n', ' ');
        return r.length() <= MAX_LENGTH ? r : r.substring(0, MAX_LENGTH);
    }

    /** An open label. Closing it twice is harmless. */
    public static final class Scope implements AutoCloseable {
        private final Deque<String> stack;
        private final String reason;
        private boolean closed;

        private Scope(Deque<String> stack, String reason) {
            this.stack = stack;
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            // Normally the top; if scopes were closed out of order, remove this one wherever it is.
            if (!stack.isEmpty() && stack.peek() == reason) stack.pop();
            else stack.removeFirstOccurrence(reason);
        }
    }
}
