package icarus.core;

public record RawNumber(String value) {
    /** True if this number parses as an integer literal (no '.', 'e', or 'E'). */
    public boolean isInteger() {
        return value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0;
    }

    @Override
    public String toString() {
        return value;
    }
}
