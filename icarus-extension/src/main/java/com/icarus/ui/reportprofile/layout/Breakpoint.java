package com.icarus.ui.reportprofile.layout;

public enum Breakpoint {
    COMPACT, NARROW, REGULAR, ULTRAWIDE;

    public static Breakpoint forWidth(int widthPx) {
        if (widthPx < 680)  return COMPACT;
        if (widthPx < 1000) return NARROW;
        if (widthPx < 1600) return REGULAR;
        return ULTRAWIDE;
    }
}
