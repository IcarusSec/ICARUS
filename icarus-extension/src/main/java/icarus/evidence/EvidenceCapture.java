package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.Category;
import icarus.core.EvidencePaths;
import icarus.core.Finding;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.ui.ToastNotification;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EvidenceCapture {



    final MontoyaApi api;
    final ModuleConfig config;
    final List<CapturedEvidence> captured = new ArrayList<>();
    final CweRepository cweRepository = new CweRepository();

    // Lets a piece of evidence be left out of the *next* report without discarding it —
    // "Remove Evidence" is destructive (the screenshot is gone), this is a reversible toggle.
    private final Set<CapturedEvidence> excludedFromReport = new HashSet<>();

    // Notified with the final, identity-stable Finding once evidence is saved — lets the
    // caller (Orchestrator) fold it into the same registry the Results tab and report
    // button read from, without EvidenceCapture needing to know about FindingRegistry.
    Consumer<Finding> onApplied = f -> {};

    public EvidenceCapture(MontoyaApi api, ModuleConfig config) {
        this.api = api;
        this.config = config;
    }

    public void setOnApplied(Consumer<Finding> onApplied) {
        this.onApplied = onApplied;
    }

    public void removeCaptured(CapturedEvidence evidence) {
        captured.remove(evidence);
        excludedFromReport.remove(evidence);
    }

    /** Discards all captured evidence and inclusion state — used when importing a project file to fully replace current state. */
    public void clearAll() {
        captured.clear();
        excludedFromReport.clear();
    }

    /** Adds a reconstructed piece of evidence (e.g. from an imported project file) without going through the interactive capture flow. */
    public void restoreCaptured(CapturedEvidence evidence, boolean included) {
        captured.add(evidence);
        if (!included) excludedFromReport.add(evidence);
    }

    public void setIncluded(CapturedEvidence evidence, boolean included) {
        if (included) {
            excludedFromReport.remove(evidence);
        } else {
            excludedFromReport.add(evidence);
        }
    }

    public boolean isIncluded(CapturedEvidence evidence) {
        return !excludedFromReport.contains(evidence);
    }

    /**
     * Report order follows this list's order. The Evidence Manager drags rows around its own
     * copy of {@link #getCaptured()} and then calls this once with the full new order to sync
     * it back — simpler than translating individual drag gestures into swap operations here.
     */
    public void reorderCaptured(List<CapturedEvidence> newOrder) {
        captured.clear();
        captured.addAll(newOrder);
    }

    public List<CapturedEvidence> getCaptured() {
        return List.copyOf(captured);
    }

    /**
     * Replaces {@code evidence}'s caption in place (records are immutable, so this swaps in
     * a new instance at the same list position — preserving report order and carrying over
     * inclusion/exclusion, which is tracked by object identity in {@link #excludedFromReport}).
     *
     * Matched by {@code imagePath} (unique per capture, and stable across edits) rather than
     * full record equality/identity — {@code equals()} on a record includes every field, so
     * once the caption changes, the caller's original reference no longer matches anything in
     * this list and every subsequent call here would silently no-op. Returns the new instance
     * so the caller can keep tracking "this card's evidence" across edits instead of holding a
     * now-stale reference.
     */
    public CapturedEvidence setCaption(CapturedEvidence evidence, String caption) {
        return replace(evidence, evidence.finding(), caption, null);
    }

    /**
     * Re-assigns a piece of evidence to a different finding — e.g. a screenshot captured
     * against the wrong finding, one that turns out to belong under another, or a finding
     * being renamed (every one of its evidence items gets moved to a new {@link Finding}
     * instance with the changed title). Since grouping is by {@link Finding#similarityHash()}
     * (see {@link #groupedBySimilarityHash()}), this is what actually moves it between groups
     * in the Evidence Manager. Also repaints the screenshot's header banner (the fixed "ICARUS
     * · title" / "severity · description" strip every capture path draws — see
     * {@link #repaintHeaderForFinding}) and persists it back to {@code evidence.imagePath()}, so
     * the image itself reflects wherever it ended up, not just the in-memory Finding link.
     * Same imagePath-based matching and identity-swap semantics as {@link #setCaption} — see
     * that method's doc for why.
     */
    public CapturedEvidence moveToFinding(CapturedEvidence evidence, Finding targetFinding) {
        int idx = indexByImagePath(evidence.imagePath());
        if (idx < 0) return evidence;
        CapturedEvidence current = captured.get(idx);
        BufferedImage repainted = repaintHeaderForFinding(current.image(), targetFinding);
        try {
            ImageIO.write(repainted, "png", current.imagePath().toFile());
        } catch (IOException e) {
            api.logging().logToError("Failed to persist updated evidence header image after move: " + e);
            // Still reassign the finding even if the on-disk image couldn't be updated —
            // the Evidence Manager/report generators read the in-memory image either way.
        }
        return replace(evidence, targetFinding, current.caption(), repainted);
    }

    /**
     * Re-paints the fixed header banner (title + severity/description subtitle) baked into an
     * evidence screenshot at capture time, using {@code newFinding}'s title/severity/
     * description and the currently configured color scheme. Every screenshot this extension
     * produces shares the same banner geometry — a filled 0,0-to-width,70 strip, title at
     * (20,30), subtitle at (20,55); see {@link #renderTextToImage} and
     * {@link #drawRateLimitTable} — so repainting just that region doesn't need the original
     * request/response text.
     *
     * ponytail: if the color scheme setting changed since capture, the repainted header uses
     * the CURRENT scheme rather than matching the rest of the (untouched) image pixel-for-pixel
     * — a minor, rare cosmetic mismatch, not worth persisting the original scheme per screenshot
     * to avoid. Also doesn't account for a user-drawn annotation (Phase 2) overlapping the
     * header region — an edge case rare enough not to special-case here.
     */
    public BufferedImage repaintHeaderForFinding(BufferedImage image, Finding newFinding) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));
        int imgWidth = image.getWidth();

        EvidenceImageRenderer.drawHeaderBanner(g, imgWidth, cs, "ICARUS  ·  " + newFinding.type() + EvidenceImageRenderer.projectNameSuffix(api, config), newFinding.severity().name() + "  ·  " + newFinding.description());

        g.dispose();
        return out;
    }

    private int indexByImagePath(Path imagePath) {
        for (int i = 0; i < captured.size(); i++) {
            if (captured.get(i).imagePath().equals(imagePath)) return i;
        }
        return -1;
    }

    /**
     * Matched by {@code imagePath} (unique per capture, and stable across edits) rather than
     * full record equality/identity — {@code equals()} on a record includes every field, so
     * once caption/finding changes, the caller's original reference no longer matches anything
     * in this list and a lookup by equals/identity would silently no-op. Returns the new
     * instance so the caller can keep tracking "this card's evidence" across edits instead of
     * holding a now-stale reference. {@code image} of {@code null} keeps the current image.
     */
    private CapturedEvidence replace(CapturedEvidence evidence, Finding finding, String caption, BufferedImage image) {
        int idx = indexByImagePath(evidence.imagePath());
        if (idx < 0) return evidence;
        CapturedEvidence current = captured.get(idx);
        BufferedImage finalImage = image != null ? image : current.image();
        boolean wasExcluded = excludedFromReport.contains(current);
        CapturedEvidence updated = new CapturedEvidence(finding, current.imagePath(), finalImage, caption);
        captured.set(idx, updated);
        if (wasExcluded) {
            excludedFromReport.remove(current);
            excludedFromReport.add(updated);
        }
        return updated;
    }

    /**
     * Groups included evidence by {@link Finding#similarityHash()} instead of Finding object
     * identity — re-editing a finding's evidence (Evidence Editor "Apply") builds a brand new
     * Finding instance with the same hash, so identity-based lookups silently orphaned every
     * prior screenshot for that finding. Hash grouping is what makes 1-finding-to-N-evidence
     * actually work: every piece of evidence captured against "the same" finding (by hash)
     * lands in one group, regardless of which edit produced its Finding instance.
     * List order (both within a group and across groups) follows this object's internal
     * `captured` order, which drag-and-drop reordering in the Evidence Manager controls.
     */
    public Map<String, List<CapturedEvidence>> groupedBySimilarityHash() {
        Map<String, List<CapturedEvidence>> grouped = new java.util.LinkedHashMap<>();
        for (CapturedEvidence ce : captured) {
            if (!isIncluded(ce)) continue;
            grouped.computeIfAbsent(ce.finding().similarityHash(), h -> new ArrayList<>()).add(ce);
        }
        return grouped;
    }

    public void captureInteractive(Finding finding) {
        SwingUtilities.invokeLater(() -> {
            new EvidencePhase1Dialog(this).showPhase1(finding);
        });
    }

    /** Same direct-to-annotation entry point the RATE_LIMIT table image above uses, for a
     *  screenshot that already exists (e.g. pasted from the system clipboard) — skips Phase 1
     *  text cleanup since there's no request/response text to render. */
    public void captureInteractiveWithImage(Finding finding, BufferedImage image) {
        SwingUtilities.invokeLater(() -> new EvidencePhase2Dialog(this).showPhase2(new JFrame(), finding, image, finding.type()));
    }

    // ===================================================================================
    // PHASE 1: TEXT CLEANUP
    // ===================================================================================




    /**
     * Writes the rendered evidence image to the configured output directory (no save
     * dialog — this is the one-click path) and hands the finished {@code finding} to
     * {@link #onApplied}, which folds it into the same registry the Results tab and
     * "Generate HTML Report" read from. Using this exact finding object as both the
     * {@link CapturedEvidence} key and the registered finding is what makes the
     * screenshot actually show up in the report — a mismatch there was silently
     * dropping evidence images before.
     */
    void saveAndRegisterEvidence(Finding finding, BufferedImage image) {
        try {
            Path dir = Path.of(EvidencePaths.defaultOutputDir(api, config));
            Files.createDirectories(dir);
            String filename = "evidence-" + finding.type().replaceAll("[^a-zA-Z0-9.-]", "_") + "-" + System.currentTimeMillis() + ".png";
            Path out = dir.resolve(filename);
            ImageIO.write(image, "png", out.toFile());

            captured.add(new CapturedEvidence(finding, out, image, ""));
            onApplied.accept(finding);
            ToastNotification.show(api.userInterface().swingUtils().suiteFrame(),
                    "Evidence added to report: " + finding.type());
        } catch (IOException e) {
            api.logging().logToError("Failed to save evidence screenshot: " + e);
            JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(),
                    "Failed to save evidence screenshot: " + e.getMessage());
        }
    }










    // ===================================================================================
    // IMAGE RENDERING
    // ===================================================================================










    // ===================================================================================
    // PHASE 2: VISUAL ANNOTATION
    // ===================================================================================



    /**
     * Builds a shaft + closed triangular head. The shaft stops at the head's base
     * (not the tip) so it doesn't poke through the filled head once drawAnnotation
     * fills this shape.
     */





    /** @param caption editable text tied to this specific piece of evidence, shown beneath its image in reports (Step 05/06). */
    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image, String caption) {}
}
