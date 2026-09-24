package icarus;

import burp.api.montoya.MontoyaApi;
import icarus.core.Finding;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ProjectStateCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps ICARUS state in Burp's project file while Burp runs, not just at unload.
 *
 * <p>Before this, the findings registry was written only by the unloading handler, and the
 * Evidence Manager (screenshots, captions, inclusion, order) was never persisted at all: a Burp
 * crash lost the whole session's findings, and even a clean restart came back to an empty
 * Evidence Manager and an empty report unless the user had exported a {@code .icarus} file.
 *
 * <p>Changes only mark state dirty; one background thread writes it after a short debounce.
 * The registry (which Base64-embeds every finding's request/response and churns during passive
 * scanning) is written at most once per {@link #REGISTRY_MIN_INTERVAL_MS}; the evidence index is
 * small (screenshots are referenced by path, see {@link ProjectStateCodec#exportIndex}).
 */
final class StateAutosaver {

    static final String EVIDENCE_KEY = "icarus_evidence";
    static final String REGISTRY_KEY = "icarus_state";

    private static final long DEBOUNCE_MS = 5_000;
    private static final long REGISTRY_MIN_INTERVAL_MS = 60_000;

    private final MontoyaApi api;
    private final EvidenceCapture evidence;
    private final Supplier<String> registrySerializer;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ICARUS-autosave");
        t.setDaemon(true);
        return t;
    });

    private boolean registryDirty;
    private boolean evidenceDirty;
    private boolean restoring;
    private long lastRegistrySave;
    private ScheduledFuture<?> pending;

    StateAutosaver(MontoyaApi api, EvidenceCapture evidence, Supplier<String> registrySerializer) {
        this.api = api;
        this.evidence = evidence;
        this.registrySerializer = registrySerializer;
    }

    synchronized void markRegistryDirty() {
        registryDirty = true;
        schedule();
    }

    synchronized void markEvidenceDirty() {
        if (restoring) return; // re-adding what we just loaded isn't a change
        evidenceDirty = true;
        schedule();
    }

    private void schedule() {
        if (exec.isShutdown() || (pending != null && !pending.isDone())) return;
        long delay = DEBOUNCE_MS;
        if (registryDirty && !evidenceDirty) {
            long sinceLast = System.currentTimeMillis() - lastRegistrySave;
            delay = Math.max(DEBOUNCE_MS, REGISTRY_MIN_INTERVAL_MS - sinceLast);
        }
        pending = exec.schedule(() -> flush(false), delay, TimeUnit.MILLISECONDS);
    }

    /** Writes whatever is dirty; {@code force} also writes a registry that was saved recently. */
    private void flush(boolean force) {
        boolean saveEvidence, saveRegistry;
        synchronized (this) {
            pending = null;
            saveEvidence = evidenceDirty;
            long sinceLast = System.currentTimeMillis() - lastRegistrySave;
            saveRegistry = registryDirty && (force || sinceLast >= REGISTRY_MIN_INTERVAL_MS - 1_000);
            evidenceDirty = false;
            if (saveRegistry) {
                registryDirty = false;
                lastRegistrySave = System.currentTimeMillis();
            }
        }
        try {
            if (saveEvidence) {
                api.persistence().extensionData().setString(EVIDENCE_KEY,
                        ProjectStateCodec.exportIndex(evidence.getCaptured(), evidence::isIncluded));
            }
            if (saveRegistry) {
                api.persistence().extensionData().setString(REGISTRY_KEY, registrySerializer.get());
            }
        } catch (Exception e) {
            api.logging().logToError("ICARUS autosave failed: " + e);
            synchronized (this) {
                if (saveEvidence) evidenceDirty = true;
                if (saveRegistry) registryDirty = true;
            }
        }
        synchronized (this) {
            if (registryDirty || evidenceDirty) schedule(); // e.g. registry deferred by the rate limit
        }
    }

    /**
     * Reloads the persisted Evidence Manager index in the background (decoding a few dozen
     * full-HD PNGs shouldn't block extension load). Screenshots whose file is gone are skipped
     * and logged. {@code ensureRegistered} receives every restored finding so one whose registry
     * record didn't make it into the last registry save is still reportable.
     */
    void restoreEvidenceAsync(Consumer<List<Finding>> ensureRegistered) {
        String json;
        try {
            json = api.persistence().extensionData().getString(EVIDENCE_KEY);
        } catch (Exception e) {
            return;
        }
        if (json == null || json.isBlank()) return;
        exec.execute(() -> {
            synchronized (this) { restoring = true; }
            List<Finding> restored = new ArrayList<>();
            int missing = 0;
            try {
                for (var item : ProjectStateCodec.importFrom(json).items()) {
                    try {
                        if (item.imagePath() == null) { missing++; continue; }
                        Path path = Path.of(item.imagePath());
                        BufferedImage image = Files.isRegularFile(path) ? ImageIO.read(path.toFile()) : null;
                        if (image == null) {
                            missing++;
                            api.logging().logToError("ICARUS: evidence screenshot missing, not restored: " + path);
                            continue;
                        }
                        evidence.restoreCaptured(new EvidenceCapture.CapturedEvidence(item.finding(), path, image, item.caption()),
                                item.included());
                        restored.add(item.finding());
                    } catch (Exception e) {
                        missing++;
                        api.logging().logToError("ICARUS: could not restore an evidence item: " + e);
                    }
                }
                if (!restored.isEmpty()) ensureRegistered.accept(restored);
                api.logging().logToOutput("ICARUS: restored " + restored.size() + " evidence item(s)"
                        + (missing > 0 ? ", " + missing + " skipped" : "") + ".");
            } catch (Exception e) {
                api.logging().logToError("ICARUS: could not read saved evidence state: " + e);
            } finally {
                synchronized (this) { restoring = false; }
            }
        });
    }

    /** Final synchronous save on extension unload. */
    void shutdown() {
        synchronized (this) {
            if (pending != null) pending.cancel(false);
            registryDirty = true; // always write the registry on unload, as before
        }
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS); // let an in-flight restore/save finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            // A restore still running means the in-memory list is partial — writing it would
            // overwrite the complete saved index with a truncated one.
            evidenceDirty = !restoring;
        }
        flush(true);
    }
}
