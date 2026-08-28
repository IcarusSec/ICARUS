package icarus.evidence;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.JsonParser;
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
 * blob — a ".icarus" project file. Pure data-layer: no Swing, no file I/O, no live
 * {@code MontoyaApi} instance needed (Montoya's {@code HttpRequest}/{@code HttpResponse}/
 * {@code ByteArray} are plain value-object factories). The caller (Orchestrator) drives the
 * actual file chooser, background thread, and {@code FindingRegistry} re-registration
 * around this.
 */
public final class ProjectStateCodec {

    private ProjectStateCodec() {}

    public record ImportedItem(Finding finding, byte[] imageBytes, String caption, boolean included) {}

    public record ImportResult(List<ImportedItem> items, ReportTemplateConfig reportTemplateConfig) {}

    public static String export(List<CapturedEvidence> evidence, Predicate<CapturedEvidence> isIncluded, ReportTemplateConfig rtc) {
        List<Object> findingsJson = new ArrayList<>();
        for (CapturedEvidence ce : evidence) {
            findingsJson.add(findingToJson(ce, isIncluded.test(ce)));
        }

        // Reuses ReportTemplateConfig's own JSON shape (Step 02/03) instead of inventing a
        // second serialization for the same data — round-trips through a throwaway ModuleConfig.
        ModuleConfig scratch = new ModuleConfig();
        rtc.saveTo(scratch);
        Object rtcJson = scratch.getJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1.0);
        root.put("findings", findingsJson);
        root.put("reportTemplateConfig", rtcJson);
        return JsonParser.write(root);
    }

    private static Map<String, Object> findingToJson(CapturedEvidence ce, boolean included) {
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
        m.put("imagePng", Base64.getEncoder().encodeToString(imageToPngBytes(ce.image())));

        HttpRequestResponse rr = f.evidence();
        if (rr != null && rr.request() != null) {
            Map<String, Object> evidenceJson = new LinkedHashMap<>();
            evidenceJson.put("request", Base64.getEncoder().encodeToString(rr.request().toByteArray().getBytes()));
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

        return new ImportResult(items, rtc);
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
                HttpRequest request = HttpRequest.httpRequest(ByteArray.byteArray(Base64.getDecoder().decode(String.valueOf(reqB64))));
                HttpResponse response = null;
                Object resB64 = evidenceMap.get("response");
                if (resB64 != null) {
                    response = HttpResponse.httpResponse(ByteArray.byteArray(Base64.getDecoder().decode(String.valueOf(resB64))));
                }
                builder.evidence(HttpRequestResponse.httpRequestResponse(request, response));
            }
        }

        Finding finding = builder.build();
        byte[] imageBytes = Base64.getDecoder().decode(String.valueOf(m.getOrDefault("imagePng", "")));
        String caption = String.valueOf(m.getOrDefault("caption", ""));
        boolean included = Boolean.TRUE.equals(m.getOrDefault("included", true));
        return new ImportedItem(finding, imageBytes, caption, included);
    }
}
