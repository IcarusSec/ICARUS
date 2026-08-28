package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import icarus.core.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
            dontAsk && extData.getInteger(projPrefix + "cooldown_wait_ms") != null ? extData.getInteger(projPrefix + "cooldown_wait_ms") : config.getInt("rl.cooldown_wait_ms", 60000),
            dontAsk && extData.getInteger(projPrefix + "max_rps") != null ? extData.getInteger(projPrefix + "max_rps") : config.getInt("rl.max_rps", 0)
        };
        boolean[] proceed = new boolean[] {
            dontAsk,
            dontAsk && Boolean.TRUE.equals(extData.getBoolean(projPrefix + "detect_only")),
            dontAsk && Boolean.TRUE.equals(extData.getBoolean(projPrefix + "export_log"))
        };

        if (!dontAsk) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    JTextField txtCount = new JTextField(String.valueOf(params[0]));
                    JTextField txtConcurrency = new JTextField(String.valueOf(params[1]));
                    JTextField txtCooldown = new JTextField(String.valueOf(params[2]));
                    JTextField txtRps = new JTextField(String.valueOf(params[3]));
                    JCheckBox chkDetectOnly = new JCheckBox("Detection only (prove enforcement, no bypasses)", proceed[1]);
                    JCheckBox chkExport = new JCheckBox("Export audit log (.txt)", proceed[2]);
                    JCheckBox chkDontAsk = new JCheckBox("Don't ask again for this project");

                    Object[] message = {
                        "Number of requests:", txtCount,
                        "Thread count (concurrency):", txtConcurrency,
                        "Delay / Cooldown between bypasses (ms):", txtCooldown,
                        "Max RPS (0 = unlimited):", txtRps,
                        " ", chkDetectOnly,
                        " ", chkExport,
                        " ", chkDontAsk
                    };

                    int option = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(), message, "Rate Limit Tester Configuration", JOptionPane.OK_CANCEL_OPTION);
                    if (option == JOptionPane.OK_OPTION) {
                        try {
                            params[0] = Integer.parseInt(txtCount.getText().trim());
                            params[1] = Integer.parseInt(txtConcurrency.getText().trim());
                            params[2] = Integer.parseInt(txtCooldown.getText().trim());
                            params[3] = Integer.parseInt(txtRps.getText().trim());
                            proceed[0] = true;
                            proceed[1] = chkDetectOnly.isSelected();
                            proceed[2] = chkExport.isSelected();

                            if (chkDontAsk.isSelected()) {
                                extData.setBoolean(projPrefix + "dont_ask_again", true);
                                extData.setInteger(projPrefix + "request_count", params[0]);
                                extData.setInteger(projPrefix + "concurrency", params[1]);
                                extData.setInteger(projPrefix + "cooldown_wait_ms", params[2]);
                                extData.setInteger(projPrefix + "max_rps", params[3]);
                                extData.setBoolean(projPrefix + "detect_only", proceed[1]);
                                extData.setBoolean(projPrefix + "export_log", proceed[2]);
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
        int maxRps = Math.max(0, params[3]);
        boolean detectOnly = proceed[1];
        boolean exportLog = proceed[2];

        HttpRequest baseRequest = requestResponse.request();
        String fullPath = baseRequest.path();
        String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf("?")) : fullPath;

        List<Finding> findings = new ArrayList<>();

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String startTime = LocalDateTime.now().format(dtf);
        long detectionStartMs = System.currentTimeMillis();

        // ── Phase 1: Detection ──
        BlastResult detection = blast(baseRequest, totalRequests, concurrency, maxRps, logger);

        long detectionElapsedMs = Math.max(1, System.currentTimeMillis() - detectionStartMs);
        String endTime = LocalDateTime.now().format(dtf);

        if (detection.serverCrashed) {
            logger.accept("Server crashed (status " + detection.blockStatus + ") — halting bypass attempts.");
            findings.add(Finding.builder(name(), "SERVER_CRASH")
                    .description(String.format("Rate limit threshold or connection drop reached: %d of %d requests completed before failure (Status: %d / Connection Terminated).",
                            detection.requestsSent, totalRequests, detection.blockStatus))
                    .severity(Severity.HIGH)
                    .category(Category.RATE_LIMIT)
                    .path(path)
                    .evidence(detection.blockEvidence != null ? detection.blockEvidence : requestResponse)
                    .meta("requests_sent", String.valueOf(detection.requestsSent))
                    .meta("crash_status", String.valueOf(detection.blockStatus))
                    .meta("blast_log", detection.serializedLog)
                    .meta("start_time", startTime)
                    .meta("end_time", endTime)
                    .build());
            if (exportLog) {
                exportAuditLog(config, path, totalRequests, concurrency, maxRps, detection,
                        "Target crashed at request #" + detection.requestsSent + " (status " + detection.blockStatus + ")",
                        "", startTime, endTime);
            }
            return findings;
        }

        if (detection.blockedAt < 0) {
            double seconds = detectionElapsedMs / 1000.0;
            double rps = detection.requestsSent / seconds;
            String rpsStr = String.format("%.1f RPS", rps);

            if (detection.dominantStatus == 403 || detection.dominantStatus == 401 || detection.dominantStatus == 503) {
                findings.add(Finding.builder(name(), "BASELINE_FORBIDDEN")
                        .description(String.format("Baseline request inherently returned status %d. Cannot determine if rate limiting exists.", detection.dominantStatus))
                        .severity(Severity.INFO)
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
                if (exportLog) {
                    exportAuditLog(config, path, totalRequests, concurrency, maxRps, detection,
                            "Baseline request inherently returned status " + detection.dominantStatus + ". Cannot determine if rate limiting exists.",
                            "", startTime, endTime);
                }
                return findings;
            }

            // No rate limiting detected — that IS a finding. RPS isn't repeated here since
            // the evidence image already appends it from the "rps" meta field below.
            findings.add(Finding.builder(name(), "NO_RATE_LIMIT")
                    .description(String.format("No rate limiting detected after %d identical requests. All responses returned status %d in %.1f seconds.",
                            detection.requestsSent, detection.dominantStatus, seconds))
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
            if (exportLog) {
                exportAuditLog(config, path, totalRequests, concurrency, maxRps, detection,
                        "No rate limiting detected after " + detection.requestsSent + " requests.",
                        "", startTime, endTime);
            }
            return findings;
        }

        // ── Phase 2: Characterization ──
        String blockType = describeBlockType(detection);
        StringBuilder bypassLog = new StringBuilder();

        // ── Phase 3: Bypass Attempts (skipped in detect-only mode, or if stopped mid-Phase-1) ──
        icarus.ScanRunner.waitIfPaused();
        if (!detectOnly && !icarus.ScanRunner.isCancelled()) {
            // Wait for cooldown before bypass attempts
            try { Thread.sleep(Math.min(cooldownMs, 5000)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            if (config.getBool("rl.bypass_headers", true) && !icarus.ScanRunner.isCancelled()) {
                tryHeaderBypass(baseRequest, detection, totalRequests, concurrency, maxRps, bypassLog, path, logger);
            }

            if (config.getBool("rl.bypass_path", true) && !icarus.ScanRunner.isCancelled()) {
                tryPathBypass(baseRequest, detection, totalRequests, concurrency, maxRps, bypassLog, path, cooldownMs, logger);
            }

            if (config.getBool("rl.bypass_query", true) && !icarus.ScanRunner.isCancelled()) {
                tryQueryBypass(baseRequest, detection, totalRequests, concurrency, maxRps, bypassLog, path, cooldownMs, logger);
            }
        }
        if (icarus.ScanRunner.isCancelled()) {
            logger.accept("Rate Limit Testing aborted by user — reporting on partial results.");
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
                    .description("Rate limiting confirmed — blocked after "
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
                    .description("Rate limiting detected — blocked after "
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

        if (exportLog) {
            exportAuditLog(config, path, totalRequests, concurrency, maxRps, detection, blockType, bypassLog.toString(), startTime, endTime);
        }

        return findings;
    }

    // ── Blast Engine ──

    private BlastResult blast(HttpRequest request, int count, int concurrency, int maxRps, Consumer<String> logger) {
        return blastWithMutator(count, concurrency, maxRps, idx -> request, logger);
    }

    /**
     * Sends {@code count} requests concurrently, each built by {@code mutator} from its index,
     * and analyzes the responses for a rate-limit block. Shared by {@link #blast} and the
     * bypass-attempt methods below, which previously each reimplemented this loop.
     */
    private static final int SKIPPED_STATUS = -1;

    private BlastResult blastWithMutator(int count, int concurrency, int maxRps, java.util.function.IntFunction<HttpRequest> mutator, Consumer<String> logger) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<SingleResult>> futures = new ArrayList<>();
        AtomicInteger blockCount = new AtomicInteger(0);
        AtomicInteger crashCount = new AtomicInteger(0);
        AtomicInteger sentCount = new AtomicInteger(0);

        // Ticket-based global pacing: every worker thread atomically claims the next send
        // slot, so the aggregate rate across all `concurrency` threads is capped at maxRps.
        // A per-thread Thread.sleep(1000/maxRps) would instead cap each thread independently,
        // letting the real aggregate rate reach concurrency * maxRps.
        long intervalMs = maxRps > 0 ? Math.max(1, 1000L / maxRps) : 0;
        AtomicLong nextSlotMs = new AtomicLong(System.currentTimeMillis());

        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                // Once the block is confirmed (3+ block responses observed), stop actually
                // sending — the threshold is already known and further requests just add load.
                // Same debounce for a crashing server (3+ 0/500/502/504 responses) so a downed
                // target doesn't get hammered with the rest of `count` requests. Same skip on
                // a user-requested stop: pool.shutdownNow() interrupts in-progress tasks, but a
                // still-queued task starting after that needs its own check too.
                if (blockCount.get() >= 3 || crashCount.get() >= 3 || icarus.ScanRunner.isCancelled()) {
                    return new SingleResult(idx, SKIPPED_STATUS, 0, 0, 0, null);
                }
                // Counted before sendRequest() so a mid-request exception still counts as sent.
                int current = sentCount.incrementAndGet();
                if (count > 0 && current % Math.max(1, count / 10) == 0) {
                    logger.accept(String.format("[%d%%] Reached %d requests from %d.", (int) ((current / (double) count) * 100), current, count));
                }

                if (intervalMs > 0) {
                    long slot = nextSlotMs.getAndAdd(intervalMs);
                    long waitMs = slot - System.currentTimeMillis();
                    if (waitMs > 0) {
                        try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                if (icarus.ScanRunner.isCancelled()) {
                    return new SingleResult(idx, SKIPPED_STATUS, 0, 0, 0, null);
                }

                HttpRequest mutated = mutator.apply(idx);
                long start = System.currentTimeMillis();
                HttpRequestResponse rr = api.http().sendRequest(mutated);
                long elapsed = System.currentTimeMillis() - start;
                int status = rr.response() != null ? rr.response().statusCode() : 0;
                int len = rr.response() != null ? rr.response().body().length() : 0;
                if (status == 429 || status == 403 || status == 503) {
                    blockCount.incrementAndGet();
                } else if (status == 0 || status == 500 || status == 502 || status == 504) {
                    crashCount.incrementAndGet();
                }
                return new SingleResult(idx, status, len, elapsed, start, rr);
            }));
        }

        List<SingleResult> results = new ArrayList<>();
        for (var f : futures) {
            if (Thread.currentThread().isInterrupted()) {
                // Stop joining immediately rather than working through the rest of `futures`
                // one by one — pool.shutdownNow() below interrupts whatever's still running.
                f.cancel(true);
                continue;
            }
            try {
                SingleResult r = f.get(30, TimeUnit.SECONDS);
                if (r.status != SKIPPED_STATUS) results.add(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore status; loop above will catch it next iteration
            } catch (Exception e) {
                results.add(new SingleResult(results.size(), 0, 0, -1, System.currentTimeMillis(), null));
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            logger.accept("Rate limit blast stopped by user.");
        }
        // shutdownNow(), not shutdown(): this pool is internal to blastWithMutator and never
        // reused, so interrupting anything still executing (whether we were cancelled or not)
        // is strictly faster cleanup, not a behavior change on the normal-completion path.
        pool.shutdownNow();

        // Sort by index to preserve order
        results.sort((a, b) -> Integer.compare(a.index, b.index));

        return analyzeResults(results, sentCount.get());
    }

    private BlastResult analyzeResults(List<SingleResult> results, int requestsSent) {
        if (results.isEmpty()) return new BlastResult(-1, 0, 0, null, "", requestsSent, false, "");

        StringBuilder sb = new StringBuilder();
        for (SingleResult r : results) {
            sb.append(r.index).append(":")
              .append(r.status).append(":")
              .append(r.elapsedMs).append(";");
        }
        String log = sb.toString();
        String auditTable = buildAuditTable(results);

        // A single 0/500/502/504 can be a transient blip — mirror the block-detection
        // debounce and only trust a server crash once 3+ requests confirm it.
        long crashSamples = results.stream().filter(r -> isCrashStatus(r.status)).count();
        if (crashSamples >= 3) {
            SingleResult firstCrash = results.stream().filter(r -> isCrashStatus(r.status)).findFirst().get();
            return new BlastResult(firstCrash.index, results.get(0).status, firstCrash.status, firstCrash.evidence, log, requestsSent, true, auditTable);
        }

        // Find the dominant (first) status code
        int firstStatus = results.get(0).status;

        // Find the first result that differs significantly. Use the SingleResult's own
        // index (not its array position) — early-stopping filters skipped entries out of
        // `results` before this scan, so position and original request index diverge.
        for (int i = 1; i < results.size(); i++) {
            SingleResult r = results.get(i);
            if (isBlockResponse(r.status, firstStatus)) {
                return new BlastResult(r.index, firstStatus, r.status, r.evidence, log, requestsSent, false, auditTable);
            }
        }

        return new BlastResult(-1, firstStatus, 0, null, log, requestsSent, false, auditTable);
    }

    // Combined-format audit table (REQ_ID | REQ_TS | RES_TS | LATENCY_MS | STATUS | CURRENT_RPS)
    // for the optional .txt export — built once per blast so every finding path (crash,
    // no-block, and the final characterized result) can reuse it.
    private String buildAuditTable(List<SingleResult> results) {
        DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        StringBuilder table = new StringBuilder();
        table.append("REQ_ID | REQ_TS       | RES_TS       | LATENCY_MS | STATUS | CURRENT_RPS\n");
        table.append("---------------------------------------------------------------------\n");
        long prevStart = -1;
        for (SingleResult r : results) {
            String reqTs = formatTs(r.startMs, tsFmt);
            String resTs = formatTs(r.startMs + r.elapsedMs, tsFmt);
            String rps = prevStart < 0 ? "-" : String.format("%.1f", 1000.0 / Math.max(1, r.startMs - prevStart));
            table.append(String.format("%-6d | %-12s | %-12s | %10d | %6d | %s%n",
                    r.index, reqTs, resTs, r.elapsedMs, r.status, rps));
            prevStart = r.startMs;
        }
        return table.toString();
    }

    private String formatTs(long epochMs, DateTimeFormatter fmt) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(fmt);
    }

    private boolean isCrashStatus(int status) {
        return status == 0 || status == 500 || status == 502 || status == 504;
    }

    private boolean isBlockResponse(int currentStatus, int normalStatus) {
        // Crash statuses are handled separately in analyzeResults — never a rate-limit block.
        if (isCrashStatus(currentStatus)) return false;
        if (currentStatus == 429) return true;
        if (currentStatus == 503 && normalStatus != 503) return true;
        if (currentStatus == 403 && normalStatus != 403) return true;
        // Significant status change from 2xx to something else
        if (normalStatus >= 200 && normalStatus < 300 && currentStatus >= 400) return true;
        return false;
    }

    // ── Bypass: IP Headers ──

    private void tryHeaderBypass(HttpRequest base, BlastResult detection, int count, int concurrency, int maxRps,
                                  StringBuilder bypassLog, String path, Consumer<String> logger) {
        String[] headers = {
                "X-Forwarded-For", "X-Real-IP", "X-Originating-IP",
                "X-Client-IP", "True-Client-IP", "CF-Connecting-IP"
        };

        // Send requests with rotating random IPs in all spoofing headers
        BlastResult bypassResult = blastWithMutator(count, concurrency, maxRps, idx -> {
            String fakeIp = "10." + (idx % 256) + "." + ((idx * 7) % 256) + "." + ((idx * 13 + 1) % 256);
            HttpRequest mutated = base;
            for (String h : headers) {
                mutated = mutated.withAddedHeader(h, fakeIp);
            }
            return mutated;
        }, logger);
        boolean bypassed = bypassResult.blockedAt < 0 || bypassResult.blockedAt > detection.blockedAt * 2;

        bypassLog.append(bypassed ? "✓ " : "✗ ")
                 .append("X-Forwarded-For rotation → ")
                 .append(bypassed ? "BYPASSED (threshold " + (bypassResult.blockedAt < 0 ? "none" : bypassResult.blockedAt) + ")"
                                  : "BLOCKED at #" + bypassResult.blockedAt)
                 .append("\n");
    }

    // ── Bypass: Path Normalization ──

    private void tryPathBypass(HttpRequest base, BlastResult detection, int count, int concurrency, int maxRps,
                                StringBuilder bypassLog, String path, int cooldownMs, Consumer<String> logger) {
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

            try { Thread.sleep(Math.min(cooldownMs, 3000)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            HttpRequest mutated = base.withPath(variant);
            BlastResult bypassResult = blast(mutated, count, concurrency, maxRps, logger);
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

    private void tryQueryBypass(HttpRequest base, BlastResult detection, int count, int concurrency, int maxRps,
                                 StringBuilder bypassLog, String path, int cooldownMs, Consumer<String> logger) {
        try { Thread.sleep(Math.min(cooldownMs, 3000)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Each request gets a unique query parameter
        BlastResult bypassResult = blastWithMutator(count, concurrency, maxRps, idx -> {
            String sep = base.path().contains("?") ? "&" : "?";
            return base.withPath(base.path() + sep + "_icarus=" + idx);
        }, logger);
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

    // ── Audit Log Export (Option 4: Combined Format) ──

    private void exportAuditLog(ModuleConfig config, String path, int totalRequests, int concurrency, int maxRps,
                                 BlastResult detection, String resultSummary, String bypassLog, String startTime, String endTime) {
        StringBuilder audit = new StringBuilder();
        audit.append("=====================================================================\n");
        audit.append("ICARUS Rate Limit Tester — Audit Log\n");
        audit.append("ENDPOINT: ").append(path).append("\n");
        audit.append("START: ").append(startTime).append("   END: ").append(endTime).append("\n");
        audit.append("REQUESTS: ").append(detection.requestsSent).append(" / ").append(totalRequests)
                .append("   CONCURRENCY: ").append(concurrency)
                .append("   MAX_RPS: ").append(maxRps == 0 ? "unlimited" : String.valueOf(maxRps)).append("\n");
        audit.append("RESULT: ").append(resultSummary).append("\n");
        if (bypassLog != null && !bypassLog.isEmpty()) {
            audit.append("BYPASS ATTEMPTS:\n").append(bypassLog);
        }
        audit.append("=====================================================================\n");
        audit.append(detection.auditTable);

        promptSaveAuditLog(audit.toString(), path, config);
    }

    // Mirrors PostmanExportModule.promptSaveToFile's save pattern (JFileChooser + overwrite
    // check + remembered "evidence.output_dir") instead of silently dumping to the home dir.
    private void promptSaveAuditLog(String contents, String requestPath, ModuleConfig config) {
        Runnable showDialog = () -> {
            String lastDir = EvidencePaths.defaultOutputDir(api, config);
            JFileChooser fc = new JFileChooser(new File(lastDir));
            String suggested = "icarus_ratelimit_audit_" + requestPath.replaceAll("[^a-zA-Z0-9]+", "_") + "_" + System.currentTimeMillis() + ".txt";
            fc.setSelectedFile(new File(suggested));
            Frame parent = api.userInterface().swingUtils().suiteFrame();
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f.exists()) {
                    int overwrite = JOptionPane.showConfirmDialog(parent,
                            f.getName() + " already exists. Overwrite?",
                            "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (overwrite != JOptionPane.YES_OPTION) return;
                }
                try {
                    Files.writeString(f.toPath(), contents);
                    api.logging().logToOutput("Rate limit audit log saved to: " + f.getAbsolutePath());
                    if (f.getParentFile() != null) {
                        config.set("evidence.output_dir", f.getParentFile().getAbsolutePath());
                        api.persistence().extensionData().setString("config", config.serialize());
                    }
                } catch (Exception e) {
                    api.logging().logToError("Failed to save Rate Limit audit log: " + e);
                }
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            api.logging().logToError("Failed to show Rate Limit audit save dialog: " + e);
        }
    }

    // ── Data Classes ──

    private record SingleResult(int index, int status, int bodyLength, long elapsedMs, long startMs, HttpRequestResponse evidence) {}

    private record BlastResult(int blockedAt, int dominantStatus, int blockStatus, HttpRequestResponse blockEvidence, String serializedLog, int requestsSent, boolean serverCrashed, String auditTable) {}
}
