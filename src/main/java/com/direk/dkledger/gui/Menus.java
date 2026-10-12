package com.direk.dkledger.gui;

import com.direk.dkcore.gui.ItemBuilder;
import com.direk.dkcore.gui.Menu;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.api.LedgerApi;
import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.command.LedgerCommand;
import com.direk.dkledger.command.Ui;
import com.direk.dkledger.ledger.LedgerStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The /ledger menus: overview, sources, alerts, top earners and frozen players. Everything is read-only
 * except resolving alerts and unfreezing players (shift-click), which need dkledger.admin.
 */
public final class Menus {

    private static final ItemStack FILLER = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name(Component.text(" ")).build();

    private final DkLedger plugin;

    public Menus(DkLedger plugin) {
        this.plugin = plugin;
    }

    private Component title(String key, String period) {
        return plugin.messages().get(key, com.direk.dkcore.message.Messages.text("period", period));
    }

    // ------------------------------------------------------------------ main menu

    public void openMain(Player player) {
        plugin.actions().supply().thenAccept(s -> {
            if (!player.isOnline()) return;
            Menu m = new Menu(5, title("menu.title", ""));
            List<Component> supply = new ArrayList<>();
            if (s.time() == 0) supply.add(gray("No snapshot yet."));
            else {
                supply.add(gray("Total: ").append(white(Money.format(s.total()))));
                supply.add(gray("Wallets " + Money.format(s.wallet()) + ", banks " + Money.format(s.bank())));
                supply.add(gray("24h: " + change(s.change24h()) + "   7 days: " + change(s.change7d())));
                supply.add(gray("Per active player: " + Money.format(s.perActive())));
                supply.add(gray("Richest 10% hold " + Money.percent(s.topShare())));
            }
            m.set(11, icon(Material.EMERALD, "Money supply", NamedTextColor.GREEN, supply), e -> run(player, "supply"));

            List<AlertView> open = plugin.alerts().open();
            long critical = open.stream().filter(a -> a.severity().equals("CRITICAL")).count();
            m.set(13, icon(open.isEmpty() ? Material.BELL : Material.RED_DYE, "Alerts", open.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED,
                    List.of(gray(open.size() + " open, " + critical + " critical"), click("Click to see them"))), e -> openAlerts(player));
            m.set(15, icon(Material.HOPPER, "Where money comes from", NamedTextColor.GOLD,
                    List.of(gray("Faucets, sinks and transfers"), click("Click for the last 7 days"))), e -> openSources(player, 7));

            m.set(29, icon(Material.PLAYER_HEAD, "Top earners", NamedTextColor.AQUA,
                    List.of(gray("Who earned the most this week"), click("Click to see them"))), e -> openTop(player));
            AuditResult scan = plugin.scans().last();
            List<Component> audit = new ArrayList<>();
            if (scan == null) audit.add(gray("No scan yet."));
            else {
                audit.add(gray(scan.count(Severity.CRITICAL) + " critical, " + scan.count(Severity.WARNING) + " warnings"));
                audit.add(gray("Rewards: " + Money.format(scan.casualPerDay()) + "/day casual"));
            }
            audit.add(click("Left-click: scan now"));
            audit.add(click("Right-click: list findings"));
            m.set(31, icon(Material.WRITABLE_BOOK, "Config audit", NamedTextColor.YELLOW, audit), e -> {
                player.closeInventory();
                run(player, e.isRightClick() ? "findings" : "scan");
            });
            m.set(33, icon(Material.BLUE_ICE, "Frozen players", NamedTextColor.AQUA,
                    List.of(gray(plugin.freezes().all().size() + " frozen"), click("Click to see them"))), e -> openFrozen(player));

            m.set(38, icon(Material.COMPARATOR, "What if...", NamedTextColor.LIGHT_PURPLE,
                    List.of(gray("Simulate a price or reward change"), gray("without touching any file."), click("Click for the commands"))),
                    e -> {
                        player.closeInventory();
                        run(player, "whatif");
                    });
            m.set(40, icon(Material.FILLED_MAP, "Weekly report", NamedTextColor.GOLD,
                    List.of(gray("Charts and tables of the week"), click("Click to write it now"))), e -> {
                player.closeInventory();
                run(player, "weekly");
            });
            m.set(42, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), e -> player.closeInventory());
            m.fill(FILLER);
            m.open(player);
        });
    }

    // ------------------------------------------------------------------ sources

    public void openSources(Player player, int days) {
        plugin.actions().sources(days).thenAccept(list -> {
            if (!player.isOnline()) return;
            String period = days == 1 ? "today" : days + " days";
            Menu m = new Menu(6, title("menu.sources-title", period));
            int slot = 0;
            for (LedgerApi.Source s : list) {
                if (slot >= 45) break;
                Material material = switch (s.kind()) {
                    case "faucet" -> Material.LIME_DYE;
                    case "sink" -> Material.RED_DYE;
                    case "transfer" -> Material.PAPER;
                    case "wallet-bank" -> Material.GOLD_INGOT;
                    default -> Material.COMMAND_BLOCK;
                };
                m.set(slot++, icon(material, s.name(), s.net() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED, List.of(
                        gray("Kind: " + s.kind()), gray("In: " + Money.format(s.gained())), gray("Out: " + Money.format(s.lost())),
                        Component.text("Net: " + Money.format(s.net()), s.net() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED))));
            }
            if (list.isEmpty()) m.set(22, icon(Material.STRUCTURE_VOID, "No money moved yet", NamedTextColor.GRAY, List.of()));
            m.set(45, period(days == 1, "Today"), e -> openSources(player, 1));
            m.set(46, period(days == 7, "7 days"), e -> openSources(player, 7));
            m.set(47, period(days == 30, "30 days"), e -> openSources(player, 30));
            back(m, player);
            m.fill(FILLER);
            m.open(player);
        });
    }

    private static ItemStack period(boolean selected, String label) {
        return ItemBuilder.of(selected ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE)
                .name(Component.text(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)).glow(selected).build();
    }

    // ------------------------------------------------------------------ alerts

    public void openAlerts(Player player) {
        Menu m = new Menu(6, title("menu.alerts-title", ""));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(plugin.ledgerConfig().zone());
        int slot = 0;
        for (AlertView a : plugin.alerts().recent()) {
            if (slot >= 45) break;
            Material material = !a.open() ? Material.GRAY_CONCRETE : switch (a.severity()) {
                case "CRITICAL" -> Material.RED_CONCRETE;
                case "WARNING" -> Material.YELLOW_CONCRETE;
                default -> Material.LIGHT_BLUE_CONCRETE;
            };
            TextColor color = !a.open() ? NamedTextColor.GRAY : a.severity().equals("CRITICAL") ? NamedTextColor.RED
                    : a.severity().equals("WARNING") ? NamedTextColor.YELLOW : NamedTextColor.AQUA;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("#" + a.id() + " · " + a.check() + " · " + f.format(Instant.ofEpochMilli(a.time())), NamedTextColor.DARK_GRAY));
            for (String line : Ui.lines(a.detail(), 40)) lore.add(gray(line));
            if (!a.open()) lore.add(Component.text("Resolved by " + a.resolvedBy() + (a.note() != null ? ": " + a.note() : ""), NamedTextColor.DARK_GRAY));
            else lore.add(click("Shift-click: mark as resolved"));
            m.set(slot++, icon(material, a.title(), color, lore), e -> {
                if (!a.open() || !e.isShiftClick() || !player.hasPermission(LedgerCommand.ADMIN)) return;
                plugin.actions().resolveAlert(a.id(), player.getName(), null).thenAccept(done -> openAlerts(player));
            });
        }
        if (slot == 0) m.set(22, icon(Material.LIME_DYE, "No alerts in the last 30 days", NamedTextColor.GREEN, List.of()));
        back(m, player);
        m.fill(FILLER);
        m.open(player);
    }

    // ------------------------------------------------------------------ top earners

    public void openTop(Player player) {
        plugin.actions().top(7).thenAccept(list -> {
            if (!player.isOnline()) return;
            Menu m = new Menu(3, title("menu.top-title", ""));
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 4, 22, 3};
            int i = 0;
            for (LedgerStore.PlayerTotal p : list) {
                if (i >= slots.length) break;
                ItemStack head = icon(Material.PLAYER_HEAD, (i + 1) + ". " + p.name(), NamedTextColor.GOLD, List.of(
                        gray("Earned: " + Money.format(p.earned())), gray("Spent: " + Money.format(p.spent())), click("Click for their history")));
                if (head.getItemMeta() instanceof SkullMeta skull) {
                    skull.setOwningPlayer(Bukkit.getOfflinePlayer(p.player()));
                    head.setItemMeta(skull);
                }
                String name = p.name();
                m.set(slots[i++], head, e -> {
                    player.closeInventory();
                    run(player, "player " + name);
                });
            }
            if (list.isEmpty()) m.set(13, icon(Material.STRUCTURE_VOID, "Nobody earned money this week", NamedTextColor.GRAY, List.of()));
            m.set(18, backItem(), e -> openMain(player));
            m.fill(FILLER);
            m.open(player);
        });
    }

    // ------------------------------------------------------------------ frozen players

    public void openFrozen(Player player) {
        Menu m = new Menu(3, title("menu.frozen-title", ""));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(plugin.ledgerConfig().zone());
        int slot = 0;
        for (LedgerStore.Frozen fr : plugin.freezes().all()) {
            if (slot >= 18) break;
            ItemStack head = icon(Material.PLAYER_HEAD, fr.name(), NamedTextColor.AQUA, List.of(
                    gray("Since " + f.format(Instant.ofEpochMilli(fr.since())) + " by " + fr.by()),
                    gray(fr.reason().isEmpty() ? "No reason given" : fr.reason()), click("Shift-click: unfreeze")));
            if (head.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(fr.uuid()));
                head.setItemMeta(skull);
            }
            m.set(slot++, head, e -> {
                if (!e.isShiftClick() || !player.hasPermission(LedgerCommand.ADMIN)) return;
                plugin.actions().unfreeze(fr.name()).thenAccept(done -> openFrozen(player));
            });
        }
        if (slot == 0) m.set(13, icon(Material.LIME_DYE, "Nobody is frozen", NamedTextColor.GREEN, List.of()));
        m.set(18, backItem(), e -> openMain(player));
        m.fill(FILLER);
        m.open(player);
    }

    // ------------------------------------------------------------------ helpers

    private void back(Menu m, Player player) {
        m.set(49, backItem(), e -> openMain(player));
    }

    private static ItemStack backItem() {
        return ItemBuilder.of(Material.ARROW).name(Component.text("Back", NamedTextColor.WHITE)).build();
    }

    private static ItemStack icon(Material material, String name, TextColor color, List<Component> lore) {
        return ItemBuilder.of(material).name(Component.text(name, color)).lore(lore).build();
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component white(String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static Component click(String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    private static String change(double c) {
        return Double.isNaN(c) ? "-" : (c >= 0 ? "+" : "-") + Money.percent(Math.abs(c));
    }

    /** Runs a /ledger sub command for the player (so menus and chat show the same thing). */
    private static void run(Player player, String sub) {
        player.performCommand("ledger " + sub);
    }
}
