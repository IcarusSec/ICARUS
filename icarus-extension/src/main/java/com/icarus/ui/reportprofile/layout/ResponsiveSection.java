package com.icarus.ui.reportprofile.layout;

import java.awt.Component;

public interface ResponsiveSection {
    Component component();
    void onBreakpointChanged(Breakpoint bp);
}
