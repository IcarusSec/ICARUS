package icarus.modules;

/**
 * WAF / CDN vendors ICARUS fingerprints. Launch scope: the big 5 + GENERIC
 * (see PLAN.md §7-B); other vendors fall through to GENERIC until added.
 */
public enum WafVendor {
    CLOUDFLARE, AKAMAI, AWS_WAF, IMPERVA_INCAPSULA, F5_BIGIP_ASM, GENERIC
}
