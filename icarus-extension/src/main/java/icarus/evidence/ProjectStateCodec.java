package icarus.evidence;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.JsonParser;
import icarus.core.KnowledgeBaseEntry;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Serializes/deserializes ICARUS Evidence Manager state (findings, screenshots, captions,
 * inclusion, and the active {@link ReportTemplateConfig}) to/from a single portable JSON
 * blob — a ".icarus" project file. Format 2 adds the findings registry, suppression rules, report
 * profiles, custom Knowledge Base entries and the logo files those settings point at
 * ({@link Workspace}). Data layer: no Swing, and the only file I/O is reading those logos; no live
 * {@code MontoyaApi} instance needed (Montoya's {@code HttpRequest}/{@code HttpResponse}/
 * {@code ByteArray} are plain value-object factories). The caller (Orchestrator) drives the
 * actual file chooser, background thread, and {@code FindingRegistry} re-registration
 * around this.
 */
public final class ProjectStateCodec {

    private ProjectStateCodec() {}

    /** {@code imageBytes} is set for a portable project file; {@code imagePath} for a {@link #exportIndex} entry. */
    public record ImportedItem(Finding finding, byte[] imageBytes, String caption, boolean included, String imagePath) {}

    /**
     * The rest of the tool state a fresh install needs (format 2). Every field is null when the
     * file does not carry it (a format-1 file). {@code registryJson} is {@link icarus.core.FindingRegistry#serializeState()};
     * {@code reportProfilesJson} is the stored user report profiles array.
     */
    public record Workspace(String registryJson, List<String> suppressedHashes, String reportProfilesJson,
                            String activeProfileId, List<KnowledgeBaseEntry> knowledgeBase) {}

    /** {@code assets} maps an {@link #ASSET_PREFIX} id to the file bytes it replaced; see {@link #resolveAssets}. */
    public record ImportResult(List<ImportedItem> items, ReportTemplateConfig reportTemplateConfig,
                               Workspace workspace, Map<String, byte[]> assets) {}

    /** Path-valued keys whose files are embedded, so logos survive a move to another machine. */
    private static final java.util.Set<String> ASSET_KEYS = java.util.Set.of("companyLogoPath", "clientLogoPath", "logoPath", "customCssPath");
    public static final String ASSET_PREFIX = "icarus-asset:";
    private static final long MAX_ASSET_BYTES = 20L * 1024 * 1024;

    /**
     * Lightweight index of the Evidence Manager for Burp's own project persistence: same finding
     * JSON as {@link #export}, but each screenshot is referenced by its on-disk path instead of
     * being Base64-embedded (it already lives in the evidence folder), so saving stays cheap
     * enough to run after every change. Read back with {@link #importFrom}.
     */
    public static String exportIndex(List<CapturedEvidence> evidence, Predicate<CapturedEvidence> isIncluded) {
        List<Object> findingsJson = new ArrayList<>();
        for (CapturedEvidence ce : evidence) {
            findingsJson.add(findingToJson(ce, isIncluded.test(ce), false));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1.0);
        root.put("findings", findingsJson);
        return JsonParser.write(root);
    }

