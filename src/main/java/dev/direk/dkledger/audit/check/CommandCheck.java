package dev.direk.dkledger.audit.check;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.Check;
import dev.direk.dkledger.audit.Finding;
import dev.direk.dkledger.audit.read.ConfigScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A8: reward commands that can't work: commands no plugin registered (like "florist give" when the
 * command is "floristcoin"), and crate keys that don't exist (like "vote_key" when the key is "vote").
 */
public final class CommandCheck implements Check {

    private static final Set<String> CRATE_COMMANDS = Set.of("crates", "crate", "excellentcrates", "ecrates");

    @Override
    public String id() {
        return "A8";
    }

    @Override
    public void run(AuditContext ctx) {
        unknownCommands(ctx);
        crateKeys(ctx);
    }

    private void unknownCommands(AuditContext ctx) {
        Set<String> commands = ctx.server.commands();
        if (commands.isEmpty()) {
            ctx.notes.add("A8: unknown commands weren't checked (the scan didn't run on a server).");
            return;
        }
        Set<String> ignored = new HashSet<>();
        ctx.settings.ignoreCommands.forEach(c -> ignored.add(c.toLowerCase(Locale.ROOT)));

        Map<String, List<ConfigScan.CommandRef>> unknown = new LinkedHashMap<>();
        for (ConfigScan.CommandRef ref : ctx.scan.commands()) {
            String label = ref.label();
            String bare = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
            if (commands.contains(label) || commands.contains(bare) || ignored.contains(label) || ignored.contains(bare)) continue;
            unknown.computeIfAbsent(ref.plugin() + "\u0000" + label, k -> new ArrayList<>()).add(ref);
        }
        List<Finding> found = new ArrayList<>();
        for (List<ConfigScan.CommandRef> refs : unknown.values()) {
            ConfigScan.CommandRef first = refs.getFirst();
            StringBuilder places = new StringBuilder();
            for (int i = 0; i < Math.min(5, refs.size()); i++) {
                ConfigScan.CommandRef r = refs.get(i);
                places.append(i == 0 ? "" : "; ").append(r.file()).append(" ").append(r.key()).append(": ").append(r.raw());
            }
            found.add(Finding.warning(id(), "Unknown command /" + first.label() + " in " + first.plugin()
                            + (refs.size() > 1 ? " (" + refs.size() + " places)" : ""),
                    "No plugin registered /" + first.label() + ", so these lines do nothing: " + places + ".",
                    "Fix the command name. If it's right but registered late, add it to checks.commands.ignore.",
                    first.file() + ": " + first.key()));
        }
        ctx.addCapped(id(), found, "unknown commands");
    }

    private void crateKeys(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.crateKeysFolder);
        List<ConfigScan.CommandRef> keyGives = new ArrayList<>();
        for (ConfigScan.CommandRef ref : ctx.scan.commands()) {
            String bare = ref.label().contains(":") ? ref.label().substring(ref.label().indexOf(':') + 1) : ref.label();
            if (!CRATE_COMMANDS.contains(bare) || ref.args().size() < 4) continue;
            if (ref.args().get(0).equalsIgnoreCase("key") && ref.args().get(1).equalsIgnoreCase("give")) keyGives.add(ref);
        }
        if (keyGives.isEmpty()) return;
        if (folder == null || !Files.isDirectory(folder)) {
            ctx.notes.add("A8: crate keys weren't checked (" + String.join(" or ", ctx.settings.crateKeysFolder) + " not found).");
            return;
        }
        Set<String> keys = new HashSet<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yml"))
                    .forEach(n -> keys.add(n.substring(0, n.length() - 4).toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            return;
        }
        Map<String, List<ConfigScan.CommandRef>> missing = new LinkedHashMap<>();
        for (ConfigScan.CommandRef ref : keyGives) {
            String key = ref.args().get(3).toLowerCase(Locale.ROOT);
            if (!keys.contains(key) && !key.contains("%") && !key.contains("{") && !key.contains("<")) {
                missing.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
            }
        }
        for (Map.Entry<String, List<ConfigScan.CommandRef>> e : missing.entrySet()) {
            List<String> where = e.getValue().stream().map(r -> r.plugin() + " (" + r.file() + " " + r.key() + ")").distinct().toList();
            ctx.add(Finding.warning(id(), "Crate key \"" + e.getKey() + "\" doesn't exist",
                    "Given in " + String.join(", ", where) + ", but there's no " + e.getKey() + ".yml in "
                            + ctx.relative(folder) + ". Existing keys: " + String.join(", ", keys.stream().sorted().toList()) + ".",
                    "Use one of the existing key names.", e.getValue().getFirst().file()));
        }
    }
}
