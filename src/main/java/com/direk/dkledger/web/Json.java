package com.direk.dkledger.web;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small JSON writer for the dashboard's answers: maps, lists, text, numbers, true/false and null.
 * Numbers that aren't numbers (NaN, infinity) are written as null.
 */
public final class Json {

    private Json() {
    }

    /** A map that keeps its keys in the order they were added. */
    public static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        return m;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> number(sb, d);
            case Float f -> number(sb, f.doubleValue());
            case Number n -> sb.append(n.longValue());
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            default -> string(sb, String.valueOf(v));
        }
    }

    private static void number(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(Math.round(d * 10_000) / 10_000.0);
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // Also escaped so the JSON can never close a <script> or start an HTML comment if it's ever embedded.
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
