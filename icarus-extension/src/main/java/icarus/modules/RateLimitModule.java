package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import icarus.core.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Rate Limit module — detects, characterizes, and attempts to bypass rate limiting.
 *
 * Phase 1: Blast N identical requests (null payloads) to detect if rate limiting exists.
 * Phase 2: If detected, characterize the threshold and block type.
 * Phase 3: Attempt known bypass techniques and re-blast.
 */
public class RateLimitModule implements IcarusModule {

    private final MontoyaApi api;

    public RateLimitModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "Rate Limit Tester";
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        if (!config.getBool("rl.enabled", true)) return List.of();
        if (requestResponse == null || requestResponse.request() == null) return List.of();

        // Check project-level persistence to see if we should skip the dialog
        burp.api.montoya.persistence.PersistedObject extData = api.persistence().extensionData();
        String projPrefix = "rl_" + api.project().name() + "_";
        boolean dontAsk = extData.getBoolean(projPrefix + "dont_ask_again") != null && extData.getBoolean(projPrefix + "dont_ask_again");

        int[] params = new int[] {
            dontAsk && extData.getInteger(projPrefix + "request_count") != null ? extData.getInteger(projPrefix + "request_count") : config.getInt("rl.request_count", 50),
            dontAsk && extData.getInteger(projPrefix + "concurrency") != null ? extData.getInteger(projPrefix + "concurrency") : config.getInt("rl.concurrency", 10),
            dontAsk && extData.getInteger(projPrefix + "cooldown_wait_ms") != null ? extData.getInteger(projPrefix + "cooldown_wait_ms") : config.getInt("rl.cooldown_wait_ms", 60000)
        };
        boolean[] proceed = new boolean[] {
            dontAsk,
            dontAsk && Boolean.TRUE.equals(extData.getBoolean(projPrefix + "detect_only"))
        };

        if (!dontAsk) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    JTextField txtCount = new JTextField(String.valueOf(params[0]));
                    JTextField txtConcurrency = new JTextField(String.valueOf(params[1]));
                    JTextField txtCooldown = new JTextField(String.valueOf(params[2]));
                    JCheckBox chkDetectOnly = new JCheckBox("Detection only (prove enforcement, no bypasses)", proceed[1]);
                    JCheckBox chkDontAsk = new JCheckBox("Don't ask again for this project");

                    Object[] message = {
                        "Number of requests:", txtCount,
                        "Thread count (concurrency):", txtConcurrency,
                        "Delay / Cooldown between bypasses (ms):", txtCooldown,
                        " ", chkDetectOnly,
                        " ", chkDontAsk
                    };

