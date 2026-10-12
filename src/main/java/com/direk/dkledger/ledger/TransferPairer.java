package com.direk.dkledger.ledger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/**
 * Finds the other player of a transfer. A /pay, a trade or an auction takes money from one player and gives
 * (about) the same amount to another, from the same source, a moment apart. This remembers recent
 * withdrawals and matches a deposit to the newest one from the same source with the same amount (or up to
 * {@code maxFee} less, for taxes).
 */
public final class TransferPairer {

    private record Pending(String source, UUID player, double amount, long time) {
    }

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final long windowMillis;
    private final double maxFee;

    /**
     * @param windowMillis how far apart the two halves can be
     * @param maxFee       the largest share (0-1) of the amount that may be missing on the receiving side
     */
    public TransferPairer(long windowMillis, double maxFee) {
        this.windowMillis = windowMillis;
        this.maxFee = maxFee;
    }

    /** Remembers money leaving {@code player}. */
    public synchronized void sent(String source, UUID player, double amount, long now) {
        prune(now);
        pending.addLast(new Pending(source, player, amount, now));
        while (pending.size() > 256) pending.removeFirst();
    }

    /** @return who sent the money {@code player} just got, or null if it doesn't look like a transfer */
    public synchronized UUID received(String source, UUID player, double amount, long now) {
        prune(now);
        Iterator<Pending> it = pending.descendingIterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!p.source.equals(source) || p.player.equals(player)) continue;
            if (amount <= p.amount + 0.001 && amount >= p.amount * (1 - maxFee) - 0.001) {
                it.remove();
                return p.player;
            }
        }
        return null;
    }

    private void prune(long now) {
        while (!pending.isEmpty() && now - pending.peekFirst().time > windowMillis) pending.removeFirst();
    }
}