    public static String export(List<CapturedEvidence> evidence, Predicate<CapturedEvidence> isIncluded, ReportTemplateConfig rtc,
                                Workspace workspace) {
        List<Object> findingsJson = new ArrayList<>();
        for (CapturedEvidence ce : evidence) {
            findingsJson.add(findingToJson(ce, isIncluded.test(ce), true));
        }

        // Reuses ReportTemplateConfig's own JSON shape (Step 02/03) instead of inventing a
        // second serialization for the same data — round-trips through a throwaway ModuleConfig.
        ModuleConfig scratch = new ModuleConfig();
        rtc.saveTo(scratch);
        Object rtcJson = scratch.getJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY);
        Map<String, String> assets = new LinkedHashMap<>();
        embedAssets(rtcJson, assets);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 2.0);
        root.put("findings", findingsJson);
        root.put("reportTemplateConfig", rtcJson);
        if (workspace != null) {
            if (workspace.registryJson() != null) root.put("registry", JsonParser.parse(workspace.registryJson()));
            if (workspace.suppressedHashes() != null) root.put("suppressedHashes", new ArrayList<Object>(workspace.suppressedHashes()));
            if (workspace.reportProfilesJson() != null && !workspace.reportProfilesJson().isBlank()) {
                Object profiles = JsonParser.parse(workspace.reportProfilesJson());
                embedAssets(profiles, assets);
                root.put("reportProfiles", profiles);
            }
            if (workspace.activeProfileId() != null) root.put("activeReportProfileId", workspace.activeProfileId());
            if (workspace.knowledgeBase() != null) {
                List<Object> kb = new ArrayList<>();
                for (KnowledgeBaseEntry e : workspace.knowledgeBase()) kb.add(kbToJson(e));
                root.put("knowledgeBase", kb);
            }
        }
        root.put("assets", assets);
        return JsonParser.write(root);
    }

    /** Swaps each readable file under an {@link #ASSET_KEYS} key for an {@link #ASSET_PREFIX} id and keeps its bytes in {@code assets}. */
    @SuppressWarnings("unchecked")
    private static void embedAssets(Object node, Map<String, String> assets) {
        if (node instanceof Map<?, ?> m) {
            for (var e : ((Map<String, Object>) m).entrySet()) {
                if (ASSET_KEYS.contains(e.getKey()) && e.getValue() instanceof String p && !p.isBlank()) {
                    e.setValue(embedFile(p, assets));
                } else {
                    embedAssets(e.getValue(), assets);
                }
            }
        } else if (node instanceof List<?> l) {
            l.forEach(v -> embedAssets(v, assets));
        }
    }

    private static String embedFile(String path, Map<String, String> assets) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isRegularFile(p) || java.nio.file.Files.size(p) > MAX_ASSET_BYTES) return path;
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot).toLowerCase().replaceAll("[^.a-z0-9]", "") : "";
            String id = hash + ext;
            assets.put(id, Base64.getEncoder().encodeToString(bytes));
            return ASSET_PREFIX + id;
        } catch (Exception e) {
            return path; // unreadable: keep the path as it was rather than lose the setting
        }
    }

    /** Replaces every {@link #ASSET_PREFIX} id in {@code json} with the local path it was written to. */
    public static String resolveAssets(String json, Map<String, String> localPaths) {
        if (json == null) return null;
        for (var e : localPaths.entrySet()) {
            json = json.replace("\"" + ASSET_PREFIX + e.getKey() + "\"", JsonParser.write(e.getValue()));
        }
        return json;
    }

    /** {@link #resolveAssets} for one value, e.g. a {@link ReportTemplateConfig} logo path. */
    public static String resolveAsset(String value, Map<String, String> localPaths) {
        if (value == null || !value.startsWith(ASSET_PREFIX)) return value;
        return localPaths.getOrDefault(value.substring(ASSET_PREFIX.length()), null);
    }

    private static Map<String, Object> kbToJson(KnowledgeBaseEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("severity", e.severity());
        m.put("description", e.description());
        m.put("impact", e.impact());
        m.put("recommendation", e.recommendation());
        m.put("impactLevel", e.impactLevel());
        m.put("probLevel", e.probLevel());
        m.put("cwe", e.cwe());
        m.put("deleted", e.deleted());
        return m;
    }

    private static KnowledgeBaseEntry kbFromJson(Map<?, ?> m) {
        java.util.function.Function<String, String> s = k -> m.get(k) == null ? "" : String.valueOf(m.get(k));
        return new KnowledgeBaseEntry(s.apply("name"), s.apply("severity"), s.apply("description"), s.apply("impact"),
                s.apply("recommendation"), s.apply("impactLevel"), s.apply("probLevel"), s.apply("cwe"),
                Boolean.TRUE.equals(m.get("deleted")));
    }

    private static Map<String, Object> findingToJson(CapturedEvidence ce, boolean included, boolean embedImage) {
        Finding f = ce.finding();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", f.module());
        m.put("type", f.type());
        m.put("description", f.description());
        m.put("severity", f.severity().name());
        m.put("category", f.category().name());
        m.put("path", f.path());
        m.put("cweIds", new ArrayList<Object>(f.cweIds()));
        m.put("metadata", new LinkedHashMap<Object, Object>(f.metadata()));
        m.put("caption", ce.caption());
        m.put("included", included);
        if (embedImage) {
            m.put("imagePng", Base64.getEncoder().encodeToString(imageToPngBytes(ce.image())));
        } else {
            m.put("imagePath", ce.imagePath().toAbsolutePath().normalize().toString());
        }

        HttpRequestResponse rr = f.evidence();
        if (rr != null && rr.request() != null) {
            Map<String, Object> evidenceJson = new LinkedHashMap<>();
            evidenceJson.put("request", Base64.getEncoder().encodeToString(rr.request().toByteArray().getBytes()));
            // Persist the target binding too — toByteArray() is only the raw HTTP bytes, so
            // without this a reloaded finding's request has a null HttpService and every
            // validate_finding / exploit_finding resend throws "HTTP service cannot be null".
            HttpService svc = rr.request().httpService();
            if (svc != null) {
                evidenceJson.put("host", svc.host());
                evidenceJson.put("port", svc.port());
                evidenceJson.put("secure", svc.secure());
            }
            if (rr.response() != null) {
                evidenceJson.put("response", Base64.getEncoder().encodeToString(rr.response().toByteArray().getBytes()));
            }
            m.put("evidence", evidenceJson);
        }
        return m;
    }

    private static byte[] imageToPngBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static ImportResult importFrom(String json) {
        Object parsed = JsonParser.parse(json);
        if (!(parsed instanceof Map<?, ?> rootRaw)) {
            throw new IllegalArgumentException("Not a valid ICARUS project file");
        }
        Map<String, Object> root = (Map<String, Object>) rootRaw;

        List<ImportedItem> items = new ArrayList<>();
        for (Object rawFinding : (List<Object>) root.getOrDefault("findings", List.of())) {
            items.add(findingFromJson((Map<String, Object>) rawFinding));
        }

        ModuleConfig scratch = new ModuleConfig();
        Object rtcJson = root.get("reportTemplateConfig");
        if (rtcJson != null) scratch.setJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY, rtcJson);
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(scratch);

        Object registry = root.get("registry");
        Object suppressed = root.get("suppressedHashes");
        Object profiles = root.get("reportProfiles");
        Object kbRaw = root.get("knowledgeBase");
        List<KnowledgeBaseEntry> kb = null;
        if (kbRaw instanceof List<?> list) {
            kb = new ArrayList<>();
            for (Object o : list) if (o instanceof Map<?, ?> m && m.get("name") != null) kb.add(kbFromJson(m));
        }
        List<String> suppressedHashes = null;
        if (suppressed instanceof List<?> list) {
            suppressedHashes = new ArrayList<>();
            for (Object o : list) suppressedHashes.add(String.valueOf(o));
        }
        Workspace workspace = new Workspace(
                registry instanceof Map<?, ?> ? JsonParser.write(registry) : null,
                suppressedHashes,
                profiles instanceof List<?> ? JsonParser.write(profiles) : null,
                root.get("activeReportProfileId") instanceof String id ? id : null,
                kb);

        Map<String, byte[]> assets = new LinkedHashMap<>();
        if (root.get("assets") instanceof Map<?, ?> am) {
            for (var e : am.entrySet()) {
                String id = String.valueOf(e.getKey());
                // Ids become file names on import: refuse anything but the hash.ext shape we write.
                if (!id.matches("[0-9a-f]{16}(\\.[a-z0-9]{1,8})?")) continue;
                assets.put(id, Base64.getDecoder().decode(String.valueOf(e.getValue())));
            }
        }
        return new ImportResult(items, rtc, workspace, assets);
    }

    @SuppressWarnings("unchecked")
    private static ImportedItem findingFromJson(Map<String, Object> m) {
        Finding.Builder builder = Finding.builder(String.valueOf(m.get("module")), String.valueOf(m.get("type")))
                .description(String.valueOf(m.getOrDefault("description", "")))
                .severity(Severity.valueOf(String.valueOf(m.get("severity"))))
                .category(Category.valueOf(String.valueOf(m.get("category"))))
                .path(String.valueOf(m.getOrDefault("path", "")));

        Object cweRaw = m.get("cweIds");
        if (cweRaw instanceof List<?> list) list.forEach(id -> builder.cwe(String.valueOf(id)));

        Object metaRaw = m.get("metadata");
        if (metaRaw instanceof Map<?, ?> map) map.forEach((k, v) -> builder.meta(String.valueOf(k), String.valueOf(v)));

        Object evidenceRaw = m.get("evidence");
        if (evidenceRaw instanceof Map<?, ?> evidenceMap) {
            Object reqB64 = evidenceMap.get("request");
            if (reqB64 != null) {
                ByteArray reqBytes = ByteArray.byteArray(Base64.getDecoder().decode(String.valueOf(reqB64)));
                Object host = evidenceMap.get("host");
                Object port = evidenceMap.get("port");
                HttpRequest request = (host != null && port instanceof Number)
                        ? HttpRequest.httpRequest(
                            HttpService.httpService(
                                String.valueOf(host),
                                ((Number) port).intValue(),
                                Boolean.TRUE.equals(evidenceMap.get("secure"))),
                            reqBytes)
                        : HttpRequest.httpRequest(reqBytes); // pre-1.x project file — no target binding saved
                HttpResponse response = null;
                Object resB64 = evidenceMap.get("response");
                if (resB64 != null) {
                    response = HttpResponse.httpResponse(ByteArray.byteArray(Base64.getDecoder().decode(String.valueOf(resB64))));
                }
                builder.evidence(HttpRequestResponse.httpRequestResponse(request, response));
            }
        }

        Finding finding = builder.build();
        Object pathRaw = m.get("imagePath");
        String imagePath = pathRaw instanceof String p && !p.isBlank() ? p : null;
        byte[] imageBytes = imagePath != null ? null
                : Base64.getDecoder().decode(String.valueOf(m.getOrDefault("imagePng", "")));
        String caption = String.valueOf(m.getOrDefault("caption", ""));
        boolean included = Boolean.TRUE.equals(m.getOrDefault("included", true));
        return new ImportedItem(finding, imageBytes, caption, included, imagePath);
    }
}
