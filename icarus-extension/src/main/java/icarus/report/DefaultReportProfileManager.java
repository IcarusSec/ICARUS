package icarus.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import icarus.report.render.ReportData;
import icarus.report.render.ReportRenderContext;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe default implementation of ReportProfileManager.
 */
public class DefaultReportProfileManager implements ReportProfileManager {

    public static final String KEY_USER_PROFILES = "reporting.profiles.json";
    public static final String KEY_ACTIVE_PROFILE_ID = "reporting.active_profile_id";
    public static final String DEFAULT_BUILTIN_ID = "builtin:executive-modern";

    private final ModuleConfig config;
    private final Map<String, ReportProfile> builtIns = new LinkedHashMap<>();
    private final Map<String, ReportProfile> userProfiles = new ConcurrentHashMap<>();
    private volatile String activeProfileId = DEFAULT_BUILTIN_ID;

    public DefaultReportProfileManager(ModuleConfig config) {
        this.config = config;
        loadBuiltInProfiles();
        loadUserProfiles();
    }

    private void loadBuiltInProfiles() {
        List<String> paths = List.of(
            "/reports/profiles/executive-modern.json",
            "/reports/profiles/classic-technical.json"
        );

        for (String path : paths) {
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in != null) {
                    ReportProfile p = ReportProfileCodec.fromStream(in);
                    builtIns.put(p.id(), p);
                }
            } catch (Exception e) {
                System.err.println("[ICARUS] Warning: failed to load built-in report profile " + path + ": " + e.getMessage());
            }
        }
    }

    private synchronized void loadUserProfiles() {
        userProfiles.clear();
        String json = config != null ? config.getString(KEY_USER_PROFILES, null) : null;
        if (json != null && !json.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<ReportProfile> list = mapper.readValue(json, new TypeReference<List<ReportProfile>>() {});
                for (ReportProfile p : list) {
                    if (p != null && !p.builtIn()) {
                        userProfiles.put(p.id(), p);
                    }
                }
            } catch (Exception e) {
                System.err.println("[ICARUS] Warning: failed to parse user report profiles: " + e.getMessage());
            }
        }

        // Migrate legacy ReportTemplateConfig if no user profiles exist
        if (userProfiles.isEmpty() && config != null) {
            ReportTemplateConfig legacy = ReportTemplateConfig.fromConfig(config);
            ReportProfile base = builtIns.getOrDefault(DEFAULT_BUILTIN_ID, builtIns.values().stream().findFirst().orElse(null));
            if (legacy != null && base != null) {
                try {
                    ReportProfile migrated = ReportTemplateConfigMigrator.migrate(legacy, base);
                    if (migrated != null) {
                        userProfiles.put(migrated.id(), migrated);
                        saveToConfig();
                    }
                } catch (Exception e) {
                    System.err.println("[ICARUS] Warning: failed to migrate legacy report template: " + e.getMessage());
                }
            }
        }

        String savedActive = config != null ? config.getString(KEY_ACTIVE_PROFILE_ID, null) : null;
        if (savedActive != null && (builtIns.containsKey(savedActive) || userProfiles.containsKey(savedActive))) {
            this.activeProfileId = savedActive;
        } else if (builtIns.containsKey(DEFAULT_BUILTIN_ID)) {
            this.activeProfileId = DEFAULT_BUILTIN_ID;
        } else if (!builtIns.isEmpty()) {
            this.activeProfileId = builtIns.keySet().iterator().next();
        }
    }

    private synchronized void saveToConfig() {
        if (config == null) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(new ArrayList<>(userProfiles.values()));
            config.set(KEY_USER_PROFILES, json);
            config.set(KEY_ACTIVE_PROFILE_ID, activeProfileId);
        } catch (Exception e) {
            System.err.println("[ICARUS] Failed to save user report profiles to config: " + e.getMessage());
        }
    }

    @Override
    public List<ReportProfile> list() {
        List<ReportProfile> combined = new ArrayList<>(builtIns.values());
        combined.addAll(userProfiles.values());
        return Collections.unmodifiableList(combined);
    }

    @Override
    public ReportProfile get(String id) {
        if (id == null) return active();
        if (builtIns.containsKey(id)) return builtIns.get(id);
        return userProfiles.get(id);
    }

    @Override
    public ReportProfile active() {
        ReportProfile p = get(activeProfileId);
        if (p != null) return p;
        return builtIns.getOrDefault(DEFAULT_BUILTIN_ID, builtIns.values().iterator().next());
    }

    @Override
    public synchronized void setActive(String id) {
        if (id != null && (builtIns.containsKey(id) || userProfiles.containsKey(id))) {
            this.activeProfileId = id;
            if (config != null) {
                config.set(KEY_ACTIVE_PROFILE_ID, id);
            }
        }
    }

    @Override
    public synchronized ReportProfile clone(String sourceId, String newName) {
        ReportProfile source = get(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Source profile not found: " + sourceId);
        }
        String newId = "user:" + UUID.randomUUID();
        ReportProfile cloned = source.createClone(newId, newName);
        userProfiles.put(cloned.id(), cloned);
        saveToConfig();
        return cloned;
    }

    @Override
    public synchronized void saveUserProfile(ReportProfile profile) {
        if (profile == null) throw new IllegalArgumentException("Profile cannot be null");
        if (profile.builtIn()) {
            throw new IllegalStateException("Cannot save over built-in profile: " + profile.id());
        }
        userProfiles.put(profile.id(), profile);
        saveToConfig();
    }

    @Override
    public synchronized void deleteUserProfile(String id) {
        if (id == null) return;
        if (builtIns.containsKey(id)) {
            throw new IllegalStateException("Cannot delete built-in profile: " + id);
        }
        if (userProfiles.remove(id) != null) {
            if (activeProfileId.equals(id)) {
                activeProfileId = DEFAULT_BUILTIN_ID;
            }
            saveToConfig();
        }
    }

    @Override
    public String exportJson(String id) {
        ReportProfile profile = get(id);
        if (profile == null) throw new IllegalArgumentException("Profile not found: " + id);
        return ReportProfileCodec.toJson(profile);
    }

    @Override
    public synchronized ReportProfile importJson(String json) {
        ReportProfile imported = ReportProfileCodec.fromJson(json);
        String newId = "user:" + UUID.randomUUID();
        ReportProfile userClone = imported.createClone(newId, imported.name());
        userProfiles.put(userClone.id(), userClone);
        saveToConfig();
        return userClone;
    }

    @Override
    public ReportRenderContext bind(ReportData data, Path workingDir) {
        return bind(activeProfileId, data, workingDir);
    }

    @Override
    public ReportRenderContext bind(String profileId, ReportData data, Path workingDir) {
        ReportProfile profile = get(profileId);
        if (profile == null) profile = active();
        String locTag = profile.locale() != null ? profile.locale() : "pt-BR";
        Locale locale = Locale.forLanguageTag(locTag.replace('_', '-'));
        return new ReportRenderContext(profile, data, locale, workingDir);
    }
}
