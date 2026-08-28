package icarus.evidence;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import burp.api.montoya.MontoyaApi;
import icarus.core.*;
import java.awt.geom.*;
import icarus.ui.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;
import com.formdev.flatlaf.extras.FlatSVGIcon;

public class EvidencePhase2Dialog {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public EvidencePhase2Dialog(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

public void showPhase2(JFrame parentEditor, Finding finding, BufferedImage snap, String finalTitle) {
        // Owned by the Burp suite frame so it doesn't get lost behind Burp (was a
        // top-level, owner-less JFrame before).
        java.awt.Frame parent = api.userInterface().swingUtils().suiteFrame();
        JFrame frame = new JFrame(I18n.t("evidence.phase2.title"));
        if (parent != null) frame.setIconImage(parent.getIconImage());
        java.awt.GraphicsConfiguration gc = parent != null ? parent.getGraphicsConfiguration() : null;
        java.awt.Rectangle screenBounds = gc != null ? gc.getBounds() : new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        
        int maxWidth = Math.min(1200, screenBounds.width - 50);
        int maxHeight = Math.min(800, screenBounds.height - 100);
        frame.setSize(new java.awt.Dimension(maxWidth, maxHeight));
        frame.setLocationRelativeTo(parent);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());

        // Canvas panel that paints the image and annotations at an offset (for panning)
        List<Shape> shapes = new ArrayList<>();
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();

        Color[] curCol = { Color.RED };
        String[] mode = { "PAN" }; // PAN, BOX, ARROW, HIGHLIGHT, REDACT
        Point[] dragStart = { null };
        Shape[] preview = { null };
        int[] panOffset = { 0, 0 }; // x, y offset for panning
        double[] scale = { 1.0 };

        JPanel canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics gr) {
                super.paintComponent(gr);
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.translate(panOffset[0], panOffset[1]);
                g2.scale(scale[0], scale[0]);

                // Draw image
                g2.drawImage(snap, 0, 0, null);

                // Draw committed shapes
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    drawAnnotation(g2, shapes.get(i), kinds.get(i), cols.get(i));
                }
                // Draw live preview
                if (preview[0] != null) {
                    drawAnnotation(g2, preview[0], mode[0], curCol[0]);
                }
                g2.dispose();
            }

            public void drawAnnotation(Graphics2D g2, Shape s, String kind, Color c) {
                if ("HIGHLIGHT".equals(kind)) {
                    g2.setColor(new Color(255, 255, 0, 80));
                    g2.fill(s);
                } else if ("REDACT".equals(kind)) {
                    g2.setColor(Color.BLACK);
                    g2.fill(s);
                } else if ("ARROW".equals(kind)) {
                    g2.setColor(c);
                    g2.fill(s);
                    g2.draw(s);
                } else {
                    g2.setColor(c);
                    g2.draw(s);
                }
            }
        };
        canvas.setBackground(new Color(30, 30, 30));
        canvas.setPreferredSize(new Dimension(snap.getWidth() + 200, snap.getHeight() + 200));

        MouseAdapter mouseHandler = new MouseAdapter() {
            public Point panAnchor;

            @Override
            public void mousePressed(MouseEvent e) {
                if ("PAN".equals(mode[0]) || SwingUtilities.isMiddleMouseButton(e)) {
                    panAnchor = e.getPoint();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    // Convert screen point to image-space point
                    dragStart[0] = new Point((int)((e.getX() - panOffset[0]) / scale[0]), (int)((e.getY() - panOffset[1]) / scale[0]));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panAnchor != null && ("PAN".equals(mode[0]) || SwingUtilities.isMiddleMouseButton(e))) {
                    panOffset[0] += e.getX() - panAnchor.x;
                    panOffset[1] += e.getY() - panAnchor.y;
                    panAnchor = e.getPoint();
                    canvas.repaint();
                } else if (dragStart[0] != null) {
                    Point p = new Point((int)((e.getX() - panOffset[0]) / scale[0]), (int)((e.getY() - panOffset[1]) / scale[0]));
                    if ("ARROW".equals(mode[0])) {
                        preview[0] = capture.annotator.createArrow(dragStart[0], p);
                    } else {
                        int x = Math.min(dragStart[0].x, p.x);
                        int y = Math.min(dragStart[0].y, p.y);
                        int w = Math.abs(p.x - dragStart[0].x);
                        int h = Math.abs(p.y - dragStart[0].y);
                        preview[0] = new Rectangle2D.Double(x, y, w, h);
                    }
                    canvas.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if ("PAN".equals(mode[0]) || SwingUtilities.isMiddleMouseButton(e)) {
                    panAnchor = null;
                    if ("PAN".equals(mode[0])) {
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    } else {
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    }
                } else if (preview[0] != null) {
                    shapes.add(preview[0]);
                    cols.add(curCol[0]);
                    kinds.add(mode[0]);
                    preview[0] = null;
                    dragStart[0] = null;
                    canvas.repaint();
                }
            }
            
            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                double delta = e.getPreciseWheelRotation() < 0 ? 1.1 : 0.9;
                scale[0] = Math.max(0.5, Math.min(3.0, scale[0] * delta));
                // Center zoom on mouse cursor
                panOffset[0] = (int)(e.getX() - (e.getX() - panOffset[0]) * delta);
                panOffset[1] = (int)(e.getY() - (e.getY() - panOffset[1]) * delta);
                canvas.repaint();
            }
        };
        canvas.addMouseListener(mouseHandler);
        canvas.addMouseMotionListener(mouseHandler);
        canvas.addMouseWheelListener(mouseHandler);

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        // Toolbar
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 8, 0);

        JToggleButton panBtn = new JToggleButton(I18n.t("evidence.phase2.btn.pan"), createIcon("move"), true);
        JToggleButton boxBtn = new JToggleButton(I18n.t("evidence.phase2.btn.box"), createIcon("square"));
        JToggleButton arrowBtn = new JToggleButton(I18n.t("evidence.phase2.btn.arrow"), createIcon("arrow-up-right"));
        JToggleButton hiBtn = new JToggleButton(I18n.t("evidence.phase2.btn.highlight"), createIcon("edit-2"));
        JToggleButton redactBtn = new JToggleButton(I18n.t("evidence.phase2.btn.redact"), createIcon("eye-off"));

        ButtonGroup grp = new ButtonGroup();
        grp.add(panBtn); grp.add(boxBtn); grp.add(arrowBtn); grp.add(hiBtn); grp.add(redactBtn);

        for (var b : List.of(panBtn, boxBtn, arrowBtn, hiBtn, redactBtn)) {
            b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
            b.setFocusable(false);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
            b.setForeground(Color.WHITE);
        }

        ActionListener modeSel = a -> {
            if (a.getSource() == panBtn) {
                mode[0] = "PAN";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                if (a.getSource() == boxBtn) mode[0] = "BOX";
                else if (a.getSource() == arrowBtn) mode[0] = "ARROW";
                else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
                else if (a.getSource() == redactBtn) mode[0] = "REDACT";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        };
        panBtn.addActionListener(modeSel); boxBtn.addActionListener(modeSel);
        arrowBtn.addActionListener(modeSel); hiBtn.addActionListener(modeSel);
        redactBtn.addActionListener(modeSel);

        JButton colourBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.colour"), curCol[0]);
        colourBtn.setIcon(createIcon("aperture"));
        colourBtn.setFocusable(false);
        colourBtn.setHorizontalAlignment(SwingConstants.LEFT);
        colourBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        colourBtn.setForeground(Color.WHITE);
        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(frame, I18n.t("evidence.phase2.dialog.chooseColour"), curCol[0]);
            if (chosen != null) { curCol[0] = chosen; colourBtn.setBackground(curCol[0]); }
        });

        JButton undoBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.undo"), new Color(70, 70, 70));
        undoBtn.setIcon(createIcon("corner-up-left"));
        undoBtn.setFocusable(false);
        undoBtn.setHorizontalAlignment(SwingConstants.LEFT);
        undoBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        undoBtn.setForeground(Color.WHITE);
        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                canvas.repaint();
            }
        });

        JButton saveBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.save"), EvidenceCapture.ACCENT_COLOR);
        saveBtn.setIcon(createIcon("save"));
        saveBtn.setFocusable(false);
        saveBtn.setHorizontalAlignment(SwingConstants.LEFT);
        saveBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        saveBtn.setForeground(Color.WHITE);
        saveBtn.addActionListener(a -> {
            try {
                BufferedImage out = capture.phase2Dialog.renderFinalImage(snap, shapes, kinds, cols);

                String lastDir = EvidencePaths.defaultOutputDir(api, config);
                JFileChooser fc = new JFileChooser(new File(lastDir));
                fc.setSelectedFile(new File("evidence-" + finalTitle.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png"));
                if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    ImageIO.write(out, "png", f);
                    capture.captured.add(new EvidenceCapture.CapturedEvidence(finding, f.toPath(), out, ""));
                    capture.onApplied.accept(finding);
                    if (f.getParentFile() != null) {
                        config.set("evidence.output_dir", f.getParentFile().getAbsolutePath());
                        api.persistence().extensionData().setString("config", config.serialize());
                    }
                    JOptionPane.showMessageDialog(frame, I18n.t("evidence.phase2.msg.saved") + f.getAbsolutePath());
                    // frame.dispose(); // User requested not to close immediately
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        JButton copyBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.copy"), EvidenceCapture.ACCENT_COLOR.darker());
        copyBtn.setIcon(createIcon("copy"));
        copyBtn.setFocusable(false);
        copyBtn.setHorizontalAlignment(SwingConstants.LEFT);
        copyBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        copyBtn.setForeground(Color.WHITE);
        copyBtn.addActionListener(a -> {
            try {
                BufferedImage out = capture.phase2Dialog.renderFinalImage(snap, shapes, kinds, cols);
                Transferable transferable = new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { DataFlavor.imageFlavor }; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return out; }
                };
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
                // JOptionPane.showMessageDialog(frame, "Image copied to clipboard.");
                frame.dispose(); // User requested to close when copying
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        JButton sendToReportBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.sendToReport"), EvidenceCapture.ACCENT_COLOR);
        sendToReportBtn.setIcon(createIcon("file-text"));
        sendToReportBtn.setFocusable(false);
        sendToReportBtn.setHorizontalAlignment(SwingConstants.LEFT);
        sendToReportBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        sendToReportBtn.setForeground(Color.WHITE);
        sendToReportBtn.addActionListener(a -> {
            BufferedImage out = capture.phase2Dialog.renderFinalImage(snap, shapes, kinds, cols);
            capture.saveAndRegisterEvidence(finding, out);
            JOptionPane.showMessageDialog(frame, I18n.t("evidence.phase2.msg.sent"));
            // frame.dispose(); // User requested not to close immediately
        });

        boolean[] backClicked = { false };
        JButton backBtn = capture.uiHelpers.createModernButton(I18n.t("evidence.phase2.btn.back"), new Color(100, 100, 100));
        backBtn.setIcon(createIcon("arrow-left"));
        backBtn.setFocusable(false);
        backBtn.setHorizontalAlignment(SwingConstants.LEFT);
        backBtn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 16;");
        backBtn.setForeground(Color.WHITE);
        backBtn.addActionListener(a -> {
            backClicked[0] = true;
            frame.dispose();
            parentEditor.setVisible(true);
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (!backClicked[0]) {
                    parentEditor.dispose();
                }
            }
        });

        JLabel lblWorkflow = new JLabel("Workflow");
        lblWorkflow.setFont(lblWorkflow.getFont().deriveFont(Font.BOLD, 12f));
        lblWorkflow.setForeground(Color.GRAY);
        bar.add(lblWorkflow, gbc); gbc.gridy++;
        
        bar.add(backBtn, gbc); gbc.gridy++;
        bar.add(new JSeparator(), gbc); gbc.gridy++;
        
        JLabel lblTools = new JLabel("Ferramentas");
        lblTools.setFont(lblTools.getFont().deriveFont(Font.BOLD, 12f));
        lblTools.setForeground(Color.GRAY);
        bar.add(lblTools, gbc); gbc.gridy++;
        
        bar.add(panBtn, gbc); gbc.gridy++;
        bar.add(boxBtn, gbc); gbc.gridy++;
        bar.add(arrowBtn, gbc); gbc.gridy++;
        bar.add(hiBtn, gbc); gbc.gridy++;
        bar.add(redactBtn, gbc); gbc.gridy++;
        bar.add(colourBtn, gbc); gbc.gridy++;
        bar.add(undoBtn, gbc); gbc.gridy++;
        bar.add(new JSeparator(), gbc); gbc.gridy++;
        
        JLabel lblActions = new JLabel("Ações");
        lblActions.setFont(lblActions.getFont().deriveFont(Font.BOLD, 12f));
        lblActions.setForeground(Color.GRAY);
        bar.add(lblActions, gbc); gbc.gridy++;
        
        bar.add(sendToReportBtn, gbc); gbc.gridy++;
        bar.add(saveBtn, gbc); gbc.gridy++;
        bar.add(copyBtn, gbc); gbc.gridy++;
        
        gbc.weighty = 1.0;
        bar.add(Box.createGlue(), gbc);
        JScrollPane barScroll = new JScrollPane(bar);
        barScroll.setBorder(BorderFactory.createEmptyBorder());
        barScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        barScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(barScroll, BorderLayout.EAST);

        // Flameshot-style shortcuts. Routed through doClick() on the existing toolbar
        // buttons so the ButtonGroup selection state and mode[0]/cursor side effects in
        // their ActionListener stay the single source of truth — no duplicated logic here.
        InputMap shortcutMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap shortcutActions = root.getActionMap();

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "icarus.modeBox");
        shortcutActions.put("icarus.modeBox", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { boxBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "icarus.modeArrow");
        shortcutActions.put("icarus.modeArrow", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { arrowBtn.doClick(); }
        });

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "icarus.undo");
        shortcutActions.put("icarus.undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { undoBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "icarus.modeHighlight");
        shortcutActions.put("icarus.modeHighlight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { hiBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "icarus.modeRedact");
        shortcutActions.put("icarus.modeRedact", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { redactBtn.doClick(); }
        });

        // Spacebar hold-to-pan: switch to Pan on press, restore the previous tool on
        // release. The mode[0] guard makes repeated KEY_PRESSED events from OS key-repeat
        // (fired continuously while held) a no-op after the first one.
        String[] prevMode = { null };

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "icarus.panDown");
        shortcutActions.put("icarus.panDown", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (!"PAN".equals(mode[0])) {
                    prevMode[0] = mode[0];
                    panBtn.doClick();
                }
            }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "icarus.panUp");
        shortcutActions.put("icarus.panUp", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (prevMode[0] != null) {
                    if ("BOX".equals(prevMode[0])) boxBtn.doClick();
                    else if ("ARROW".equals(prevMode[0])) arrowBtn.doClick();
                    else if ("HIGHLIGHT".equals(prevMode[0])) hiBtn.doClick();
                    else if ("REDACT".equals(prevMode[0])) redactBtn.doClick();
                    prevMode[0] = null;
                }
            }
        });

        // Default cursor for pan mode
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        parentEditor.setVisible(false); // Hide the Phase 1 dialog, keep alive for Back button
        frame.setContentPane(root);
        frame.setVisible(true);
    }

