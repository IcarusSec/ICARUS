# ICARUS – Reporting Tab UI Review

**Date:** 2026-08-30
**Build:** Burp Suite Professional v2026.7.3 · ICARUS extension
**Scope:** `ICARUS ▸ Reporting` sub-tab only (Report Profile & Actions, Layout, Sections Flow, Colors & Theme, Branding & Metadata, Content & Policy)
**Method:** visual inspection via Orca computer-use screenshots (Burp is a Java UI – no accessibility tree, screenshot-only)

---

## High – functional / data problems

### 1. "Critical" and "Not Fixed" severity badges are the same colour
`Severity Badge Colors` sets **Critical = `#CC2E2E`** and **Not Fixed = `#CC2E2E`** – identical red. In a generated report a critical finding and a not-fixed retest item are visually indistinguishable. Give "Not Fixed" its own hue (or derive it from status, not severity).

### 2. "Info" badge collides with the theme's Secondary Accent
`Info = #6E6E6E` is byte-for-byte the same as `Secondary Accent = #6E6E6E`. Info-severity badges disappear into section rules / chrome of the same grey. Pick a distinct low-chroma blue or keep grey but shift lightness.

### 3. Title / token area is unlabelled and ambiguous
Under the `Title` label there are **two** empty text inputs with a row of 8 placeholder chips (`{{team}} {{component}} {{requester}} {{environment}} {{author}} {{date}} {{finding_count}} {{finding_types}}`) wedged between them. Nothing says which box is the title, what the second box is for, or that the chips are click-to-insert. Label both fields and add helper text ("click a token to insert").

---

## Medium – layout / clipping

### 4. Sections Flow list is clipped
- Row labels truncate: "Executive Summary" renders as "Executive Summar".
- The list viewport shows only ~2.5 rows with no clear scroll affordance.
- The button column (`Up / Down / Add / Remove`) is cut off vertically – the 4th button shows only its top edge and a partial "Remo" caption. Increase the panel min-height or let it grow with the section.

### 5. "Content & Policy" right-hand toggle group is orphaned
The toggles `Why / Where / Impact / Remediation` and `When / How / Description / Evidence` sit far to the right of the left column with a large empty gap and **no group heading**. They read as disconnected controls. Add a heading (e.g. "Finding fields to include") and tighten the column gap.

### 6. Inconsistent label style for the same control type
Left column uses full phrases ("Include Evidence Screenshots", "Generate Table of Contents"); the right group uses single words ("Why", "When", …). Same widget (toggle), two different labelling conventions in one section.

### 7. "Include Evidence Screenshots" vs "Evidence" – name collision
Two separate toggles (`Include Evidence Screenshots` on the left, `Evidence` on the right). Near-identical names, no hint that one controls screenshot attachments and the other the per-finding "Evidence" text block. Rename to disambiguate.

---

## Low – polish

### 8. "Layout" section header is a checkbox; the others aren't
`☐ Layout` has a leading checkbox, while every other section header (`Report Profile & Actions`, `Sections Flow`, `Colors & Theme`, `Branding & Metadata`, `Content & Policy`) is icon + bold text. If unchecking disables the section, nothing in the section visually changes (no greying/disable state). Make section headers consistent and reflect the disabled state.

### 9. Company Logo / Client Logo are bare text inputs
No "Browse…" button and no drag-and-drop target – unclear whether a file path, a URL, or a paste is expected. Add a file picker.

### 10. "Max bytes" spinners weakly grouped
The `4,096` spinners for HTTP Request/Response sit well right of their parent toggles with a floating "Max bytes" label between – the association with each toggle is easy to misread. Move each spinner next to its toggle or indent it under it.

### 11. Font Size field is oversized
The Font Size input is ~full-column width (matches the Font Family dropdown) for a 2-digit value. Shrink it to a compact stepper.

### 12. Uneven vertical rhythm
Large empty band between the Severity Badge row and "Branding & Metadata" compared with tighter spacing elsewhere. Normalise section spacing.

### 13. Verify layout on ultra-wide windows
At ~2000 px window width the panel fills nicely; on a 3440 px maximised window the content block appeared to stop well short, leaving a large empty left/right gutter. Confirm the container max-width / alignment behaviour on wide monitors.

---

## Not blocking / observations
- Cover Page and Finding Card Layout thumbnails only indicate selection with an orange border – fine, but the "None" cover option gives no preview of what "no cover" looks like.
- `Preview PDF` / `Preview HTML` / `Save Profile` buttons – "Save Profile" is greyed until a change is made (expected); worth confirming it re-enables reliably after edits in every sub-section.
