package com.direk.dkledger.supply;

import com.direk.dkcore.bank.BankService;
import com.direk.dkcore.economy.EconomyService;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.detect.SupplyMath;
import com.direk.dkledger.ledger.LedgerStore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts everyone's money: every player dkCore has seen, wallet (Vault) plus bank (dkBank). Wallets are read
 * a few players per tick on the main thread so a big player list never lags the server; bank balances come
 * from dkBank's database in the background.
 */
public final class SupplyService {

    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;
    private final LedgerStore store;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Snapshot latest;
    private volatile int perTick = 20;

    public SupplyService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(int balancesPerTick) {
        this.perTick = Math.max(1, balancesPerTick);
    }

    public Snapshot latest() {
        return latest;
    }

    public CompletableFuture<Void> loadLatest() {
        return store.snapshotAtOrBefore(Long.MAX_VALUE).thenAccept(s -> s.ifPresent(v -> latest = v));
    }

    /** Takes a snapshot and saves it. Completes on the main thread; empty if one is already running. */
    public CompletableFuture<Optional<Snapshot>> take() {
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<Snapshot>> done = new CompletableFuture<>();
        store.players().whenComplete((players, err) -> plugin.tasks().sync(() -> {
            if (err != null) {
                running.set(false);
                done.completeExceptionally(err);
                return;
            }
            readWallets(players, done);
        }));
        return done;
    }

    private void readWallets(List<LedgerStore.KnownPlayer> players, CompletableFuture<Optional<Snapshot>> done) {
        Optional<EconomyService> economy = plugin.economy().get();
        double[] wallets = new double[players.size()];
        int[] index = {0};
        BukkitTask[] task = new BukkitTask[1];
        // Not tasks().timer: /ledger reload cancels those, and a count in progress must finish.
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                for (int n = 0; n < perTick && index[0] < players.size(); n++, index[0]++) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(players.get(index[0]).uuid());
                    wallets[index[0]] = economy.map(e -> e.balance(p)).orElse(0.0);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Couldn't read a wallet balance: " + e.getMessage());
                index[0]++;
            }
            if (index[0] >= players.size()) {
                task[0].cancel();
                readBanks(players, wallets, done);
            }
        }, 1L, 1L);
    }

    private void readBanks(List<LedgerStore.KnownPlayer> players, double[] wallets, CompletableFuture<Optional<Snapshot>> done) {
        Optional<BankService> bank = plugin.bank().get();
        double[] banks = new double[players.size()];
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        if (bank.isPresent()) {
            for (int i = 0; i < players.size(); i++) {
                int at = i;
                OptionalDouble cached = bank.get().cachedBalance(players.get(i).uuid());
                if (cached.isPresent()) banks[i] = cached.getAsDouble();
                else reads.add(bank.get().balance(players.get(i).uuid()).thenAccept(b -> banks[at] = b.orElse(0)));
            }
        }
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> plugin.tasks().sync(() -> {
            try {
                long now = System.currentTimeMillis();
                double walletTotal = 0;
                double bankTotal = 0;
                double[] totals = new double[players.size()];
                int active = 0;
                for (int i = 0; i < players.size(); i++) {
                    walletTotal += wallets[i];
                    bankTotal += banks[i];
                    totals[i] = wallets[i] + banks[i];
                    if (now - players.get(i).lastSeen() <= DAY || Bukkit.getPlayer(players.get(i).uuid()) != null) active++;
                }
                Snapshot s = new Snapshot(now, walletTotal, bankTotal, players.size(), active,
                        SupplyMath.topShare(totals, 0.10), SupplyMath.gini(totals));
                latest = s;
                store.saveSnapshot(s);
                done.complete(Optional.of(s));
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            } finally {
                running.set(false);
            }
        }));
    }
}
