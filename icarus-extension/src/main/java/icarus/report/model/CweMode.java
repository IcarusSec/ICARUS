package icarus.report.model;

/**
 * Strategy for CWE resolution during reporting.
 */
public enum CweMode {
    HARDCODED_CATALOG,  // Full offline bundled catalog
    PROFILE_LIST        // Only CWEs in profile allowlist
}
