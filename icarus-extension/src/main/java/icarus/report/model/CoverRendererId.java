package icarus.report.model;

/**
 * Supported cover page layout styles.
 */
public enum CoverRendererId {
    GRADIENT_HERO,      // Executive Modern: full-bleed gradient band with client logo card
    HEADER_BAND,        // Classic Technical: compact header stripe with document title
    NONE                // Direct flow: no cover, document begins on page 1
}
