package icarus.core;

import java.util.*;

/**
 * Type-safe, hierarchical configuration bag.
 *
 * Each module reads its own namespace (e.g. "paramvalidator.test.structural").
 * The GUI settings panel writes into this; modules only read.
 *
 * Values are stored as strings and parsed on access — keeps persistence trivial.
 */
public final class ModuleConfig {

    private final Map<String, String> values;

    public ModuleConfig() {
        this.values = new LinkedHashMap<>();
    }

    public ModuleConfig(Map<String, String> initial) {
        this.values = new LinkedHashMap<>(initial);
    }

    // ── Writers (used by SettingsPanel) ──────────────────────────

    public void set(String key, String value) {
        values.put(key, value);
    }

    public void set(String key, boolean value) {
        values.put(key, String.valueOf(value));
    }

    public void set(String key, int value) {
        values.put(key, String.valueOf(value));
    }

    public void set(String key, long value) {
        values.put(key, String.valueOf(value));
    }

    // ── Readers (used by modules) ───────────────────────────────

    public String getString(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = values.get(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public long getLong(String key, long defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try { return Long.parseLong(v); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public List<String> getStringList(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Returns an unmodifiable snapshot of all key-value pairs. */
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
