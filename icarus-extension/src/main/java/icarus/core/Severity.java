package icarus.core;

/**
 * Severity levels for findings, ordered from most to least critical.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    public boolean isAtLeast(Severity threshold) {
        return this.ordinal() <= threshold.ordinal();
    }
}
