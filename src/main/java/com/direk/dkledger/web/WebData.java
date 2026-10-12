package com.direk.dkledger.web;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.api.LedgerApi;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.supply.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.direk.dkledger.web.Json.obj;

/**
 * The dashboard's answers, as JSON-ready maps. Everything here is read-only: the dashboard can look, never
 * change. Each method is started on the main thread (the web server takes care of that).
 */
public final class WebData {

    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;

    public WebData(DkLedger plugin) {
        this.plugin = plugin;
    }

    private LedgerApi api() {
        return plugin.actions();
    }

    public CompletableFuture<Object> me(WebSessions.Session s) {
        return CompletableFuture.completedFuture(obj("name", s.name(), "version", plugin.getPluginMeta().getVersion(),
                "timezone", plugin.ledgerConfig().zone().getId(), "expires", s.expires(),
                "tracking", obj("items", plugin.ledgerConfig().tracking().items(), "playtime", plugin.ledgerConfig().tracking().playtime())));
    }

    /** Headline numbers, the money supply over the period, and money created and removed. */
    public CompletableFuture<Object> overview(int days) {
        long now = System.currentTimeMillis();
        CompletableFuture<LedgerApi.Supply> supply = api().supply();
        CompletableFuture<List<Snapshot>> history = plugin.store().snapshots(now - Math.max(1, days) * DAY, now);
        CompletableFuture<List<LedgerApi.Source>> sources = api().sources(days);
        Snapshot latest = plugin.supply().latest();
        int openAlerts = plugin.alerts().open().size();
        return CompletableFuture.allOf(supply, history, sources).thenApply(v -> {
            LedgerApi.Supply s = supply.join();
            double created = 0;
            double removed = 0;
            for (LedgerApi.Source src : sources.join()) {
                if (src.kind().equals("faucet") || src.kind().equals("staff") && src.net() > 0) created += src.net();
                else if (src.net() < 0) removed += -src.net();
            }
            List<Object> points = new ArrayList<>();
            for (Snapshot p : history.join()) {
                points.add(obj("t", p.time(), "wallet", p.wallet(), "bank", p.bank(), "total", p.total(), "active", p.active()));
            }
            return obj("supply", obj("total", s.total(), "wallet", s.wallet(), "bank", s.bank(), "change24h", s.change24h(),
                            "change7d", s.change7d(), "perActive", s.perActive(), "topShare", s.topShare(), "time", s.time()),
                    "players", obj("active", latest == null ? 0 : latest.active(), "accounts", latest == null ? 0 : latest.accounts()),
                    "created", created, "removed", removed, "openAlerts", openAlerts, "history", points);
        });
    }

    public CompletableFuture<Object> sources(int days) {
        return api().sources(days).thenApply(list -> {
            List<Object> out = new ArrayList<>();
            for (LedgerApi.Source s : list) out.add(obj("name", s.name(), "kind", s.kind(), "in", s.gained(), "out", s.lost(), "net", s.net()));
            return out;
        });
    }

    public CompletableFuture<Object> top(int days) {
        return plugin.actions().top(days).thenApply(list -> {
            List<Object> out = new ArrayList<>();
            for (LedgerStore.PlayerTotal p : list) out.add(obj("name", p.name(), "earned", p.earned(), "spent", p.spent()));
            return out;
        });
    }

    public CompletableFuture<Object> items(int days) {
        return api().items(days).thenApply(list -> {
            List<Object> out = new ArrayList<>();
            for (LedgerApi.Item i : list) {
                out.add(obj("item", i.item(), "sold", i.sold(), "soldMoney", i.soldMoney(), "bought", i.bought(), "boughtMoney", i.boughtMoney()));
            }
            return out;
        });
    }

    public CompletableFuture<Object> reasons(int days) {
        return api().reasons(days).thenApply(list -> {
            List<Object> out = new ArrayList<>();
            for (LedgerApi.Reason r : list) out.add(obj("source", r.source(), "reason", r.reason(), "in", r.gained(), "out", r.lost()));
            return out;
        });
    }

    public CompletableFuture<Object> alerts() {
        List<Object> out = new ArrayList<>();
        for (AlertView a : api().alerts()) {
            out.add(obj("id", a.id(), "time", a.time(), "check", a.check(), "severity", a.severity(), "subject", a.subject(),
                    "title", a.title(), "detail", a.detail(), "open", a.open(), "resolvedBy", a.resolvedBy(), "note", a.note()));
        }
        return CompletableFuture.completedFuture(out);
    }

    /** A player's money, activity and items, or null if nobody by that name has joined. */
    public CompletableFuture<Object> player(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.]{1,16}")) return CompletableFuture.completedFuture(null);
        return api().player(name).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture((Object) null);
            LedgerApi.Player p = found.get();
            return api().activity(p.name()).thenCombine(plugin.actions().playerItems(p.uuid()), (act, items) -> {
                Map<String, Object> out = obj("name", p.name(), "uuid", p.uuid().toString(), "frozen", p.frozen(),
                        "frozenReason", p.frozenReason(), "earnedToday", p.earnedToday(), "earned7d", p.earned7d());
                List<Object> sources = new ArrayList<>();
                for (LedgerApi.Source s : p.sources7d()) sources.add(obj("name", s.name(), "in", s.gained(), "out", s.lost()));
                out.put("sources", sources);
                out.put("recent", p.recentEntries());
                out.put("notes", p.notes());
                Optional<LedgerApi.Activity> a = act;
                out.put("activity", a.map(x -> (Object) obj("firstJoin", x.firstJoin(), "activeMinutes", x.activeMinutes(),
                        "afkMinutes", x.afkMinutes(), "sessions", x.sessions(), "daysPlayed", x.daysPlayed(), "progress", x.progress())).orElse(null));
                List<Object> traded = new ArrayList<>();
                for (LedgerStore.ItemTotal i : items) {
                    traded.add(obj("item", i.item(), "sold", i.sold(), "soldMoney", i.soldMoney(), "bought", i.bought(), "boughtMoney", i.boughtMoney()));
                }
                out.put("items", traded);
                return out;
            });
        });
    }
}
