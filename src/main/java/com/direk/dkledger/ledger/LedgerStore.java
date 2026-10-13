package com.direk.dkledger.ledger;

import com.direk.dkcore.database.Database;
import com.direk.dkledger.supply.Snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything dkLedger keeps in dkCore's shared database. Tables start with dkledger_.
 * Every method runs on dkCore's database thread and returns a future that completes there,
 * so callers hop back to the main thread (tasks().sync) before touching Bukkit.
 */
public final class LedgerStore {

    public static final String ENTRIES = "dkledger_entries";
    public static final String DAILY = "dkledger_daily";
    public static final String PLAYER_DAILY = "dkledger_player_daily";
    public static final String SNAPSHOTS = "dkledger_snapshots";
    public static final String SOURCES = "dkledger_sources";
    public static final String ALERTS = "dkledger_alerts";
    public static final String FROZEN = "dkledger_frozen";
    public static final String NOTES = "dkledger_notes";
    public static final String LOGINS = "dkledger_logins";
    public static final String PRICES = "dkledger_prices";
    public static final String META = "dkledger_meta";
    public static final String REASON_DAILY = "dkledger_reason_daily";
    public static final String TRADES = "dkledger_trades";
    public static final String ITEM_DAILY = "dkledger_item_daily";
    public static final String PRICE_DAILY = "dkledger_price_daily";
    public static final String PLAYERS = "dkledger_players";
    public static final String PLAYTIME = "dkledger_playtime";
    public static final String EVENTS = "dkledger_events";
    public static final String STAFF = "dkledger_staff";

    private final Database db;

    public LedgerStore(Database db) {
        this.db = db;
    }

    private boolean mysql() {
        return db.dialect() == Database.Dialect.MYSQL;
    }

