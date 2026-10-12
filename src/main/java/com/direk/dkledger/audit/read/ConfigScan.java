package com.direk.dkledger.audit.read;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.Money;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads every YAML file in every plugin folder and collects:
 * <ul>
 *   <li>every command that gives money ("eco give %player% 500"), so new reward plugins show up on their own;</li>
 *   <li>every command a plugin will run, so commands that don't exist can be reported.</li>
 * </ul>
 *
 * @param money    every money-giving command
 * @param commands every command found
 * @param files    how many files were read
 * @param broken   files that couldn't be read
 */
public record ConfigScan(List<MoneyCommand> money, List<CommandRef> commands, int files, List<String> broken) {

    /**
     * @param plugin the plugin folder
     * @param file   the file, relative to the plugins folder
     * @param key    the key inside the file, e.g. "rewards.days.1.actions[0]"
     * @param amount the money given
     * @param raw    the full command
     */
    public record MoneyCommand(String plugin, String file, String key, double amount, String raw) {
    }

    /**
     * @param plugin the plugin folder
     * @param file   the file, relative to the plugins folder
     * @param key    the key inside the file
     * @param raw    the line as written
     * @param label  the command name, lower case, without "/" or prefixes like "[console]"
     * @param args   the rest of the command
     */
    public record CommandRef(String plugin, String file, String key, String raw, String label, List<String> args) {
    }

    /** "[console] ", "[player]", "[op]", "[sudo]", "[luck] {10} ", "console:", "player:"... */
    private static final Pattern RUNNER_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:\\[(?:console|player|op|sudo|luck)]\\s*(?:\\{[0-9.]+}\\s*)?|(?:console|player|op):\\s*)");
    /** Other "[tag]" lines are messages, sounds, titles... not commands. */
    private static final Pattern OTHER_TAG = Pattern.compile("^\\s*\\[[a-zA-Z_-]+]");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_.:-]+");
    private static final Set<String> COMMAND_LIST_KEYS = Set.of("rewards", "actions");
    private static final Pattern SINGLE_COMMAND_KEY = Pattern.compile("(?i).+[-_]command");
    /** Keys that hold command names (aliases, banned commands), not commands to run. */
    private static final Pattern NAME_LIST_KEY = Pattern.compile(
            "(?i).*(alias|banned|blocked|blacklist|whitelist|disabled|allowed|ignored|blacklisted|whitelisted).*");

    public static ConfigScan empty() {
        return new ConfigScan(List.of(), List.of(), 0, List.of());
    }

    public static ConfigScan run(AuditContext ctx) {
        Path root = ctx.settings.pluginsFolder;
        List<PathMatcher> skip = ctx.settings.scanSkip.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
        long maxBytes = ctx.settings.scanMaxFileKb * 1024L;

        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, 8)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .filter(p -> {
                        Path rel = root.relativize(p);
                        if (rel.getNameCount() < 2) return false; // loose files in plugins/ aren't plugin configs
                        return skip.stream().noneMatch(m -> m.matches(rel));
                    })
                    .filter(p -> {
                        try {
                            return Files.size(p) <= maxBytes;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted().toList();
        } catch (IOException e) {
            ctx.notes.add("Couldn't list the plugins folder: " + e.getMessage());
            return empty();
        }

        List<MoneyCommand> money = new ArrayList<>();
        List<CommandRef> commands = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> content = ctx.readQuietly(file);
            String rel = ctx.relative(file);
            if (content == null) {
                broken.add(rel);
                continue;
            }
            String plugin = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
            walk(ctx, plugin, rel, "", content, false, false, money, commands);
        }
        return new ConfigScan(List.copyOf(money), List.copyOf(commands), files.size(), List.copyOf(broken));
    }

    private static void walk(AuditContext ctx, String plugin, String file, String key, Object node, boolean inCommandList,
                             boolean listItem, List<MoneyCommand> money, List<CommandRef> commands) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String child = String.valueOf(e.getKey());
                String path = key.isEmpty() ? child : key + "." + child;
                boolean commandKey = child.toLowerCase(Locale.ROOT).contains("command");
                boolean listKey = COMMAND_LIST_KEYS.contains(child.toLowerCase(Locale.ROOT)) && e.getValue() instanceof List<?>;
                // "count-command: count" or "respawn-block-command: respawnBlock check" name a command (and its aliases).
                boolean nameSetting = SINGLE_COMMAND_KEY.matcher(child).matches() && !(e.getValue() instanceof List<?>);
                boolean names = NAME_LIST_KEY.matcher(child).matches() || nameSetting;
                walk(ctx, plugin, file, path, e.getValue(), (commandKey || listKey) && !names, false, money, commands);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(ctx, plugin, file, key + "[" + i + "]", list.get(i), inCommandList, true, money, commands);
            }
        } else if (node instanceof String text) {
            OptionalDouble amount = Money.givenBy(text);
            if (amount.isPresent()) money.add(new MoneyCommand(plugin, file, key, amount.getAsDouble(), text.trim()));
            CommandRef ref = command(ctx, plugin, file, key, text, inCommandList, listItem);
            if (ref != null) commands.add(ref);
        }
    }

    /** The command in a line, if the line is one. */
    static CommandRef command(AuditContext ctx, String plugin, String file, String key, String text, boolean inCommandList,
                              boolean listItem) {
        String line = text.trim();
        if (line.isEmpty()) return null;
        Matcher prefix = RUNNER_PREFIX.matcher(line);
        boolean hasRunner = prefix.find();
        if (hasRunner) {
            line = line.substring(prefix.end());
        } else {
            if (!inCommandList || OTHER_TAG.matcher(line).find()) return null;
            // "count-command: count" names a command, it doesn't run one. Commands to run have arguments or sit in lists.
            if (!listItem && !line.trim().contains(" ")) return null;
        }
        if (line.startsWith("/")) line = line.substring(1);
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!LABEL.matcher(label).matches() || label.chars().allMatch(Character::isDigit)) return null;
        for (String ignored : ctx.settings.ignoreCommandPaths) {
            if ((file + ":" + key).startsWith(ignored)) return null;
        }
        return new CommandRef(plugin, file, key, text.trim(), label, List.copyOf(Arrays.asList(parts).subList(1, parts.length)));
    }
}
