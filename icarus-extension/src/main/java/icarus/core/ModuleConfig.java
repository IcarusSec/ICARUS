package icarus.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
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

    public List<String> getStringList(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Reads a JSON-valued key (e.g. a structured config blob like
     * {@code report.template_config.json}) and parses it via {@link JsonParser}.
     * Returns {@code null} if the key is unset or contains invalid JSON.
     */
    public Object getJson(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return JsonParser.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** Serializes {@code value} via {@link JsonParser} and stores it under {@code key}. */
    public void setJson(String key, Object value) {
        values.put(key, JsonParser.write(value));
    }

    /** Persistence key for the structured report config blob. */
    public static final String REPORT_TEMPLATE_CONFIG_KEY = "report.template_config.json";

    /** Persistence key for UI language. */
    public static final String UI_LANGUAGE_KEY = "ui.language";

    /**
     * One-time migration: if no {@code report.template_config.json} exists yet,
     * synthesize a default one.
     * Safe to call on every startup — no-ops once the key is present.
     */
    public void migrateReportTemplateConfigIfNeeded() {
        if (getJson(REPORT_TEMPLATE_CONFIG_KEY) != null) return;

        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("primaryColor", null);
        theme.put("secondaryColor", null);
        theme.put("customCssPath", null);

        Map<String, Object> defaultConfig = new LinkedHashMap<>();
        defaultConfig.put("sections", List.of());
        defaultConfig.put("variables", new LinkedHashMap<>());
        defaultConfig.put("theme", theme);
        defaultConfig.put("retestStatuses", List.of("Fixed", "Not Fixed"));
        defaultConfig.put("retestSuppressedSections", List.of());
        defaultConfig.put("tocEnabled", true);

        setJson(REPORT_TEMPLATE_CONFIG_KEY, defaultConfig);
    }

    /** Removes all entries. Used by "Reset to Default" before re-applying defaults. */
    public void clear() {
        values.clear();
    }

    // ── Persistence (round-trips through a single string, e.g. Burp's extensionData) ──

    /** Serializes all entries as Java {@link Properties} text. */
    public String serialize() {
        Properties props = new Properties();
        props.putAll(values);
        StringWriter writer = new StringWriter();
        try {
            props.store(writer, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // StringWriter never actually throws
        }
        return writer.toString();
    }

    /** Loads entries from a string produced by {@link #serialize()}. */
    public void loadSerialized(String serialized) {
        if (serialized == null) return;
        Properties props = new Properties();
        try {
            props.load(new StringReader(serialized));
        } catch (IOException e) {
            throw new UncheckedIOException(e); // StringReader never actually throws
        }
        for (String name : props.stringPropertyNames()) {
            values.put(name, props.getProperty(name));
        }
    }
}
