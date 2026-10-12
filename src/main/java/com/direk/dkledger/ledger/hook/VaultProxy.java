package com.direk.dkledger.ledger.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sits in front of the real Vault economy (EssentialsX) and passes every call through unchanged. Deposits and
 * withdrawals that succeed are reported to the ledger with the plugin that made them.
 * <p>
 * Registered in onLoad at the highest priority, so plugins that look up the economy while enabling get this.
 * The real economy is looked up on each use until found, so load order doesn't matter. Recording can never
 * break a payment: every recording step is wrapped, and the money moves first.
 */
public final class VaultProxy implements Economy {

    /** What the ledger needs from a payment. */
    public interface Recorder {
        /** @return the source paying, worked out once per payment (also used for freezes) */
        String source(String method);

        /** @return true if this payment must be refused (the player is frozen for this source) */
        boolean blocked(UUID player, String source);

        void record(UUID player, String name, double amount, String source, String detail, double balance);
    }

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final Logger log;
    private volatile Economy cached;
    private volatile Recorder recorder;
    private volatile boolean warned;

    public VaultProxy(Logger log) {
        this.log = log;
    }

    /** True while this thread is inside a payment made through the proxy (so Essentials' own event isn't counted twice). */
    public static boolean inCall() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Runs a payment that dkLedger records itself (a staff reverse), so neither the proxy nor Essentials' event
     * records it a second time.
     */
    public static <T> T untracked(java.util.function.Supplier<T> payment) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return payment.get();
        } finally {
            depth[0]--;
        }
    }

    public void recorder(Recorder recorder) {
        this.recorder = recorder;
    }

    /** Forget the cached economy; called when economies register or unregister. */
    public void refresh() {
        cached = null;
    }

    /** The real economy, or null if there's none yet. */
    public Economy delegate() {
        Economy e = cached;
        if (e != null) return e;
        java.util.Collection<RegisteredServiceProvider<Economy>> list = Bukkit.getServicesManager().getRegistrations(Economy.class);
        for (RegisteredServiceProvider<Economy> r : list) {
            if (!(r.getProvider() instanceof VaultProxy)) {
                cached = r.getProvider();
                return cached;
            }
        }
        return null;
    }

    private Economy need() {
        Economy e = delegate();
        if (e == null && !warned) {
            warned = true;
            log.warning("No economy plugin found behind Vault yet; payments fail until one is installed.");
        }
        return e;
    }

    // ------------------------------------------------------------------ money in and out (recorded)

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return move(player, null, amount, true, "depositPlayer");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return move(player, world, amount, true, "depositPlayer");
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String name, double amount) {
        return move(offline(name), null, amount, true, "depositPlayer(name)");
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return move(offline(name), world, amount, true, "depositPlayer(name)");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return move(player, null, amount, false, "withdrawPlayer");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return move(player, world, amount, false, "withdrawPlayer");
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return move(offline(name), null, amount, false, "withdrawPlayer(name)");
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return move(offline(name), world, amount, false, "withdrawPlayer(name)");
    }

    private EconomyResponse move(OfflinePlayer player, String world, double amount, boolean deposit, String method) {
        Economy e = need();
        if (e == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "No economy plugin");
        Recorder r = recorder;
        String source = null;
        if (r != null && player != null) {
            try {
                source = r.source(method);
                if (!deposit && r.blocked(player.getUniqueId(), source)) {
                    return new EconomyResponse(0, e.getBalance(player), EconomyResponse.ResponseType.FAILURE,
                            "Your money is frozen by staff");
                }
            } catch (RuntimeException ex) {
                problem(ex);
            }
        }
        EconomyResponse response;
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            if (deposit) response = world == null ? e.depositPlayer(player, amount) : e.depositPlayer(player, world, amount);
            else response = world == null ? e.withdrawPlayer(player, amount) : e.withdrawPlayer(player, world, amount);
        } finally {
            depth[0]--;
        }
        if (r != null && source != null && player != null && response != null && response.transactionSuccess() && response.amount != 0) {
            try {
                String name = player.getName() == null ? "" : player.getName();
                r.record(player.getUniqueId(), name, deposit ? response.amount : -response.amount, source, method, response.balance);
            } catch (RuntimeException ex) {
                problem(ex);
            }
        }
        return response;
    }

    private void problem(RuntimeException ex) {
        log.log(Level.WARNING, "Couldn't record a payment (the payment itself went through)", ex);
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer offline(String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null ? cached : Bukkit.getOfflinePlayer(name);
    }

    // ------------------------------------------------------------------ everything else: passed straight through

    @Override
    public boolean isEnabled() {
        Economy e = delegate();
        return e != null && e.isEnabled();
    }

    @Override
    public String getName() {
        Economy e = delegate();
        return e == null ? "dkLedger (no economy)" : e.getName();
    }

    @Override
    public boolean hasBankSupport() {
        Economy e = delegate();
        return e != null && e.hasBankSupport();
    }

    @Override
    public int fractionalDigits() {
        Economy e = delegate();
        return e == null ? 2 : e.fractionalDigits();
    }

    @Override
    public String format(double amount) {
        Economy e = delegate();
        return e == null ? String.valueOf(amount) : e.format(amount);
    }

    @Override
    public String currencyNamePlural() {
        Economy e = delegate();
        return e == null ? "" : e.currencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        Economy e = delegate();
        return e == null ? "" : e.currencyNameSingular();
    }

    @Override
    @Deprecated
    public boolean hasAccount(String name) {
        Economy e = delegate();
        return e != null && e.hasAccount(name);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        Economy e = delegate();
        return e != null && e.hasAccount(player);
    }

    @Override
    @Deprecated
    public boolean hasAccount(String name, String world) {
        Economy e = delegate();
        return e != null && e.hasAccount(name, world);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        Economy e = delegate();
        return e != null && e.hasAccount(player, world);
    }

    @Override
    @Deprecated
    public double getBalance(String name) {
        Economy e = delegate();
        return e == null ? 0 : e.getBalance(name);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        Economy e = delegate();
        return e == null ? 0 : e.getBalance(player);
    }

    @Override
    @Deprecated
    public double getBalance(String name, String world) {
        Economy e = delegate();
        return e == null ? 0 : e.getBalance(name, world);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        Economy e = delegate();
        return e == null ? 0 : e.getBalance(player, world);
    }

    @Override
    @Deprecated
    public boolean has(String name, double amount) {
        Economy e = delegate();
        return e != null && e.has(name, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Economy e = delegate();
        return e != null && e.has(player, amount);
    }

    @Override
    @Deprecated
    public boolean has(String name, String world, double amount) {
        Economy e = delegate();
        return e != null && e.has(name, world, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        Economy e = delegate();
        return e != null && e.has(player, world, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse createBank(String name, String player) {
        Economy e = need();
        return e == null ? noEconomy() : e.createBank(name, player);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        Economy e = need();
        return e == null ? noEconomy() : e.createBank(name, player);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        Economy e = need();
        return e == null ? noEconomy() : e.deleteBank(name);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        Economy e = need();
        return e == null ? noEconomy() : e.bankBalance(name);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        Economy e = need();
        return e == null ? noEconomy() : e.bankHas(name, amount);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        Economy e = need();
        return e == null ? noEconomy() : e.bankWithdraw(name, amount);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        Economy e = need();
        return e == null ? noEconomy() : e.bankDeposit(name, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse isBankOwner(String name, String player) {
        Economy e = need();
        return e == null ? noEconomy() : e.isBankOwner(name, player);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        Economy e = need();
        return e == null ? noEconomy() : e.isBankOwner(name, player);
    }

    @Override
    @Deprecated
    public EconomyResponse isBankMember(String name, String player) {
        Economy e = need();
        return e == null ? noEconomy() : e.isBankMember(name, player);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        Economy e = need();
        return e == null ? noEconomy() : e.isBankMember(name, player);
    }

    @Override
    public List<String> getBanks() {
        Economy e = delegate();
        return e == null ? List.of() : e.getBanks();
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String name) {
        Economy e = delegate();
        return e != null && e.createPlayerAccount(name);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        Economy e = delegate();
        return e != null && e.createPlayerAccount(player);
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String name, String world) {
        Economy e = delegate();
        return e != null && e.createPlayerAccount(name, world);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        Economy e = delegate();
        return e != null && e.createPlayerAccount(player, world);
    }

    private static EconomyResponse noEconomy() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "No economy plugin");
    }
}
