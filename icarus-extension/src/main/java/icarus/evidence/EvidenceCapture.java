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

    // Burp Dark Theme Colors
    public static final Color BG_COLOR = new Color(34, 34, 34);
    public static final Color TEXT_COLOR = new Color(190, 190, 190);
    public static final Color ACCENT_COLOR = new Color(37, 99, 235);
    public static final Color SEPARATOR_COLOR = new Color(80, 80, 80);

    public static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    public static final Font BOLD_FONT = new Font(Font.MONOSPACED, Font.BOLD, 16);
    public static final int BINARY_TRUNCATE_BYTES = 2048;

    public static final int HEADER_LOGO_SIZE = 38;
    // Fixed at the old logo's center (left=20, size=48 -> center=44) so changing HEADER_LOGO_SIZE
    // resizes the logo in place instead of shifting where it visually sits.
    public static final int HEADER_LOGO_CENTER_X = 44;
    // Prescaled to 3x the display size: drawHeaderLogo then draws it down to HEADER_LOGO_SIZE
    // with bilinear interpolation, which supersamples away the jaggies a direct small render has.
    public static final BufferedImage LOGO = EvidenceImageRenderer.loadScaledLogo(HEADER_LOGO_SIZE * 3);

    

    

    private final MontoyaApi api;
    final ModuleConfig config;
    public final List<CapturedEvidence> captured = new ArrayList<>();
    public final CweRepository cweRepository = new CweRepository();

    // Lets a piece of evidence be left out of the *next* report without discarding it —
    // "Remove Evidence" is destructive (the screenshot is gone), this is a reversible toggle.
    private final Set<CapturedEvidence> excludedFromReport = new HashSet<>();

    // Notified with the final, identity-stable Finding once evidence is saved — lets the
    // caller (Orchestrator) fold it into the same registry the Results tab and report
    // button read from, without EvidenceCapture needing to know about FindingRegistry.
    public Consumer<Finding> onApplied = f -> {};

    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public void notifyChangeListeners() {
        icarus.core.DebugLog.log("EvidenceCapture.notifyChangeListeners: " + captured.size()
                + " captured, " + changeListeners.size() + " listeners");
        for (Runnable listener : changeListeners) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    icarus.core.DebugLog.timed("EvidenceCapture change listener (inline, " + captured.size() + " captured)", listener);
                } else {
                    SwingUtilities.invokeLater(() -> icarus.core.DebugLog.timed(
                            "EvidenceCapture change listener (queued, " + captured.size() + " captured)", listener));
                }
            } catch (Exception e) {
                if (api != null && api.logging() != null) {
                    api.logging().logToError("Error in EvidenceCapture change listener: " + e);
                }
            }
        }
    }

    public final EvidenceImageRenderer imageRenderer;
    public final RateLimitTableRenderer tableRenderer;
    public final EvidenceAnnotator annotator;
    public final EvidencePhase1Dialog phase1Dialog;
    public final EvidencePhase2Dialog phase2Dialog;
    public final EvidenceUiHelpers uiHelpers;

    public EvidenceCapture(MontoyaApi api, ModuleConfig config) {
        this.imageRenderer = new EvidenceImageRenderer(this, api, config);
        this.tableRenderer = new RateLimitTableRenderer(this, api, config);
        this.annotator = new EvidenceAnnotator(this, api, config);
        this.phase1Dialog = new EvidencePhase1Dialog(this, api, config);
        this.phase2Dialog = new EvidencePhase2Dialog(this, api, config);
        this.uiHelpers = new EvidenceUiHelpers(this, api, config);

        this.api = api;
        this.config = config;
    }

    public void setOnApplied(Consumer<Finding> onApplied) {
        this.onApplied = onApplied;
    }

    public void removeCaptured(CapturedEvidence evidence) {
        captured.remove(evidence);
        excludedFromReport.remove(evidence);
        notifyChangeListeners();
    }

    /** Discards all captured evidence and inclusion state — used when importing a project file to fully replace current state. */
    public void clearAll() {
        captured.clear();
        excludedFromReport.clear();
        notifyChangeListeners();
    }

    /** Adds a reconstructed piece of evidence (e.g. from an imported project file) without going through the interactive capture flow. */
    public void restoreCaptured(CapturedEvidence evidence, boolean included) {
        captured.add(evidence);
        if (!included) excludedFromReport.add(evidence);
        notifyChangeListeners();
    }

    public void setIncluded(CapturedEvidence evidence, boolean included) {
        if (included) {
            excludedFromReport.remove(evidence);
        } else {
            excludedFromReport.add(evidence);
        }
        notifyChangeListeners();
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
        notifyChangeListeners();
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
     * produces shares the same banner geometry — a filled 0,0-to-width,70 strip, logo/title at
     * {@link #drawHeaderLogo}'s x, subtitle 25px below — so repainting just that region doesn't
     * need the original request/response text.
     *
     * ponytail: if the color scheme setting changed since capture, the repainted header uses
     * the CURRENT scheme rather than matching the rest of the (untouched) image pixel-for-pixel
     * — a minor, rare cosmetic mismatch, not worth persisting the original scheme per screenshot
     * to avoid. Also doesn't account for a user-drawn annotation overlapping the header region
     * — an edge case rare enough not to special-case here.
     */
    public BufferedImage repaintHeaderForFinding(BufferedImage image, Finding newFinding) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Catppuccin"));
        int imgWidth = image.getWidth();

        g.setColor(cs.headerBg());
        g.fillRect(0, 0, imgWidth, 70);
        g.setColor(cs.divider());
        g.drawLine(0, 70, imgWidth, 70);

        int titleX = imageRenderer.drawHeaderLogo(g, 70);
        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS  ·  " + newFinding.type() + imageRenderer.projectNameSuffix(), titleX, 30);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        String severity = newFinding.severity().name();
        g.setColor(EvidenceImageRenderer.severityTokenColor(severity, cs));
        g.drawString(severity, titleX, 55);
        int severityWidth = g.getFontMetrics().stringWidth(severity);

        g.setColor(cs.dim());
        g.drawString("  ·  " + newFinding.description(), titleX + severityWidth, 55);

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
        notifyChangeListeners();
        return updated;
    }

    /**
     * Groups included evidence by {@link Finding#similarityHash()} instead of Finding object
     * identity — re-editing a finding's evidence builds a brand new Finding instance with the
     * same hash, so identity-based lookups silently orphaned every prior screenshot for that
     * finding. List order (both within a group and across groups) follows this object's
     * internal `captured` order.
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
            phase1Dialog.showPhase1(finding);
        });
    }

    /** Same direct-to-annotation entry point the RATE_LIMIT table image above uses, for a
     *  screenshot that already exists (e.g. pasted from the system clipboard) — skips Phase 1
     *  text cleanup since there's no request/response text to render. */
    public void captureInteractiveWithImage(Finding finding, BufferedImage image) {
        SwingUtilities.invokeLater(() -> phase2Dialog.showPhase2(new JFrame(), finding, image, finding.type()));
    }

    /**
     * Fully non-interactive capture for findings with no HTTP request/response to render —
     * the MCP server's {@code add_finding} tool, where an LLM reports a manually-confirmed
     * vulnerability (PoC request/response it captured itself) rather than one caught by a
     * module. {@code rawRequest} and {@code rawResponse} are rendered into their own columns
     * via {@link #renderTextToImage} verbatim — kept separate (rather than one combined blob
     * jammed into the left column) so the image actually shows REQUEST and RESPONSE instead of
     * whatever prose the caller wrote. Saves the PNG under {@link EvidencePaths#defaultOutputDir}
     * and applies it immediately — no dialog, no EDT hop needed since nothing here touches Swing
     * state shared with an open editor.
     */
    public void captureManual(Finding finding, String rawRequest, String rawResponse) {
        int wrapWidth = phase1Dialog.maxCharsForColumnWidth(1200);
        String wrappedRequest = phase1Dialog.wrapEvidenceText(rawRequest == null ? "" : rawRequest, wrapWidth);
        String wrappedResponse = phase1Dialog.wrapEvidenceText(rawResponse == null ? "" : rawResponse, wrapWidth);
        BufferedImage img = imageRenderer.renderTextToImage(wrappedRequest, wrappedResponse, finding.type(), finding.description(),
                finding.severity().name(), false);
        try {
            Path dir = EvidencePaths.evidenceImageDir(api, config);
            Files.createDirectories(dir);
            String filename = "evidence-manual-" + finding.type().replaceAll("[^a-zA-Z0-9.-]", "_")
                    + "-" + System.currentTimeMillis() + ".png";
            Path imagePath = dir.resolve(filename);
            ImageIO.write(img, "png", imagePath.toFile());
            CapturedEvidence ce = new CapturedEvidence(finding, imagePath, img, "");
            restoreCaptured(ce, true);
            onApplied.accept(finding);
        } catch (IOException e) {
            api.logging().logToError("Failed to save manual evidence image: " + e);
        }
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
    public void saveAndRegisterEvidence(Finding finding, BufferedImage image) {
        try {
            Path dir = EvidencePaths.evidenceImageDir(api, config);
            Files.createDirectories(dir);
            String filename = "evidence-" + finding.type().replaceAll("[^a-zA-Z0-9.-]", "_") + "-" + System.currentTimeMillis() + ".png";
            Path out = dir.resolve(filename);
            ImageIO.write(image, "png", out.toFile());

            captured.add(new CapturedEvidence(finding, out, image, ""));
            onApplied.accept(finding);
            notifyChangeListeners();
            ToastNotification.show(api.userInterface().swingUtils().suiteFrame(),
                    "Evidence added to report: " + finding.type());
        } catch (IOException e) {
            api.logging().logToError("Failed to save evidence screenshot: " + e);
            JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(),
                    "Failed to save evidence screenshot: " + e.getMessage());
        }
    }

    

    

    

    

    

    

    

    /**
     * Computes how many monospace characters actually fit in a request/response column's
     * real pixel width, so wrapping matches what will actually be drawn instead of a
     * hardcoded guess. Both MONO_FONT and BOLD_FONT are monospace, so per-char advance
     * width is uniform within each — checking both covers request/status lines (bold) and
     * everything else (plain).
     */
    

    /**
     * Wraps text to maxLineLength, breaking on the last whitespace before the limit when
     * one exists (so prose doesn't split mid-word) and falling back to a hard character
     * break when a single token (URL, base64 blob, etc.) has no whitespace to break on.
     * Continuation lines keep the original line's leading indentation, so a wrapped JSON
     * or header value stays visually aligned within its structure instead of collapsing
     * to the left margin.
     */
    

    // ===================================================================================
    // IMAGE RENDERING
    // ===================================================================================

    

    /**
     * Draws as many pre-wrapped lines as fit in [startY, imgHeight), then a dim truncation
     * marker for whatever's left — instead of the caller growing the image to fit everything
     * (which is what let a long JSON body balloon into a multi-thousand-pixel-tall PNG). Line
     * wrapping/indentation and drawLine's JSON/header syntax coloring are untouched; this only
     * bounds how many of the already-wrapped lines get drawn.
     */
    

    /**
     * @param lastValueColor single-element carrier holding the color of the most recently
     *                       drawn key/value's value, so a wrapped continuation line (no
     *                       leading quote/colon of its own to classify by) can be drawn in
     *                       the same color as the value it's continuing instead of falling
     *                       back to the generic default. Reset to null on any line that
     *                       isn't itself indented, since that means it's fresh top-level
     *                       content, not a continuation. Re-derived from the current text's
     *                       own indentation every draw, so it stays correct even after the
     *                       user edits the text in showPhase1's JTextArea.
     */
    

    

    

    

    

    

    

    

    // ===================================================================================
    // PHASE 2: VISUAL ANNOTATION
    // ===================================================================================

    

    /** Flattens the base snapshot + committed annotations into one image. Shared by Save and Copy. */
    

    /** Draws the ICARUS logo centered on {@code HEADER_LOGO_CENTER_X} in the header banner; returns the x to resume text at. */
    

    /**
     * Builds a shaft + closed triangular head. The shaft stops at the head's base
     * (not the tip) so it doesn't poke through the filled head once drawAnnotation
     * fills this shape.
     */
    
    

    

    

    

    

    
    /** @param caption editable text tied to this specific piece of evidence, shown beneath its image in reports. */
    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image, String caption) {}
}
