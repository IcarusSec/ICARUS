package icarus.ui.reportprofile.layout;

public enum Breakpoint {
    COMPACT, NARROW, REGULAR, ULTRAWIDE;

    public static Breakpoint forWidth(int widthPx) {
        // Thresholds are the *outer* container width; cards/insets eat ~60-80px,
        // so a 1150px window only has ~1080px of usable content. Keep NARROW
        // (stacked / 2-col) up to 1200 so side-by-side never overflows the tab.
        if (widthPx < 720)  return COMPACT;
        if (widthPx < 1200) return NARROW;
        if (widthPx < 1500) return REGULAR;
        return ULTRAWIDE;
    }
}
