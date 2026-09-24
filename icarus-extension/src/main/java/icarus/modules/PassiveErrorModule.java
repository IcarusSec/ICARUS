package icarus.modules;

import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.core.VerboseErrorDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Flags HTTP 500+ responses and verbose error/stack-trace leaks (via the shared
 * {@link VerboseErrorDetector}). Runs both as an on-demand module and, via
 * {@link #analyzeResponse}, as part of the background passive scanner — same dual-signature
 * pattern as {@link SensitiveHeaderModule}.
 */
public final class PassiveErrorModule implements IcarusModule {

    @Override
    public String name() {
        return "Passive Error Detector";
    }

    @Override
    public boolean includeInBulkScan() {
        // Purely observational (reads the response Burp already has) — background/on-demand
        // only, doesn't send its own requests like the actual security-test modules do.
        return false;
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        if (!config.getBool("pem.enabled", true)) return List.of();
        return analyze(requestResponse.response(), config, requestResponse);
    }

    public List<Finding> analyzeResponse(HttpResponseReceived responseReceived, ModuleConfig config) {
        if (!config.getBool("pem.enabled", true) || !config.getBool("pem.passive", true)) return List.of();

        HttpRequestResponse evidence = HttpRequestResponse.httpRequestResponse(
                responseReceived.initiatingRequest(), responseReceived);
        return analyze(responseReceived, config, evidence);
    }

    private List<Finding> analyze(HttpResponse response, ModuleConfig config, HttpRequestResponse evidence) {
        if (response == null) return List.of();

        List<Finding> findings = new ArrayList<>();
        // Errors are per-endpoint, not host-wide config like a missing security header —
        // path must be set so a 500 on /api/users doesn't dedup away a 500 on /api/orders.
        // Query string dropped (a cache-buster made every hit a new finding) and the host kept
        // as scope, so /health on two different targets stays two findings.
        String path = evidence != null ? evidence.request().pathWithoutQuery() : "";
        String host = evidence != null ? Finding.hostScope(evidence.request()) : "";
        int status = response.statusCode();

        if (status >= 500) {
            findings.add(Finding.builder(name(), "SERVER_ERROR")
                    .description("Server returned HTTP " + status + " on `" + path + "`, indicating an unhandled error.")
                    .severity(Severity.HIGH)
                    .category(Category.SERVER_ERROR)
                    .path(path)
                    .evidence(evidence)
                    .meta("status", String.valueOf(status))
                    .meta(Finding.META_SCOPE, host)
                    .build());
        }

        // Pre-filter before the full body-string copy + ~35-regex pass: a 500+ always warrants
        // the check; otherwise only if the raw bytes carry a cheap error sentinel. Short-circuits
        // the common 200-OK-no-error response, which is the bulk of proxied traffic.
        // Static assets are skipped: JS bundles legitimately contain strings like
        // "TypeError: ..." and "Stack trace", which was the main source of false leaks.
        String verboseMatch = !isStaticAsset(response)
                && (status >= 500 || VerboseErrorDetector.mightContainVerboseError(response.body()))
                ? VerboseErrorDetector.getVerboseErrorMatch(response.bodyToString())
                : null;
        if (verboseMatch != null) {
            findings.add(Finding.builder(name(), "VERBOSE_ERROR_LEAK")
                    .description("Verbose error leak detected on `" + path + "`:\n\n" + verboseMatch)
                    .severity(Severity.HIGH)
                    .category(Category.INFORMATION_DISCLOSURE)
                    .path(path)
                    .evidence(evidence)
                    .meta("match", verboseMatch)
                    .meta(Finding.META_SCOPE, host)
                    .build());
        }

        return findings;
    }

    private static boolean isStaticAsset(HttpResponse response) {
        MimeType mime = response.statedMimeType();
        if (mime == null || mime == MimeType.NONE || mime == MimeType.UNRECOGNIZED) mime = response.inferredMimeType();
        if (mime == null) return false;
        return switch (mime) {
            case SCRIPT, CSS, IMAGE_UNKNOWN, IMAGE_JPEG, IMAGE_GIF, IMAGE_PNG, IMAGE_BMP, IMAGE_TIFF,
                 IMAGE_SVG_XML, SOUND, VIDEO, FONT_WOFF, FONT_WOFF2, APPLICATION_FLASH -> true;
            default -> false;
        };
    }
}
