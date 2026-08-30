package com.icarus.ui.reportprofile.layout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Root panel for the whole tab. Owns the ultrawide centering gutter and
 * broadcasts breakpoint changes to any child that opts in via
 * {@link ResponsiveSection}. Must be wrapped in a JScrollPane by the caller
 * (see ReportProfilePanel) — this class does not scroll itself.
 */
public final class ResponsiveContainer extends JPanel implements Scrollable {
    private static final int MAX_CONTENT_WIDTH = 1400;

    private final JPanel content = new JPanel(new GridBagLayout());
    private final JPanel contentWrapper = new JPanel(new BorderLayout()) {
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(MAX_CONTENT_WIDTH, Integer.MAX_VALUE);
        }
    };
    
    private final List<ResponsiveSection> sections = new ArrayList<>();
    private Breakpoint current = null; // forces first layout on addNotify
    
    // Vertical glue to absorb extra space
    private final Component verticalGlue = Box.createVerticalGlue();

    public ResponsiveContainer() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        
        contentWrapper.add(content, BorderLayout.CENTER);
        
        add(Box.createHorizontalGlue());
        add(contentWrapper);
        add(Box.createHorizontalGlue());
        
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                applyBreakpoint(Breakpoint.forWidth(innerWidth()));
            }
        });
    }

    private int innerWidth() {
        Insets in = getInsets();
        return getWidth() - in.left - in.right;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Resize events aren't guaranteed before the first paint; seed the layout
        // so sections that build their content in onBreakpointChanged aren't blank.
        applyBreakpoint(Breakpoint.forWidth(innerWidth()));
    }

    /** Register a child that needs to re-flow on breakpoint change. Order = build/insert order. */
    public void registerSection(ResponsiveSection section) {
        sections.add(section);
        
        // Remove glue before adding new item
        content.remove(verticalGlue);
        
        content.add(section.component(), new GridBagConstraints() {{
            gridx = 0; gridy = sections.size() - 1;
            weightx = 1; fill = GridBagConstraints.HORIZONTAL;
            anchor = GridBagConstraints.NORTH;
            insets = new Insets(0, 0, 20, 0);
        }});
        
        // Re-add glue at the bottom
        content.add(verticalGlue, new GridBagConstraints() {{
            gridx = 0; gridy = sections.size();
            weightx = 1; weighty = 1; 
            fill = GridBagConstraints.VERTICAL;
        }});
    }

    private void applyBreakpoint(Breakpoint bp) {
        if (bp == current) return;
        current = bp;
        for (ResponsiveSection s : sections) s.onBreakpointChanged(bp);
        revalidate();
        repaint();
    }
    
    // -- Scrollable Implementation --
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 100;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
