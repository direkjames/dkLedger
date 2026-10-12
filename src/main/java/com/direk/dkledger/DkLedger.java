package com.direk.dkledger;

import com.direk.dkcore.DkPlugin;
import com.direk.dkledger.admin.FreezeService;
import com.direk.dkledger.admin.LedgerActions;
import com.direk.dkledger.admin.LoginTracker;
import com.direk.dkledger.api.LedgerApi;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.command.LedgerCommand;
import com.direk.dkledger.config.LedgerConfig;
import com.direk.dkledger.detect.AlertService;
import com.direk.dkledger.detect.DetectorService;
import com.direk.dkledger.detect.LiveWatch;
import com.direk.dkledger.gui.Menus;
import com.direk.dkledger.ledger.CommandTracker;
import com.direk.dkledger.ledger.LedgerService;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.ledger.RollupBuffer;
import com.direk.dkledger.ledger.SourceResolver;
import com.direk.dkledger.ledger.Sources;
import com.direk.dkledger.ledger.hook.VaultProxy;
import com.direk.dkledger.report.WeeklyService;
import com.direk.dkledger.scan.ScanService;
import com.direk.dkledger.supply.SupplyService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * dkLedger - the dk suite's financial analyst: a live ledger of every money change, money supply snapshots,
 * alerts, admin actions, the config audit and a "what if" simulator. Built on dkCore.
 * <p>
 * Author: direk james
 */
public final class DkLedger extends DkPlugin {

    private Object vaultProxy; // a VaultProxy when Vault is installed (Object so Vault stays optional)
    private volatile LedgerConfig config;
    private LedgerStore store;
    private LedgerService ledger;
    private LiveWatch watch;
    private AlertService alerts;
    private FreezeService freezes;
    private LoginTracker logins;
    private SupplyService supply;
    private DetectorService detectors;
    private ScanService scans;
    private WeeklyService weekly;
    private LedgerActions actions;
    private Menus menus;
    private LedgerCommand command;
    private final List<String> hooks = new ArrayList<>();
    private long enableMs;

    /** The owner controls these: removed entries aren't added back. */
    @Override
    protected String[] userConfigSections() {
        return new String[]{"checks.renewables"};
    }

