package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.components.ThumbnailCard;
import com.icarus.ui.reportprofile.components.WireframeKind;
import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class LayoutSectionPanel implements ResponsiveSection {
    private final JPanel component = new JPanel(new GridBagLayout());
    private final JPanel coverPageGroup = new JPanel(new BorderLayout());
    private final JPanel findingCardGroup = new JPanel(new BorderLayout());
    
    private final JPanel coverPageCards = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
    private final JPanel findingCardCards = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));

    private WireframeKind selectedCover = WireframeKind.GRADIENT_HERO;
    private WireframeKind selectedFinding = WireframeKind.ELEVATED_CARD;

    private Consumer<Runnable> onStateChange = r -> r.run();

    public LayoutSectionPanel() {
        coverPageGroup.add(new JLabel("Cover Page"), BorderLayout.NORTH);
        coverPageGroup.add(coverPageCards, BorderLayout.CENTER);

        findingCardGroup.add(new JLabel("Finding Card Layout"), BorderLayout.NORTH);
        findingCardGroup.add(findingCardCards, BorderLayout.CENTER);

        renderCards();
    }

    public void setCover(WireframeKind k) { this.selectedCover = k; renderCards(); }
    public void setFinding(WireframeKind k) { this.selectedFinding = k; renderCards(); }
    public WireframeKind getCover() { return selectedCover; }
    public WireframeKind getFinding() { return selectedFinding; }

    public void setOnStateChange(Consumer<Runnable> cb) { this.onStateChange = cb; }

    private void renderCards() {
        coverPageCards.removeAll();
        coverPageCards.add(new ThumbnailCard("Gradient Hero", WireframeKind.GRADIENT_HERO, selectedCover == WireframeKind.GRADIENT_HERO, () -> updateCover(WireframeKind.GRADIENT_HERO)));
        coverPageCards.add(new ThumbnailCard("Header Band", WireframeKind.HEADER_BAND, selectedCover == WireframeKind.HEADER_BAND, () -> updateCover(WireframeKind.HEADER_BAND)));
        coverPageCards.add(new ThumbnailCard("None", WireframeKind.NONE, selectedCover == WireframeKind.NONE, () -> updateCover(WireframeKind.NONE)));

        findingCardCards.removeAll();
        findingCardCards.add(new ThumbnailCard("Elevated Card", WireframeKind.ELEVATED_CARD, selectedFinding == WireframeKind.ELEVATED_CARD, () -> updateFinding(WireframeKind.ELEVATED_CARD)));
        findingCardCards.add(new ThumbnailCard("Tabular Grid", WireframeKind.TABULAR_GRID, selectedFinding == WireframeKind.TABULAR_GRID, () -> updateFinding(WireframeKind.TABULAR_GRID)));

        coverPageCards.revalidate(); coverPageCards.repaint();
        findingCardCards.revalidate(); findingCardCards.repaint();
    }

    private void updateCover(WireframeKind k) {
        onStateChange.accept(() -> {
            selectedCover = k;
            renderCards();
        });
    }

    private void updateFinding(WireframeKind k) {
        onStateChange.accept(() -> {
            selectedFinding = k;
            renderCards();
        });
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        component.removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 0, 16, 16);
        
        if (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) {
            gbc.gridx = 0;
            gbc.gridy = 0;
            component.add(coverPageGroup, gbc);
            gbc.gridy = 1;
            component.add(findingCardGroup, gbc);
        } else {
            gbc.gridy = 0;
            gbc.gridx = 0;
            component.add(coverPageGroup, gbc);
            gbc.gridx = 1;
            component.add(findingCardGroup, gbc);
        }
        component.revalidate(); component.repaint();
    }
}
