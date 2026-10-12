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
                + "source VARCHAR(64) NOT NULL, detail VARCHAR(160) NOT NULL, other VARCHAR(36), balance DOUBLE";
        String alerts = "CREATE TABLE IF NOT EXISTS " + ALERTS + " (" + autoId() + ", time BIGINT NOT NULL, "
                + "check_id VARCHAR(8) NOT NULL, severity VARCHAR(8) NOT NULL, subject VARCHAR(80) NOT NULL, "
                + "title VARCHAR(200) NOT NULL, detail TEXT NOT NULL, resolved BIGINT, resolved_by VARCHAR(32), note VARCHAR(200)";
        String notes = "CREATE TABLE IF NOT EXISTS " + NOTES + " (" + autoId() + ", uuid VARCHAR(36) NOT NULL, "
                + "time BIGINT NOT NULL, by_name VARCHAR(32) NOT NULL, note VARCHAR(255) NOT NULL";
        if (mysql()) {
            sql.add(entries + ", INDEX idx_dkledger_entries_player (uuid, time), INDEX idx_dkledger_entries_time (time))");
            sql.add(alerts + ", INDEX idx_dkledger_alerts_time (time))");
            sql.add(notes + ", INDEX idx_dkledger_notes_player (uuid))");
        } else {
            sql.add(entries + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_entries_player ON " + ENTRIES + " (uuid, time)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_entries_time ON " + ENTRIES + " (time)");
            sql.add(alerts + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_alerts_time ON " + ALERTS + " (time)");
            sql.add(notes + ")");
            sql.add("CREATE INDEX IF NOT EXISTS idx_dkledger_notes_player ON " + NOTES + " (uuid)");
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
        return db.createTables(sql);
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
        return db.execute(c -> inTransaction(c, () -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + ENTRIES
                    + " (time, uuid, name, account, amount, source, detail, other, balance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
        }));
    }

    /** Deletes entries older than {@code before}. Daily totals and snapshots are kept forever. */
    public CompletableFuture<Integer> prune(long before) {
        return db.query(c -> {
            int removed;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + ENTRIES + " WHERE time < ?")) {
                ps.setLong(1, before);
                removed = ps.executeUpdate();
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
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, uuid, name, account, amount, source, detail, other, balance FROM "
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
            try (PreparedStatement ps = c.prepareStatement("SELECT id, time, uuid, name, account, amount, source, detail, other, balance FROM "
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
                        rs.getDouble(6), rs.getString(7), rs.getString(8), uuid(rs.getString(9)), balance));
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

    public CompletableFuture<Void> savePrices(long time, Map<String, Double> prices) {
        return db.execute(c -> {
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