public BufferedImage renderFinalImage(BufferedImage snap, List<Shape> shapes, List<String> kinds, List<Color> cols) {
        BufferedImage out = new BufferedImage(snap.getWidth(), snap.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(snap, 0, 0, null);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(3f));
        for (int i = 0; i < shapes.size(); i++) {
            String kind = kinds.get(i);
            Shape s = shapes.get(i);
            if ("REDACT".equals(kind)) {
                g2.setColor(Color.BLACK);
                g2.fill(s);
            } else if ("ARROW".equals(kind)) {
                g2.setColor(cols.get(i));
                g2.fill(s);
                g2.draw(s);
            } else {
                g2.setColor(cols.get(i));
                g2.draw(s);
            }
        }
        g2.dispose();
        return out;
    }

    private static Icon createIcon(String type) {
        try {
            FlatSVGIcon baseIcon = EvidenceUiHelpers.loadSvgIcon(type);
            if (baseIcon != null) {
                baseIcon = baseIcon.derive(16, 16);
                final FlatSVGIcon iconRef = baseIcon;
                return new Icon() {
                    private java.awt.image.BufferedImage bufferWhite = null;
                    private java.awt.image.BufferedImage bufferGray = null;

                    private java.awt.image.BufferedImage createTintedBuffer(Color tint) {
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2d = img.createGraphics();
                        iconRef.paintIcon(null, g2d, 0, 0);
                        g2d.setComposite(AlphaComposite.SrcIn);
                        g2d.setColor(tint);
                        g2d.fillRect(0, 0, 16, 16);
                        g2d.dispose();
                        return img;
                    }

                    @Override
                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        Color iconColor = Color.WHITE;
                        if (c instanceof javax.swing.AbstractButton && !((javax.swing.AbstractButton)c).getModel().isEnabled()) {
                            iconColor = Color.GRAY;
                        }
                        if (iconColor == Color.WHITE) {
                            if (bufferWhite == null) bufferWhite = createTintedBuffer(Color.WHITE);
                            g.drawImage(bufferWhite, x, y, null);
                        } else {
                            if (bufferGray == null) bufferGray = createTintedBuffer(Color.GRAY);
                            g.drawImage(bufferGray, x, y, null);
                        }
                    }
                    @Override public int getIconWidth() { return 16; }
                    @Override public int getIconHeight() { return 16; }
                };
            }
        } catch (Exception ex) {
            // fallback
        }
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }
}