    private String autoId() {
        return mysql() ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    // ------------------------------------------------------------------ tables

    public CompletableFuture<Void> createTables() {
        List<String> sql = new ArrayList<>();
        String entries = "CREATE TABLE IF NOT EXISTS " + ENTRIES + " (" + autoId() + ", time BIGINT NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, account VARCHAR(6) NOT NULL, amount DOUBLE NOT NULL, "
                + "source VARCHAR(64) NOT NULL, detail VARCHAR(160) NOT NULL, other VARCHAR(36), balance DOUBLE, "
                + "reason VARCHAR(64) NOT NULL DEFAULT ''";
        String trades = "CREATE TABLE IF NOT EXISTS " + TRADES + " (" + autoId() + ", time BIGINT NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, source VARCHAR(64) NOT NULL, item VARCHAR(48) NOT NULL, "
                + "side VARCHAR(4) NOT NULL, quantity INT NOT NULL, money DOUBLE NOT NULL, detail VARCHAR(160) NOT NULL";
        String events = "CREATE TABLE IF NOT EXISTS " + EVENTS + " (" + autoId() + ", time BIGINT NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, plugin VARCHAR(32) NOT NULL, type VARCHAR(32) NOT NULL, "
                + "event_key VARCHAR(64) NOT NULL, value DOUBLE NOT NULL";
        String alerts = "CREATE TABLE IF NOT EXISTS " + ALERTS + " (" + autoId() + ", time BIGINT NOT NULL, "
                + "check_id VARCHAR(8) NOT NULL, severity VARCHAR(8) NOT NULL, subject VARCHAR(80) NOT NULL, "
                + "title VARCHAR(200) NOT NULL, detail TEXT NOT NULL, resolved BIGINT, resolved_by VARCHAR(32), note VARCHAR(200)";
        String notes = "CREATE TABLE IF NOT EXISTS " + NOTES + " (" + autoId() + ", uuid VARCHAR(36) NOT NULL, "
                + "time BIGINT NOT NULL, by_name VARCHAR(32) NOT NULL, note VARCHAR(255) NOT NULL";
        if (mysql()) {
            sql.add(entries + ", INDEX idx_dkledger_entries_player (uuid, time), INDEX idx_dkledger_entries_time (time))");
            sql.add(alerts + ", INDEX idx_dkledger_alerts_time (time))");
            sql.add(notes + ", INDEX idx_dkledger_notes_player (uuid))");
            sql.add(trades + ", INDEX idx_dkledger_trades_player (uuid, time), INDEX idx_dkledger_trades_time (time))");
            sql.add(events + ", INDEX idx_dkledger_events_player (uuid, time), INDEX idx_dkledger_events_type (type, time))");
        } else {
            sql.add(entries + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_entries_player ON " + ENTRIES + " (uuid, time)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_entries_time ON " + ENTRIES + " (time)");
            sql.add(alerts + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_alerts_time ON " + ALERTS + " (time)");
            sql.add(notes + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_notes_player ON " + NOTES + " (uuid)");
            sql.add(trades + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_trades_player ON " + TRADES + " (uuid, time)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_trades_time ON " + TRADES + " (time)");
            sql.add(events + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_events_player ON " + EVENTS + " (uuid, time)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_events_type ON " + EVENTS + " (type, time)");
        }
        sql.add("CREATE TABLE IF NOT EXISTS " + DAILY + " (day VARCHAR(10) NOT NULL, source VARCHAR(64) NOT NULL, "
                + "account VARCHAR(6) NOT NULL, gained DOUBLE NOT NULL, lost DOUBLE NOT NULL, entries INT NOT NULL, "
                + "PRIMARY KEY (day, source, account))");
        sql.add("CREATE TABLE IF NOT EXISTS " + PLAYER_DAILY + " (day VARCHAR(10) NOT NULL, uuid VARCHAR(36) NOT NULL, "
                + "name VARCHAR(16) NOT NULL, earned DOUBLE NOT NULL, spent DOUBLE NOT NULL, PRIMARY KEY (day, uuid))");
        sql.add("CREATE TABLE IF NOT EXISTS " + SNAPSHOTS + " (time BIGINT NOT NULL PRIMARY KEY, wallet DOUBLE NOT NULL, "
                + "bank DOUBLE NOT NULL, accounts INT NOT NULL, active INT NOT NULL, top_share DOUBLE NOT NULL, gini DOUBLE NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + SOURCES + " (source VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + FROZEN + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                + "since BIGINT NOT NULL, by_name VARCHAR(32) NOT NULL, reason VARCHAR(200) NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + LOGINS + " (uuid VARCHAR(36) NOT NULL, ip_hash VARCHAR(64) NOT NULL, "
                + "last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip_hash))");
        sql.add("CREATE TABLE IF NOT EXISTS " + PRICES + " (time BIGINT NOT NULL, item VARCHAR(48) NOT NULL, "
                + "price DOUBLE NOT NULL, PRIMARY KEY (time, item))");
        sql.add("CREATE TABLE IF NOT EXISTS " + META + " (k VARCHAR(32) NOT NULL PRIMARY KEY, v VARCHAR(64) NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + REASON_DAILY + " (day VARCHAR(10) NOT NULL, source VARCHAR(64) NOT NULL, "
                + "reason VARCHAR(64) NOT NULL, gained DOUBLE NOT NULL, lost DOUBLE NOT NULL, entries INT NOT NULL, "
                + "PRIMARY KEY (day, source, reason))");
        sql.add("CREATE TABLE IF NOT EXISTS " + ITEM_DAILY + " (day VARCHAR(10) NOT NULL, item VARCHAR(48) NOT NULL, "
                + "source VARCHAR(64) NOT NULL, side VARCHAR(4) NOT NULL, trades INT NOT NULL, quantity BIGINT NOT NULL, "
                + "money DOUBLE NOT NULL, PRIMARY KEY (day, item, source, side))");
        sql.add("CREATE TABLE IF NOT EXISTS " + PRICE_DAILY + " (day VARCHAR(10) NOT NULL, item VARCHAR(48) NOT NULL, "
                + "price DOUBLE NOT NULL, PRIMARY KEY (day, item))");
        sql.add("CREATE TABLE IF NOT EXISTS " + PLAYERS + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                + "first_join BIGINT NOT NULL, first_tracked BIGINT NOT NULL, last_seen BIGINT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + PLAYTIME + " (day VARCHAR(10) NOT NULL, uuid VARCHAR(36) NOT NULL, "
                + "active_minutes INT NOT NULL, afk_minutes INT NOT NULL, sessions INT NOT NULL, PRIMARY KEY (day, uuid))");
        sql.add("CREATE TABLE IF NOT EXISTS " + STAFF + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, last_quit BIGINT NOT NULL, "
                + "muted_until BIGINT NOT NULL, enabled INT NOT NULL)");
        return db.createTables(sql).thenCompose(v -> migrate());
    }

    /** Brings tables made by older versions up to date. */
    private CompletableFuture<Void> migrate() {
        return db.execute(c -> {
            // 0.3.0: the reason other plugins give for a money change.
            if (!hasColumn(c, ENTRIES, "reason")) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + ENTRIES + " ADD COLUMN reason VARCHAR(64) NOT NULL DEFAULT ''");
                }
            }
        });
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (meta.getColumnName(i).equalsIgnoreCase(column)) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ writing the ledger

    /** Saves entries and adds the daily totals to what's already saved, in one transaction. */
    public CompletableFuture<Void> save(List<LedgerEntry> entries, RollupBuffer.Drained rollup) {
        String addSource = mysql()
                ? "INSERT INTO " + DAILY + " (day, source, account, gained, lost, entries) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE gained = gained + VALUES(gained), lost = lost + VALUES(lost), entries = entries + VALUES(entries)"
                : "INSERT INTO " + DAILY + " (day, source, account, gained, lost, entries) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, source, account) DO UPDATE SET gained = gained + excluded.gained, "
                + "lost = lost + excluded.lost, entries = entries + excluded.entries";
        String addPlayer = mysql()
                ? "INSERT INTO " + PLAYER_DAILY + " (day, uuid, name, earned, spent) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE earned = earned + VALUES(earned), spent = spent + VALUES(spent), "
                + "name = IF(VALUES(name) = '', name, VALUES(name))"
                : "INSERT INTO " + PLAYER_DAILY + " (day, uuid, name, earned, spent) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, uuid) DO UPDATE SET earned = earned + excluded.earned, spent = spent + excluded.spent, "
                + "name = CASE WHEN excluded.name = '' THEN name ELSE excluded.name END";
        String addReason = mysql()
                ? "INSERT INTO " + REASON_DAILY + " (day, source, reason, gained, lost, entries) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE gained = gained + VALUES(gained), lost = lost + VALUES(lost), entries = entries + VALUES(entries)"
                : "INSERT INTO " + REASON_DAILY + " (day, source, reason, gained, lost, entries) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, source, reason) DO UPDATE SET gained = gained + excluded.gained, "
                + "lost = lost + excluded.lost, entries = entries + excluded.entries";
        return db.execute(c -> inTransaction(c, () -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + ENTRIES
                    + " (time, uuid, name, account, amount, source, detail, other, balance, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (LedgerEntry e : entries) {
                    ps.setLong(1, e.time());
                    ps.setString(2, e.player().toString());
                    ps.setString(3, cut(e.name(), 16));
                    ps.setString(4, e.account().name());
                    ps.setDouble(5, e.amount());
                    ps.setString(6, cut(e.source(), 64));
                    ps.setString(7, cut(e.detail(), 160));
                    if (e.other() == null) ps.setNull(8, Types.VARCHAR);
                    else ps.setString(8, e.other().toString());
                    if (Double.isNaN(e.balance())) ps.setNull(9, Types.DOUBLE);
                    else ps.setDouble(9, e.balance());
                    ps.setString(10, cut(e.reason(), 64));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(addSource)) {
                for (RollupBuffer.SourceDay s : rollup.sources()) {
                    ps.setString(1, s.day());
                    ps.setString(2, cut(s.source(), 64));
                    ps.setString(3, s.account().name());
                    ps.setDouble(4, s.gained());
                    ps.setDouble(5, s.lost());
                    ps.setInt(6, s.entries());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(addPlayer)) {
                for (RollupBuffer.PlayerDay p : rollup.players()) {
                    ps.setString(1, p.day());
                    ps.setString(2, p.player().toString());
                    ps.setString(3, cut(p.name(), 16));
                    ps.setDouble(4, p.earned());
                    ps.setDouble(5, p.spent());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (!rollup.reasons().isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(addReason)) {
                    for (RollupBuffer.ReasonDay r : rollup.reasons()) {
                        ps.setString(1, r.day());
                        ps.setString(2, cut(r.source(), 64));
                        ps.setString(3, cut(r.reason(), 64));
                        ps.setDouble(4, r.gained());
                        ps.setDouble(5, r.lost());
                        ps.setInt(6, r.entries());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }));
    }

    /**
     * Deletes single entries, item trades and hourly prices older than {@code before}. Daily totals, snapshots,
     * playtime and progress events are kept forever.
     */
    public CompletableFuture<Integer> prune(long before) {
        return db.query(c -> {
            int removed;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + ENTRIES + " WHERE time < ?")) {
                ps.setLong(1, before);
                removed = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TRADES + " WHERE time < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + PRICES + " WHERE time < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + LOGINS + " WHERE last_seen < ?")) {
                ps.setLong(1, before - 60L * 86_400_000L);
                ps.executeUpdate();
            }
            return removed;
        });
    }

    // ------------------------------------------------------------------ reading the ledger

    public record SourceTotal(String source, Account account, double gained, double lost, int entries) {
        public double net() {
            return gained - lost;
        }
    }

    public record PlayerTotal(UUID player, String name, double earned, double spent) {
    }

    /** Money per source and account between two days (inclusive, "2026-10-12"). */
    public CompletableFuture<List<SourceTotal>> sourceTotals(String fromDay, String toDay) {
        return db.query(c -> {
            List<SourceTotal> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source, account, SUM(gained), SUM(lost), SUM(entries) FROM "
                    + DAILY + " WHERE day >= ? AND day <= ? GROUP BY source, account")) {
                ps.setString(1, fromDay);
                ps.setString(2, toDay);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SourceTotal(rs.getString(1), Account.valueOf(rs.getString(2)), rs.getDouble(3),
                                rs.getDouble(4), rs.getInt(5)));
                    }
                }
            }
            return out;
        });
    }

    /** Players who earned the most between two days. */
    public CompletableFuture<List<PlayerTotal>> topEarners(String fromDay, String toDay, int limit) {
        return db.query(c -> {
            List<PlayerTotal> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, MAX(name), SUM(earned), SUM(spent) FROM " + PLAYER_DAILY
                    + " WHERE day >= ? AND day <= ? GROUP BY uuid ORDER BY SUM(earned) DESC LIMIT ?")) {
                ps.setString(1, fromDay);
                ps.setString(2, toDay);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = uuid(rs.getString(1));
                        if (id != null) out.add(new PlayerTotal(id, rs.getString(2), rs.getDouble(3), rs.getDouble(4)));
                    }
                }
            }
            return out;
        });
    }

    /** What each player earned on one day, by name. */
    public CompletableFuture<Map<String, Double>> earnedOn(String day) {
        return db.query(c -> {
            Map<String, Double> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, earned FROM " + PLAYER_DAILY + " WHERE day = ? AND earned > 0")) {
                ps.setString(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        out.put(name == null || name.isEmpty() ? rs.getString(1).substring(0, 8) : name, rs.getDouble(3));
                    }
                }
            }
            return out;
        });
    }

    /** A player's newest entries, newest first. */
    public CompletableFuture<List<LedgerEntry>> entries(UUID player, int limit, int offset) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, uuid, name, account, amount, source, detail, other, balance, reason FROM "
                    + ENTRIES + " WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                return readEntries(ps);
            }
        });
    }

    public CompletableFuture<Optional<LedgerEntry>> entry(long id) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, uuid, name, account, amount, source, detail, other, balance, reason FROM "
                    + ENTRIES + " WHERE id = ?")) {
                ps.setLong(1, id);
                List<LedgerEntry> list = readEntries(ps);
                return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
            }
        });
    }

    /** A player's money per source since a time: source -> {gained, lost}. */
    public CompletableFuture<Map<String, double[]>> playerSources(UUID player, long since) {
        return db.query(c -> {
            Map<String, double[]> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source, SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), "
                    + "SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) FROM " + ENTRIES
                    + " WHERE uuid = ? AND time >= ? GROUP BY source ORDER BY 2 DESC")) {
                ps.setString(1, player.toString());
                ps.setLong(2, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), new double[]{rs.getDouble(2), rs.getDouble(3)});
                }
            }
            return out;
        });
    }

    private static List<LedgerEntry> readEntries(PreparedStatement ps) throws SQLException {
        List<LedgerEntry> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID player = uuid(rs.getString(3));
                if (player == null) continue;
                double balance = rs.getDouble(10);
                if (rs.wasNull()) balance = Double.NaN;
                out.add(new LedgerEntry(rs.getLong(1), rs.getLong(2), player, rs.getString(4), Account.valueOf(rs.getString(5)),
                        rs.getDouble(6), rs.getString(7), rs.getString(8), uuid(rs.getString(9)), balance, rs.getString(11)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ players (dkCore's dk_players)

    public record KnownPlayer(UUID uuid, String name, long lastSeen) {
    }

    /** Every player dkCore has seen. */
    public CompletableFuture<List<KnownPlayer>> players() {
        return db.query(c -> {
            List<KnownPlayer> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, last_seen FROM dk_players");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = uuid(rs.getString(1));
                    if (id != null) out.add(new KnownPlayer(id, rs.getString(2), rs.getLong(3)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ snapshots

    public CompletableFuture<Void> saveSnapshot(Snapshot s) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SNAPSHOTS
                    + " (time, wallet, bank, accounts, active, top_share, gini) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, s.time());
                ps.setDouble(2, s.wallet());
                ps.setDouble(3, s.bank());
                ps.setInt(4, s.accounts());
                ps.setInt(5, s.active());
                ps.setDouble(6, s.topShare());
                ps.setDouble(7, s.gini());
                ps.executeUpdate();
            }
        });
    }

    /** The newest snapshot taken at or before {@code time}. */
    public CompletableFuture<Optional<Snapshot>> snapshotAtOrBefore(long time) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT time, wallet, bank, accounts, active, top_share, gini FROM "
                    + SNAPSHOTS + " WHERE time <= ? ORDER BY time DESC LIMIT 1")) {
                ps.setLong(1, time);
                List<Snapshot> list = readSnapshots(ps);
                return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
            }
        });
    }

    /** Snapshots between two times, oldest first. */
    public CompletableFuture<List<Snapshot>> snapshots(long from, long to) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT time, wallet, bank, accounts, active, top_share, gini FROM "
                    + SNAPSHOTS + " WHERE time >= ? AND time <= ? ORDER BY time")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                return readSnapshots(ps);
            }
        });
    }

    private static List<Snapshot> readSnapshots(PreparedStatement ps) throws SQLException {
        List<Snapshot> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Snapshot(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getInt(4), rs.getInt(5),
                        rs.getDouble(6), rs.getDouble(7)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sources seen

    public CompletableFuture<Set<String>> knownSources() {
        return db.query(c -> {
            Set<String> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source FROM " + SOURCES); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    public CompletableFuture<Void> touchSources(Set<String> sources, long now) {
        String sql = mysql()
                ? "INSERT INTO " + SOURCES + " (source, first_seen, last_seen) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)"
                : "INSERT INTO " + SOURCES + " (source, first_seen, last_seen) VALUES (?, ?, ?) ON CONFLICT(source) DO UPDATE SET last_seen = excluded.last_seen";
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (String s : sources) {
                    ps.setString(1, cut(s, 64));
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    // ------------------------------------------------------------------ alerts

    public record StoredAlert(long id, long time, String check, String severity, String subject, String title, String detail,
                              long resolved, String resolvedBy, String note) {
        public boolean open() {
            return resolved == 0;
        }
    }

    public CompletableFuture<Long> addAlert(long time, String check, String severity, String subject, String title, String detail) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + ALERTS
                    + " (time, check_id, severity, subject, title, detail) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, time);
                ps.setString(2, check);
                ps.setString(3, severity);
                ps.setString(4, cut(subject, 80));
                ps.setString(5, cut(title, 200));
                ps.setString(6, detail);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<Boolean> resolveAlert(long id, long time, String by, String note) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + ALERTS
                    + " SET resolved = ?, resolved_by = ?, note = ? WHERE id = ? AND resolved IS NULL")) {
                ps.setLong(1, time);
                ps.setString(2, cut(by, 32));
                ps.setString(3, note == null ? null : cut(note, 200));
                ps.setLong(4, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Alerts since a time, newest first (open and resolved). */
    public CompletableFuture<List<StoredAlert>> alerts(long since, int limit) {
        return db.query(c -> {
            List<StoredAlert> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, check_id, severity, subject, title, detail, resolved, "
                    + "resolved_by, note FROM " + ALERTS + " WHERE time >= ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, since);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long resolved = rs.getLong(8);
                        out.add(new StoredAlert(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.wasNull() ? 0 : resolved, rs.getString(9), rs.getString(10)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ frozen players and notes

    public record Frozen(UUID uuid, String name, long since, String by, String reason) {
    }

    public CompletableFuture<List<Frozen>> frozen() {
        return db.query(c -> {
            List<Frozen> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, since, by_name, reason FROM " + FROZEN);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = uuid(rs.getString(1));
                    if (id != null) out.add(new Frozen(id, rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> freeze(Frozen f) {
        return db.execute(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + FROZEN + " WHERE uuid = ?")) {
                del.setString(1, f.uuid().toString());
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + FROZEN + " (uuid, name, since, by_name, reason) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, f.uuid().toString());
                ps.setString(2, cut(f.name(), 16));
                ps.setLong(3, f.since());
                ps.setString(4, cut(f.by(), 32));
                ps.setString(5, cut(f.reason(), 200));
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Boolean> unfreeze(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + FROZEN + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public record Note(long id, long time, String by, String note) {
    }

    public CompletableFuture<Void> addNote(UUID uuid, long time, String by, String note) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + NOTES + " (uuid, time, by_name, note) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, time);
                ps.setString(3, cut(by, 32));
                ps.setString(4, cut(note, 255));
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<Note>> notes(UUID uuid) {
        return db.query(c -> {
            List<Note> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, by_name, note FROM " + NOTES + " WHERE uuid = ? ORDER BY id DESC LIMIT 10")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Note(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ logins (hashed connections, for B9)

    public CompletableFuture<Void> touchLogin(UUID uuid, String ipHash, long now) {
        String sql = mysql()
                ? "INSERT INTO " + LOGINS + " (uuid, ip_hash, last_seen) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)"
                : "INSERT INTO " + LOGINS + " (uuid, ip_hash, last_seen) VALUES (?, ?, ?) ON CONFLICT(uuid, ip_hash) DO UPDATE SET last_seen = excluded.last_seen";
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ipHash);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Map<UUID, Set<String>>> logins() {
        return db.query(c -> {
            Map<UUID, Set<String>> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, ip_hash FROM " + LOGINS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = uuid(rs.getString(1));
                    if (id != null) out.computeIfAbsent(id, k -> new HashSet<>()).add(rs.getString(2));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ prices (Lodestock)

    /** Saves an hourly price sample, and keeps it as the day's price (kept forever, for the inflation index). */
    public CompletableFuture<Void> savePrices(long time, String day, Map<String, Double> prices) {
        String daily = mysql()
                ? "INSERT INTO " + PRICE_DAILY + " (day, item, price) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE price = VALUES(price)"
                : "INSERT INTO " + PRICE_DAILY + " (day, item, price) VALUES (?, ?, ?) ON CONFLICT(day, item) DO UPDATE SET price = excluded.price";
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(daily)) {
                for (Map.Entry<String, Double> e : prices.entrySet()) {
                    ps.setString(1, day);
                    ps.setString(2, cut(e.getKey(), 48));
                    ps.setDouble(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + PRICES + " (time, item, price) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, Double> e : prices.entrySet()) {
                    ps.setLong(1, time);
                    ps.setString(2, cut(e.getKey(), 48));
                    ps.setDouble(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** Each item's price from the newest sample at or before {@code time}. */
    public CompletableFuture<Map<String, Double>> pricesAt(long time) {
        return db.query(c -> {
            Map<String, Double> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item, price FROM " + PRICES + " WHERE time = (SELECT MAX(time) FROM "
                    + PRICES + " WHERE time <= ?)")) {
                ps.setLong(1, time);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ item trades

    /** Saves item trades and adds their daily totals to what's already saved, in one transaction. */
    public CompletableFuture<Void> saveTrades(com.direk.dkledger.items.ItemBuffer.Drained d) {
        String addDay = mysql()
                ? "INSERT INTO " + ITEM_DAILY + " (day, item, source, side, trades, quantity, money) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE trades = trades + VALUES(trades), quantity = quantity + VALUES(quantity), money = money + VALUES(money)"
                : "INSERT INTO " + ITEM_DAILY + " (day, item, source, side, trades, quantity, money) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, item, source, side) DO UPDATE SET trades = trades + excluded.trades, "
                + "quantity = quantity + excluded.quantity, money = money + excluded.money";
        return db.execute(c -> inTransaction(c, () -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TRADES
                    + " (time, uuid, name, source, item, side, quantity, money, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (com.direk.dkledger.items.ItemTrade t : d.trades()) {
                    ps.setLong(1, t.time());
                    ps.setString(2, t.player().toString());
                    ps.setString(3, cut(t.name(), 16));
                    ps.setString(4, cut(t.source(), 64));
                    ps.setString(5, cut(t.item(), 48));
                    ps.setString(6, t.side().name());
                    ps.setInt(7, t.quantity());
                    ps.setDouble(8, t.money());
                    ps.setString(9, cut(t.detail(), 160));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(addDay)) {
                for (com.direk.dkledger.items.ItemBuffer.ItemDay day : d.days()) {
                    ps.setString(1, day.day());
                    ps.setString(2, cut(day.item(), 48));
                    ps.setString(3, cut(day.source(), 64));
                    ps.setString(4, day.side().name());
                    ps.setInt(5, day.trades());
                    ps.setLong(6, day.quantity());
                    ps.setDouble(7, day.money());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }));
    }

    /** One item's trades over a period, both sides. */
    public record ItemTotal(String item, long bought, double boughtMoney, long sold, double soldMoney, int trades) {
        public double money() {
            return boughtMoney + soldMoney;
        }
    }

    /** Items traded between two days (inclusive), most money first. */
    public CompletableFuture<List<ItemTotal>> itemTotals(String fromDay, String toDay, int limit) {
        return db.query(c -> {
            List<ItemTotal> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item, "
                    + "SUM(CASE WHEN side = 'BUY' THEN quantity ELSE 0 END), SUM(CASE WHEN side = 'BUY' THEN money ELSE 0 END), "
                    + "SUM(CASE WHEN side = 'SELL' THEN quantity ELSE 0 END), SUM(CASE WHEN side = 'SELL' THEN money ELSE 0 END), "
                    + "SUM(trades) FROM " + ITEM_DAILY + " WHERE day >= ? AND day <= ? GROUP BY item ORDER BY SUM(money) DESC LIMIT ?")) {
                ps.setString(1, fromDay);
                ps.setString(2, toDay);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ItemTotal(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getLong(4), rs.getDouble(5), rs.getInt(6)));
                    }
                }
            }
            return out;
        });
    }

    /** A player's items traded since a time, most money first. */
    public CompletableFuture<List<ItemTotal>> playerItems(UUID player, long since, int limit) {
        return db.query(c -> {
            List<ItemTotal> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item, "
                    + "SUM(CASE WHEN side = 'BUY' THEN quantity ELSE 0 END), SUM(CASE WHEN side = 'BUY' THEN money ELSE 0 END), "
                    + "SUM(CASE WHEN side = 'SELL' THEN quantity ELSE 0 END), SUM(CASE WHEN side = 'SELL' THEN money ELSE 0 END), "
                    + "COUNT(*) FROM " + TRADES + " WHERE uuid = ? AND time >= ? GROUP BY item ORDER BY SUM(money) DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, since);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ItemTotal(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getLong(4), rs.getDouble(5), rs.getInt(6)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ reasons

    /** Money per source and reason between two days (only changes a plugin gave a reason for). */
    public CompletableFuture<List<ReasonTotal>> reasonTotals(String fromDay, String toDay, int limit) {
        return db.query(c -> {
            List<ReasonTotal> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source, reason, SUM(gained), SUM(lost), SUM(entries) FROM "
                    + REASON_DAILY + " WHERE day >= ? AND day <= ? GROUP BY source, reason "
                    + "ORDER BY SUM(gained) + SUM(lost) DESC LIMIT ?")) {
                ps.setString(1, fromDay);
                ps.setString(2, toDay);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ReasonTotal(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getInt(5)));
                    }
                }
            }
            return out;
        });
    }

    public record ReasonTotal(String source, String reason, double gained, double lost, int entries) {
        public double net() {
            return gained - lost;
        }
    }

    // ------------------------------------------------------------------ players and playtime

    /**
     * Notes that a player is here: their name, when they last were, and their first join (the earliest one
     * known is kept: the server's own record, which goes back before dkLedger was installed).
     */
    public CompletableFuture<Void> touchPlayer(UUID uuid, String name, long firstJoin, long now) {
        String sql = mysql()
                ? "INSERT INTO " + PLAYERS + " (uuid, name, first_join, first_tracked, last_seen) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen), first_join = LEAST(first_join, VALUES(first_join))"
                : "INSERT INTO " + PLAYERS + " (uuid, name, first_join, first_tracked, last_seen) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen, "
                + "first_join = MIN(first_join, excluded.first_join)";
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cut(name, 16));
                ps.setLong(3, firstJoin);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
        });
    }

    /** Adds minutes played to what's already saved. */
    public CompletableFuture<Void> savePlaytime(List<com.direk.dkledger.players.PlaytimeBuffer.PlayerDay> days) {
        String sql = mysql()
                ? "INSERT INTO " + PLAYTIME + " (day, uuid, active_minutes, afk_minutes, sessions) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE active_minutes = active_minutes + VALUES(active_minutes), "
                + "afk_minutes = afk_minutes + VALUES(afk_minutes), sessions = sessions + VALUES(sessions)"
                : "INSERT INTO " + PLAYTIME + " (day, uuid, active_minutes, afk_minutes, sessions) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, uuid) DO UPDATE SET active_minutes = active_minutes + excluded.active_minutes, "
                + "afk_minutes = afk_minutes + excluded.afk_minutes, sessions = sessions + excluded.sessions";
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (var d : days) {
                    ps.setString(1, d.day());
                    ps.setString(2, d.player().toString());
                    ps.setInt(3, d.activeMinutes());
                    ps.setInt(4, d.afkMinutes());
                    ps.setInt(5, d.sessions());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /**
     * What dkLedger knows about how a player plays.
     *
     * @param firstJoin     their first join (0 if dkLedger hasn't seen them yet)
     * @param firstTracked  when dkLedger first saw them
     * @param lastSeen      their last join or quit
     * @param activeMinutes minutes active since the day asked for
     * @param afkMinutes    minutes AFK since then
     * @param sessions      joins since then
     * @param days          days they played since then
     */
    public record Activity(long firstJoin, long firstTracked, long lastSeen, int activeMinutes, int afkMinutes, int sessions, int days) {
    }

    public CompletableFuture<Activity> activity(UUID uuid, String fromDay) {
        return db.query(c -> {
            long first = 0, tracked = 0, last = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT first_join, first_tracked, last_seen FROM " + PLAYERS + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        first = rs.getLong(1);
                        tracked = rs.getLong(2);
                        last = rs.getLong(3);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT SUM(active_minutes), SUM(afk_minutes), SUM(sessions), COUNT(*) FROM "
                    + PLAYTIME + " WHERE uuid = ? AND day >= ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, fromDay);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Activity(first, tracked, last, rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                }
            }
            return new Activity(first, tracked, last, 0, 0, 0, 0);
        });
    }

    // ------------------------------------------------------------------ progress events

    public CompletableFuture<Void> addEvent(com.direk.dkledger.players.ProgressEvent e) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + EVENTS
                    + " (time, uuid, name, plugin, type, event_key, value) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, e.time());
                ps.setString(2, e.player().toString());
                ps.setString(3, e.name());
                ps.setString(4, e.plugin());
                ps.setString(5, e.type());
                ps.setString(6, e.key());
                ps.setDouble(7, e.value());
                ps.executeUpdate();
            }
        });
    }

    /** A player's newest progress events, newest first. */
    public CompletableFuture<List<com.direk.dkledger.players.ProgressEvent>> events(UUID uuid, int limit) {
        return db.query(c -> {
            List<com.direk.dkledger.players.ProgressEvent> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT time, uuid, name, plugin, type, event_key, value FROM " + EVENTS
                    + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new com.direk.dkledger.players.ProgressEvent(rs.getLong(1), uuid, rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getDouble(7)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ staff (login summary)

    /**
     * A staff member's login summary settings.
     *
     * @param lastQuit   when they last left (0 = never seen)
     * @param mutedUntil no summary before this time
     * @param enabled    false if they turned the summary off
     */
    public record Staff(UUID uuid, long lastQuit, long mutedUntil, boolean enabled) {
    }

    public CompletableFuture<Staff> staff(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_quit, muted_until, enabled FROM " + STAFF + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Staff(uuid, rs.getLong(1), rs.getLong(2), rs.getInt(3) != 0);
                }
            }
            return new Staff(uuid, 0, 0, true);
        });
    }

    public CompletableFuture<Void> saveStaff(Staff s) {
        return db.execute(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + STAFF + " WHERE uuid = ?")) {
                del.setString(1, s.uuid().toString());
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + STAFF + " (uuid, last_quit, muted_until, enabled) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, s.uuid().toString());
                ps.setLong(2, s.lastQuit());
                ps.setLong(3, s.mutedUntil());
                ps.setInt(4, s.enabled() ? 1 : 0);
                ps.executeUpdate();
            }
        });
    }

    /** Money in and out per source since a time, from single entries: source -> {gained, lost}. */
    public CompletableFuture<Map<String, double[]>> sourcesSince(long since) {
        return db.query(c -> {
            Map<String, double[]> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source, SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), "
                    + "SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) FROM " + ENTRIES + " WHERE time >= ? GROUP BY source")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), new double[]{rs.getDouble(2), rs.getDouble(3)});
                }
            }
            return out;
        });
    }

    /** Sources that moved money for the first time since a time. */
    public CompletableFuture<List<String>> newSourcesSince(long since) {
        return db.query(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source FROM " + SOURCES + " WHERE first_seen >= ? ORDER BY first_seen")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    /** Players whose first join was since a time. */
    public CompletableFuture<Integer> newPlayersSince(long since) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + PLAYERS + " WHERE first_join >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------------ meta

    public CompletableFuture<Optional<String>> meta(String key) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM " + META + " WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> meta(String key, String value) {
        return db.execute(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + META + " WHERE k = ?")) {
                del.setString(1, key);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + META + " (k, v) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    private static void inTransaction(Connection c, Work work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            work.run();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static UUID uuid(String text) {
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