                    int option = JOptionPane.showConfirmDialog(null, message, "Rate Limit Tester Configuration", JOptionPane.OK_CANCEL_OPTION);
                    if (option == JOptionPane.OK_OPTION) {
                        try {
                            params[0] = Integer.parseInt(txtCount.getText().trim());
                            params[1] = Integer.parseInt(txtConcurrency.getText().trim());
                            params[2] = Integer.parseInt(txtCooldown.getText().trim());
                            proceed[0] = true;
                            proceed[1] = chkDetectOnly.isSelected();

                            if (chkDontAsk.isSelected()) {
                                extData.setBoolean(projPrefix + "dont_ask_again", true);
                                extData.setInteger(projPrefix + "request_count", params[0]);
                                extData.setInteger(projPrefix + "concurrency", params[1]);
                                extData.setInteger(projPrefix + "cooldown_wait_ms", params[2]);
                                extData.setBoolean(projPrefix + "detect_only", proceed[1]);
                            }
                        } catch (NumberFormatException e) {
                            api.logging().logToError("Invalid integer in Rate Limit config.");
                        }
                    }
                });
            } catch (Exception e) {
                api.logging().logToError("Failed to show Rate Limit config dialog: " + e.getMessage());
            }
        }

        if (!proceed[0]) return List.of();

        int totalRequests = params[0];
        int concurrency = params[1];
        int cooldownMs = params[2];
        boolean detectOnly = proceed[1];

        HttpRequest baseRequest = requestResponse.request();
        String path = baseRequest.path();

        List<Finding> findings = new ArrayList<>();

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String startTime = LocalDateTime.now().format(dtf);
        long detectionStartMs = System.currentTimeMillis();

        // ── Phase 1: Detection ──
        BlastResult detection = blast(baseRequest, totalRequests, concurrency);

        long detectionElapsedMs = Math.max(1, System.currentTimeMillis() - detectionStartMs);
        String endTime = LocalDateTime.now().format(dtf);

        if (detection.blockedAt < 0) {
            double seconds = detectionElapsedMs / 1000.0;
            double rps = detection.requestsSent / seconds;
            String rpsStr = String.format("%.1f RPS", rps);

            // No rate limiting detected — that IS a finding
            findings.add(Finding.builder(name(), "NO_RATE_LIMIT")
                    .description(String.format("No rate limiting detected after %d identical requests to %s. All responses returned status %d. Achieved around %s in %.1f seconds.",
                            detection.requestsSent, path, detection.dominantStatus, rpsStr, seconds))
                    .severity(Severity.MEDIUM)
                    .category(Category.RATE_LIMIT)
                    .path(path)
                    .evidence(requestResponse)
                    .meta("requests_sent", String.valueOf(detection.requestsSent))
                    .meta("concurrency", String.valueOf(concurrency))
                    .meta("all_status", String.valueOf(detection.dominantStatus))
                    .meta("rps", rpsStr)
                    .meta("blast_log", detection.serializedLog)
                    .meta("start_time", startTime)
                    .meta("end_time", endTime)
                    .build());
            return findings;
        }

        // ── Phase 2: Characterization ──
        String blockType = describeBlockType(detection);
        StringBuilder bypassLog = new StringBuilder();

        // ── Phase 3: Bypass Attempts (skipped in detect-only mode) ──
        if (!detectOnly) {
            // Wait for cooldown before bypass attempts
            try { Thread.sleep(Math.min(cooldownMs, 5000)); } catch (InterruptedException ignored) {}

            if (config.getBool("rl.bypass_headers", true)) {
                tryHeaderBypass(baseRequest, detection, totalRequests, concurrency, bypassLog, path);
            }

            if (config.getBool("rl.bypass_path", true)) {
                tryPathBypass(baseRequest, detection, totalRequests, concurrency, bypassLog, path, cooldownMs);
            }

            if (config.getBool("rl.bypass_query", true)) {
                tryQueryBypass(baseRequest, detection, totalRequests, concurrency, bypassLog, path, cooldownMs);
            }
        }

        endTime = LocalDateTime.now().format(dtf);

        // Computed from the Phase-1 detection blast alone — the multi-phase elapsed time
        // (start_time to end_time) is diluted by Phase 2/3 bypass cooldowns/requests and
        // wouldn't reflect the actual rate that tripped the block. requestsSent (not
        // totalRequests) accounts for early-stopping skipping the tail once the block
        // is confirmed, so RPS reflects what was actually fired, not what was requested.
        double rps = detection.requestsSent * 1000.0 / detectionElapsedMs;
        String rpsStr = String.format("%.1f req/s", rps);

        if (detectOnly) {
            findings.add(Finding.builder(name(), "RATE_LIMIT_ENFORCED")
                    .description("Rate limiting confirmed on " + path + " — blocked after "
                            + detection.blockedAt + " requests. " + blockType
                            + " (bypass attempts skipped: detection-only mode)")
                    .severity(Severity.INFO)
                    .category(Category.RATE_LIMIT)
                    .path(path)
                    .evidence(detection.blockEvidence != null ? detection.blockEvidence : requestResponse)
                    .meta("threshold", String.valueOf(detection.blockedAt))
                    .meta("block_status", String.valueOf(detection.blockStatus))
                    .meta("block_type", blockType)
                    .meta("requests_sent", String.valueOf(detection.requestsSent))
                    .meta("rps", rpsStr)
                    .meta("blast_log", detection.serializedLog)
                    .meta("start_time", startTime)
                    .meta("end_time", endTime)
                    .build());
        } else {
            findings.add(Finding.builder(name(), "RATE_LIMIT_DETECTED")
                    .description("Rate limiting detected on " + path + " — blocked after "
                            + detection.blockedAt + " requests. " + blockType)
                    .severity(Severity.INFO)
                    .category(Category.RATE_LIMIT)
                    .path(path)
                    .evidence(detection.blockEvidence != null ? detection.blockEvidence : requestResponse)
                    .meta("threshold", String.valueOf(detection.blockedAt))
                    .meta("block_status", String.valueOf(detection.blockStatus))
                    .meta("block_type", blockType)
                    .meta("requests_sent", String.valueOf(detection.requestsSent))
                    .meta("rps", rpsStr)
                    .meta("blast_log", detection.serializedLog)
                    .meta("bypass_log", bypassLog.toString())
                    .meta("start_time", startTime)
                    .meta("end_time", endTime)
                    .build());
        }

        return findings;
    }

    // ── Blast Engine ──

    private BlastResult blast(HttpRequest request, int count, int concurrency) {
        return blastWithMutator(count, concurrency, idx -> request);
    }

    /**
     * Sends {@code count} requests concurrently, each built by {@code mutator} from its index,
     * and analyzes the responses for a rate-limit block. Shared by {@link #blast} and the
     * bypass-attempt methods below, which previously each reimplemented this loop.
     */
    private static final int SKIPPED_STATUS = -1;

    private BlastResult blastWithMutator(int count, int concurrency, java.util.function.IntFunction<HttpRequest> mutator) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<SingleResult>> futures = new ArrayList<>();
        AtomicInteger blockCount = new AtomicInteger(0);
        AtomicInteger sentCount = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                // Once the block is confirmed (3+ block responses observed), stop actually
                // sending — the threshold is already known and further requests just add load.
                if (blockCount.get() >= 3) {
                    return new SingleResult(idx, SKIPPED_STATUS, 0, 0, null);
                }
                // Counted before sendRequest() so a mid-request exception still counts as sent.
                sentCount.incrementAndGet();

                HttpRequest mutated = mutator.apply(idx);
                long start = System.currentTimeMillis();
                HttpRequestResponse rr = api.http().sendRequest(mutated);
                long elapsed = System.currentTimeMillis() - start;
                int status = rr.response() != null ? rr.response().statusCode() : 0;
                int len = rr.response() != null ? rr.response().body().length() : 0;
                if (status == 429 || status == 403 || status == 503) {
                    blockCount.incrementAndGet();
                }
                return new SingleResult(idx, status, len, elapsed, rr);
            }));
        }

        List<SingleResult> results = new ArrayList<>();
        for (var f : futures) {
            try {
                SingleResult r = f.get(30, TimeUnit.SECONDS);
                if (r.status != SKIPPED_STATUS) results.add(r);
            } catch (Exception e) {
                results.add(new SingleResult(results.size(), 0, 0, -1, null));
            }
        }
        pool.shutdown();

        // Sort by index to preserve order
        results.sort((a, b) -> Integer.compare(a.index, b.index));

        return analyzeResults(results, sentCount.get());
    }

    private BlastResult analyzeResults(List<SingleResult> results, int requestsSent) {
        if (results.isEmpty()) return new BlastResult(-1, 0, 0, null, "", requestsSent);

        StringBuilder sb = new StringBuilder();
        for (SingleResult r : results) {
            sb.append(r.index).append(":")
              .append(r.status).append(":")
              .append(r.elapsedMs).append(";");
        }
        String log = sb.toString();

        // Find the dominant (first) status code
        int firstStatus = results.get(0).status;

        // Find the first result that differs significantly. Use the SingleResult's own
        // index (not its array position) — early-stopping filters skipped entries out of
        // `results` before this scan, so position and original request index diverge.
        for (int i = 1; i < results.size(); i++) {
            SingleResult r = results.get(i);
            if (isBlockResponse(r.status, firstStatus)) {
                return new BlastResult(r.index, firstStatus, r.status, r.evidence, log, requestsSent);
            }
        }

        return new BlastResult(-1, firstStatus, 0, null, log, requestsSent);
    }

    private boolean isBlockResponse(int currentStatus, int normalStatus) {
        if (currentStatus == 429) return true;
        if (currentStatus == 503 && normalStatus != 503) return true;
        if (currentStatus == 403 && normalStatus != 403) return true;
        // Significant status change from 2xx to something else
        if (normalStatus >= 200 && normalStatus < 300 && currentStatus >= 400) return true;
        return false;
    }

    // ── Bypass: IP Headers ──

    private void tryHeaderBypass(HttpRequest base, BlastResult detection, int count, int concurrency,
                                  StringBuilder bypassLog, String path) {
        String[] headers = {
                "X-Forwarded-For", "X-Real-IP", "X-Originating-IP",
                "X-Client-IP", "True-Client-IP", "CF-Connecting-IP"
        };

        // Send requests with rotating random IPs in all spoofing headers
        BlastResult bypassResult = blastWithMutator(count, concurrency, idx -> {
            String fakeIp = "10." + (idx % 256) + "." + ((idx * 7) % 256) + "." + ((idx * 13 + 1) % 256);
            HttpRequest mutated = base;
            for (String h : headers) {
                mutated = mutated.withAddedHeader(h, fakeIp);
            }
            return mutated;
        });
        boolean bypassed = bypassResult.blockedAt < 0 || bypassResult.blockedAt > detection.blockedAt * 2;

        bypassLog.append(bypassed ? "✓ " : "✗ ")
                 .append("X-Forwarded-For rotation → ")
                 .append(bypassed ? "BYPASSED (threshold " + (bypassResult.blockedAt < 0 ? "none" : bypassResult.blockedAt) + ")"
                                  : "BLOCKED at #" + bypassResult.blockedAt)
                 .append("\n");
    }

    // ── Bypass: Path Normalization ──

    private void tryPathBypass(HttpRequest base, BlastResult detection, int count, int concurrency,
                                StringBuilder bypassLog, String path, int cooldownMs) {
        String[] pathVariants = {
                path + "/",
                path.replaceFirst("/", "/./"),
                path.replaceFirst("/", "//"),
                path + "/.",
        };

        // Also add a URL-encoded variant of the last path segment
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            String segment = path.substring(lastSlash + 1);
            StringBuilder encoded = new StringBuilder();
            for (char c : segment.toCharArray()) {
                encoded.append(String.format("%%%02X", (int) c));
            }
            pathVariants = appendToArray(pathVariants, path.substring(0, lastSlash + 1) + encoded);
        }

        for (String variant : pathVariants) {
            if (variant.equals(path)) continue;

            try { Thread.sleep(Math.min(cooldownMs, 3000)); } catch (InterruptedException ignored) {}

            HttpRequest mutated = base.withPath(variant);
            BlastResult bypassResult = blast(mutated, count, concurrency);
            boolean bypassed = bypassResult.blockedAt < 0 || bypassResult.blockedAt > detection.blockedAt * 2;

            if (bypassed) {
                bypassLog.append("✓ Path normalization (").append(variant).append(") → ")
                         .append("BYPASSED (threshold ").append(bypassResult.blockedAt < 0 ? "none" : bypassResult.blockedAt).append(")\n");
                return; // One successful path bypass is enough
            }
        }

        bypassLog.append("✗ Path normalization → BLOCKED\n");
    }

    // ── Bypass: Query Cache Busting ──

    private void tryQueryBypass(HttpRequest base, BlastResult detection, int count, int concurrency,
                                 StringBuilder bypassLog, String path, int cooldownMs) {
        try { Thread.sleep(Math.min(cooldownMs, 3000)); } catch (InterruptedException ignored) {}

        // Each request gets a unique query parameter
        BlastResult bypassResult = blastWithMutator(count, concurrency, idx -> {
            String sep = base.path().contains("?") ? "&" : "?";
            return base.withPath(base.path() + sep + "_icarus=" + idx);
        });
        boolean bypassed = bypassResult.blockedAt < 0 || bypassResult.blockedAt > detection.blockedAt * 2;

        bypassLog.append(bypassed ? "✓ " : "✗ ")
                 .append("Cache-buster (?_icarus=N) → ")
                 .append(bypassed ? "BYPASSED (threshold " + (bypassResult.blockedAt < 0 ? "none" : bypassResult.blockedAt) + ")"
                                  : "BLOCKED at #" + bypassResult.blockedAt)
                 .append("\n");
    }

    // ── Helpers ──

    private String describeBlockType(BlastResult r) {
        if (r.blockStatus == 429) return "Server responded with HTTP 429 (Too Many Requests).";
        if (r.blockStatus == 503) return "Server responded with HTTP 503 (Service Unavailable).";
        if (r.blockStatus == 403) return "Server responded with HTTP 403 (Forbidden).";
        return "Server responded with HTTP " + r.blockStatus + " (status changed from " + r.dominantStatus + ").";
    }

    private String[] appendToArray(String[] arr, String item) {
        String[] result = new String[arr.length + 1];
        System.arraycopy(arr, 0, result, 0, arr.length);
        result[arr.length] = item;
        return result;
    }

    // ── Data Classes ──

    private record SingleResult(int index, int status, int bodyLength, long elapsedMs, HttpRequestResponse evidence) {}

    private record BlastResult(int blockedAt, int dominantStatus, int blockStatus, HttpRequestResponse blockEvidence, String serializedLog, int requestsSent) {}
}
