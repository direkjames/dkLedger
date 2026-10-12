package com.direk.dkledger.audit.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Null-safe helpers for walking YAML trees (maps, lists and plain values).
 * Keys are compared as text, so {@code 1:} and {@code '1':} are the same key.
 */
public final class Node {

    private Node() {
    }

    /** The value at a dotted path such as "island.reset.reset-limit", or null. */
    public static Object at(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            current = child(current, part);
            if (current == null) return null;
        }
        return current;
    }

    /** One key of a map (no dots), or null. */
    public static Object child(Object node, String key) {
        if (!(node instanceof Map<?, ?> map)) return null;
        Object direct = map.get(key);
        if (direct != null) return direct;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (String.valueOf(e.getKey()).equals(key)) return e.getValue();
        }
        return null;
    }

    /** The node as a map with text keys, in file order. Empty if it isn't a map. */
    public static Map<String, Object> map(Object node) {
        if (!(node instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    /** The node as a list. A single value becomes a one-item list; null becomes empty. */
    public static List<Object> list(Object node) {
        if (node == null) return List.of();
        if (node instanceof List<?> list) return new ArrayList<>(list);
        return List.of(node);
    }

    /** The node as text lines (for command lists). */
    public static List<String> strings(Object node) {
        List<String> out = new ArrayList<>();
        for (Object o : list(node)) {
            if (o != null && !(o instanceof Map<?, ?>) && !(o instanceof List<?>)) out.add(String.valueOf(o));
        }
        return out;
    }

    public static String str(Object node, String fallback) {
        if (node == null || node instanceof Map<?, ?> || node instanceof List<?>) return fallback;
        return String.valueOf(node);
    }

    /** A number, also from text like "1,500" or "75.5". */
    public static double num(Object node, double fallback) {
        if (node instanceof Number n) return n.doubleValue();
        if (node instanceof String s) {
            try {
                return Double.parseDouble(s.trim().replace(",", "").replace("_", ""));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Object node, boolean fallback) {
        if (node instanceof Boolean b) return b;
        if (node instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }
}