    /** Runs before any plugin enables: the Vault proxy must be in place before plugins look up the economy. */
    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        try {
            VaultProxy proxy = new VaultProxy(getLogger());
            getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, proxy, this, ServicePriority.Highest);
            vaultProxy = proxy;
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, "Couldn't put the ledger in front of Vault; only EssentialsX and dkBank changes are recorded", e);
        }
    }

    @Override
    protected void enable() {
        long start = System.currentTimeMillis();
        config = LedgerConfig.load(cfg().yaml(), getDataFolder().toPath().getParent(), getDataFolder().toPath());
        store = new LedgerStore(database());
        store.createTables().whenComplete((v, err) -> {
            if (err != null) {
                getLogger().log(Level.SEVERE, "Could not create the dkledger_ tables. Disabling dkLedger.", err);
                tasks().sync(() -> Bukkit.getPluginManager().disablePlugin(this));
            }
        });

        CommandTracker tracker = new CommandTracker();
        ledger = new LedgerService(this, store, new SourceResolver(Set.of("dkledger", "vault", "dkcore")), tracker);
        alerts = new AlertService(this, store);
        freezes = new FreezeService(this, store);
        logins = new LoginTracker(this, store, salt());
        supply = new SupplyService(this, store);
        detectors = new DetectorService(this, store);
        scans = new ScanService(this);
        weekly = new WeeklyService(this, store);
        actions = new LedgerActions(this, store);
        menus = new Menus(this);
        Sources rules = new Sources(config.ledger().transferSources(), config.ledger().internalSources());
        watch = new LiveWatch(rules, config.thresholds(), config.zone(), logins::of);
        applyConfig();

        if (vaultProxy instanceof VaultProxy proxy) {
            proxy.recorder(ledger);
            hooks.add("Vault");
        }
        listen(tracker, freezes, logins, new ServerListener());
        if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) hook("Essentials", () -> listen(new com.direk.dkledger.ledger.hook.EssentialsHook(ledger)));
        if (Bukkit.getPluginManager().isPluginEnabled("dkBank")) hook("dkBank", () -> listen(new com.direk.dkledger.ledger.hook.DkBankHook(ledger, freezes)));
        if (Bukkit.getPluginManager().isPluginEnabled("Lodestock")) hooks.add("Lodestock (prices)");

        command = new LedgerCommand(this);
        registerCommand(command);

        // Data from earlier runs. dkCore's database runs these in order, after the tables exist.
        store.knownSources().thenAccept(known -> ledger.watch(w -> w.knownSources(known)));
        store.meta("installed").thenAccept(v -> {
            long installed = v.map(Long::parseLong).orElse(System.currentTimeMillis());
            if (v.isEmpty()) store.meta("installed", String.valueOf(installed));
            detectors.installed(installed);
            watchPastWarmup(System.currentTimeMillis() - installed > 86_400_000L);
        });
        alerts.load();
        freezes.load();
        logins.load();
        supply.loadLatest();
        weekly.load();
        store.players().thenAccept(list -> list.forEach(p -> ledger.rememberName(p.uuid(), p.name())));

        getServer().getServicesManager().register(LedgerApi.class, actions, this, ServicePriority.Normal);
        enableMs = System.currentTimeMillis() - start;
    }

    @Override
    protected void disable() {
        if (ledger != null) ledger.flushAndWait();
        if (vaultProxy instanceof VaultProxy proxy) proxy.recorder(null); // keeps passing payments through, records nothing
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof com.direk.dkcore.gui.Menu) p.closeInventory();
        }
    }

    /** Applies config.yml to every part and (re)starts the timers. */
    private void applyConfig() {
        LedgerConfig c = config;
        Sources rules = new Sources(c.ledger().transferSources(), c.ledger().internalSources());
        watch.configure(rules, c.thresholds(), c.zone());
        ledger.configure(c.ledger().enabled(), rules, new RollupBuffer(c.zone(), rules), watch);
        alerts.configure(c.alerts().repeatMillis(), c.alerts().notifyInGame(), c.alerts().sound());
        freezes.configure(c.freeze().commands(), c.freeze().sources());
        supply.configure(c.ledger().balancesPerTick());
        detectors.configure(c.thresholds(), c.zone(), c.ledger().keepDays());
        tasks().cancelAll();
        tasks().timer(ledger::flush, 20L * c.ledger().flushSeconds(), 20L * c.ledger().flushSeconds());
        tasks().timer(() -> detectors.run(), 20L * 60, 20L * 60 * c.ledger().snapshotMinutes());
        tasks().timer(weekly::tick, 20L * 120, 20L * 300);
        for (String w : c.warnings()) getLogger().warning(w);
    }

    /** /ledger reload: config.yml and messages.yml, without a restart. */
    public List<String> reloadEverything() {
        reloadAll();
        config = LedgerConfig.load(cfg().yaml(), getDataFolder().toPath().getParent(), getDataFolder().toPath());
        applyConfig();
        return config.warnings();
    }

    public void watchPastWarmup(boolean past) {
        if (ledger != null) ledger.watch(w -> w.pastWarmup(past));
    }

    private void hook(String name, Runnable install) {
        try {
            install.run();
            hooks.add(name);
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, name + " is installed but dkLedger couldn't hook into it (version mismatch?)", e);
        }
    }

    /** A random value kept in data/salt.txt, so hashed connections can't be reversed with a lookup table. */
    private String salt() {
        Path file = getDataFolder().toPath().resolve("data").resolve("salt.txt");
        try {
            if (Files.exists(file)) return Files.readString(file, StandardCharsets.UTF_8).trim();
            Files.createDirectories(file.getParent());
            String salt = UUID.randomUUID().toString();
            Files.writeString(file, salt, StandardCharsets.UTF_8);
            return salt;
        } catch (IOException e) {
            getLogger().warning("Couldn't keep data/salt.txt: " + e.getMessage());
            return getServer().getWorldContainer().getAbsolutePath();
        }
    }

    /** Startup banner, startup scan, and keeping the Vault proxy pointed at the right economy. */
    private final class ServerListener implements Listener {

        @EventHandler
        public void onServerLoad(ServerLoadEvent e) {
            if (e.getType() != ServerLoadEvent.LoadType.STARTUP) return;
            if (cfg().yaml().getBoolean("startup-banner", true)) Banner.print(DkLedger.this, bannerRows(), config.warnings(), enableMs);
            else getLogger().info("dkLedger v" + getPluginMeta().getVersion() + " enabled");
            if (config.scanOnStartup()) tasks().later(DkLedger.this::startupScan, config.startupDelay() * 20L);
        }

        @EventHandler
        public void onRegister(ServiceRegisterEvent e) {
            if (vaultProxy instanceof VaultProxy proxy && e.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) proxy.refresh();
        }

        @EventHandler
        public void onUnregister(ServiceUnregisterEvent e) {
            if (vaultProxy instanceof VaultProxy proxy && e.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) proxy.refresh();
        }
    }

    private List<Banner.Row> bannerRows() {
        List<Banner.Row> rows = new ArrayList<>();
        rows.add(new Banner.Row("Server", "<v>", Bukkit.getName() + " " + Bukkit.getMinecraftVersion()));
        rows.add(new Banner.Row("dkCore", "<green>v<v>", core().getPluginMeta().getVersion()));
        rows.add(new Banner.Row("Database", "<green><v></green> <dark_gray>(shared with dkCore)", database().describe()));
        String economy = vaultProxy instanceof VaultProxy proxy && proxy.delegate() != null ? proxy.delegate().getName() : "none";
        rows.add(new Banner.Row("Economy", vaultProxy != null ? "<white><v></white> <gray>(recorded through Vault)" : "<yellow><v>", economy));
        rows.add(new Banner.Row("Hooks", hooks.isEmpty() ? "<yellow>none" : "<white><v>", String.join(", ", hooks)));
        rows.add(new Banner.Row("Ledger", config.ledger().enabled() ? "<green>recording</green> <gray>- kept <v> days" : "<gray>off",
                String.valueOf(config.ledger().keepDays())));
        rows.add(new Banner.Row("Alerts", "<white><v></white> <gray>open", String.valueOf(alerts.open().size())));
        rows.add(new Banner.Row("Frozen", "<white><v></white> <gray>player(s)", String.valueOf(freezes.all().size())));
        return rows;
    }

    private void startupScan() {
        scans.scan().whenComplete((r, err) -> {
            if (err != null || r == null) return;
            getLogger().info("Startup scan: " + r.count(Severity.CRITICAL) + " critical, " + r.count(Severity.WARNING)
                    + " warnings, " + r.count(Severity.INFO) + " info. Report: plugins/dkLedger/reports/latest.md");
            if (r.count(Severity.CRITICAL) == 0) return;
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission(AlertService.PERMISSION)) command.sendSummary(p, r);
        });
    }

    // ------------------------------------------------------------------ services

    public LedgerConfig ledgerConfig() { return config; }
    public LedgerService ledger() { return ledger; }
    public AlertService alerts() { return alerts; }
    public FreezeService freezes() { return freezes; }
    public SupplyService supply() { return supply; }
    public ScanService scans() { return scans; }
    public WeeklyService weekly() { return weekly; }
    public LedgerActions actions() { return actions; }
    public Menus menus() { return menus; }

    /** The Vault proxy, or null when Vault isn't installed. */
    public VaultProxy vault() {
        return vaultProxy instanceof VaultProxy proxy ? proxy : null;
    }
}
