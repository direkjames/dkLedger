package dev.direk.dkledger;

import dev.direk.dkledger.audit.AuditResult;
import dev.direk.dkledger.audit.Severity;
import dev.direk.dkledger.command.LedgerCommand;
import dev.direk.dkledger.config.LedgerConfig;
import dev.direk.dkledger.scan.ScanService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * dkLedger: our server's financial analyst. Phase 1 is the config audit (/ledger scan).
 *
 * @author direk james
 */
public final class DkLedgerPlugin extends JavaPlugin implements Listener {

    private volatile LedgerConfig config;
    private ScanService scans;
    private LedgerCommand command;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLedgerConfig();
        scans = new ScanService(this);
        command = new LedgerCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "dkLedger: audit the server's economy", List.of()));
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("dkLedger " + getPluginMeta().getVersion() + " ready. /ledger scan audits the economy.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
    }

    /** Runs the startup scan once every plugin has loaded. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || !config.scanOnStartup()) return;
        getServer().getScheduler().runTaskLater(this, this::startupScan, config.startupDelay() * 20L);
    }

    private void startupScan() {
        scans.start(result -> {
            if (result == null) return;
            getLogger().info("Startup scan: " + result.count(Severity.CRITICAL) + " critical, " + result.count(Severity.WARNING)
                    + " warnings, " + result.count(Severity.INFO) + " info. Report: plugins/dkLedger/reports/latest.md");
            notifyAdmins(result);
        });
    }

    /** Admins online get the summary when a scan they didn't start finds critical problems. */
    private void notifyAdmins(AuditResult result) {
        if (result.count(Severity.CRITICAL) == 0) return;
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("dkledger.alerts")) command.sendSummary(player, result);
        }
    }

    public void reloadLedgerConfig() {
        reloadConfig();
        config = LedgerConfig.load(getConfig(), getDataFolder().toPath().getParent(), getDataFolder().toPath(), getLogger());
    }

    public LedgerConfig ledgerConfig() {
        return config;
    }

    public ScanService scans() {
        return scans;
    }
}
