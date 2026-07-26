package icarus.screenshot;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Port of AutoScreenshot.java.
 * Provides a manual overlay to capture screen regions and a drawing editor.
 */
public final class ScreenshotEditor {

    private static final int BORDER = 5;
    private static final int GRIP = 6;
    private static final int BTN_W = 90;
    private static final int BTN_H = 30;

    public void startCapture() {
        SwingUtilities.invokeLater(this::showOverlay);
    }

    private void showOverlay() {
        JWindow overlay = new JWindow();
        overlay.setBounds(200, 200, 600, 350);
        overlay.setAlwaysOnTop(true);
        overlay.setBackground(new Color(0, 0, 0, 0));
        ((JComponent) overlay.getContentPane()).setOpaque(false);
        overlay.getRootPane().setOpaque(false);
        overlay.getContentPane().setLayout(null);

        Runnable updateShape = () -> {
            int w = overlay.getWidth(), h = overlay.getHeight();
            Area a = new Area(new Rectangle(0, 0, w, h));
            a.subtract(new Area(new Rectangle(BORDER, BORDER, w - BORDER * 2, h - BORDER * 2)));
            a.add(new Area(new Rectangle(BORDER + 2, BORDER + 2, BTN_W + 4, BTN_H + 4)));
            overlay.setShape(a);
        };

        overlay.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateShape.run();
            }
        });

        overlay.setContentPane(new JComponent() {
            { setOpaque(false); setLayout(null); }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(BORDER));
                g2.drawRect(BORDER / 2, BORDER / 2, getWidth() - BORDER, getHeight() - BORDER);
                g2.dispose();
            }
        });

        JButton capture = new JButton("Capture ↵");
        capture.setBounds(BORDER + 4, BORDER + 4, BTN_W, BTN_H);
        capture.setFont(capture.getFont().deriveFont(Font.BOLD));
        overlay.getContentPane().add(capture);

        MouseAdapter dragBtn = new MouseAdapter() {
            Point off;
            @Override
            public void mousePressed(MouseEvent e) { off = e.getPoint(); }
            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(capture, e.getPoint(), overlay.getContentPane());
                capture.setLocation(p.x - off.x, p.y - off.y);
            }
        };
        capture.addMouseListener(dragBtn);
        capture.addMouseMotionListener(dragBtn);

        MouseAdapter mover = new MouseAdapter() {
            Point start; Rectangle startB; int edge;

            int edges(Point p) {
                int m = 0;
                if (p.x >= overlay.getWidth() - GRIP) m |= 1;
                if (p.y >= overlay.getHeight() - GRIP) m |= 2;
                if (p.x <= GRIP) m |= 4;
                if (p.y <= GRIP) m |= 8;
                return m;
            }
            @Override
            public void mousePressed(MouseEvent e) {
                start = e.getLocationOnScreen();
                startB = overlay.getBounds();
                edge = edges(e.getPoint());
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                Point now = e.getLocationOnScreen();
                int dx = now.x - start.x, dy = now.y - start.y;
                Rectangle r = new Rectangle(startB);
                if (edge == 0) {
                    r.x += dx; r.y += dy;
                } else {
                    if ((edge & 1) != 0) r.width += dx;
                    if ((edge & 2) != 0) r.height += dy;
                    if ((edge & 4) != 0) { r.x += dx; r.width -= dx; }
                    if ((edge & 8) != 0) { r.y += dy; r.height -= dy; }
                    r.width = Math.max(120, r.width);
                    r.height = Math.max(90, r.height);
                }
                overlay.setBounds(r);
            }
        };
        overlay.addMouseListener(mover);
        overlay.addMouseMotionListener(mover);

        KeyEventDispatcher dispatch = new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() != KeyEvent.KEY_PRESSED || !overlay.isVisible()) return false;
                if (e.getKeyCode() == KeyEvent.VK_ENTER) { capture.doClick(); return true; }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { overlay.dispose(); return true; }
                return false;
            }
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatch);

        capture.addActionListener(ev -> takeScreenshotAndEdit(overlay));

        updateShape.run();
        overlay.setVisible(true);
    }

    private void takeScreenshotAndEdit(JWindow overlay) {
        try {
            Rectangle reg = overlay.getBounds();
            overlay.setVisible(false);
            Toolkit.getDefaultToolkit().sync();
            Thread.sleep(250);
            BufferedImage snap = new Robot(overlay.getGraphicsConfiguration().getDevice())
                    .createScreenCapture(reg);
            overlay.dispose();

            showEditor(snap);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void showEditor(BufferedImage snap) {
        JFrame editor = new JFrame("Screenshot Editor");
        editor.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        editor.getRootPane().setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));

        JPanel root = new JPanel(new BorderLayout());
        editor.setContentPane(root);

        JLabel img = new JLabel(new ImageIcon(snap));
        JLayeredPane stack = new JLayeredPane();
        img.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(img, Integer.valueOf(0));

        int minW = Math.max(500, snap.getWidth() + 24);
        int minH = Math.max(350, snap.getHeight() + 24);
        JPanel canvas = new JPanel(null);
        canvas.setBackground(Color.DARK_GRAY);
        canvas.setBorder(BorderFactory.createMatteBorder(12, 12, 12, 12, Color.DARK_GRAY));
        canvas.setPreferredSize(new Dimension(minW, minH));
        stack.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        canvas.add(stack);

        canvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int cw = canvas.getWidth() - 24;
                int ch = canvas.getHeight() - 24;
                int sx = (cw - snap.getWidth()) / 2;
                int sy = (ch - snap.getHeight()) / 2;
                stack.setBounds(12 + Math.max(0, sx), 12 + Math.max(0, sy), snap.getWidth(), snap.getHeight());
            }
        });

        root.add(canvas, BorderLayout.CENTER);

        List<Shape> shapes = new ArrayList<>();
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Color[] curCol = { Color.RED };
        String[] mode = { "RECT" };
        Point[] start = { null };
        Shape[] preview = { null };

        JComponent draw = new JComponent() {
            { setOpaque(false); }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    g2.setColor(cols.get(i));
                    if ("HIGHLIGHT".equals(kinds.get(i)) || "REDACT".equals(kinds.get(i))) g2.fill(shapes.get(i));
                    else g2.draw(shapes.get(i));
                }
                if (preview[0] != null) {
                    g2.setColor("REDACT".equals(mode[0]) ? Color.BLACK : curCol[0]);
                    if ("HIGHLIGHT".equals(mode[0]) || "REDACT".equals(mode[0])) g2.fill(preview[0]);
                    else g2.draw(preview[0]);
                }
                g2.dispose();
            }
        };
        draw.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(draw, Integer.valueOf(100));

        MouseAdapter dm = new MouseAdapter() {
            Path2D path;
            @Override
            public void mousePressed(MouseEvent e) {
                start[0] = e.getPoint();
                if ("DRAW".equals(mode[0])) {
                    path = new Path2D.Double();
                    path.moveTo(e.getX(), e.getY());
                    preview[0] = path;
                }
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                if (start[0] == null) return;
                Point p = e.getPoint();
                switch (mode[0]) {
                    case "LINE" -> preview[0] = new Line2D.Double(start[0], p);
                    case "DRAW" -> path.lineTo(p.getX(), p.getY());
                    default -> {
                        int x = Math.min(start[0].x, p.x);
                        int y = Math.min(start[0].y, p.y);
                        int w = Math.abs(p.x - start[0].x);
                        int h = Math.abs(p.y - start[0].y);
                        preview[0] = new Rectangle2D.Double(x, y, w, h);
                    }
                }
                draw.repaint();
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (preview[0] != null) {
                    Color c = switch (mode[0]) {
                        case "HIGHLIGHT" -> new Color(curCol[0].getRed(), curCol[0].getGreen(), curCol[0].getBlue(), 80);
                        case "REDACT" -> Color.BLACK;
                        default -> curCol[0];
                    };
                    shapes.add(preview[0]);
                    cols.add(c);
                    kinds.add(mode[0]);
                }
                start[0] = null;
                preview[0] = null;
                draw.repaint();
            }
        };
        draw.addMouseListener(dm);
        draw.addMouseMotionListener(dm);

        // Toolbar
        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bar.setPreferredSize(new Dimension(180, snap.getHeight()));
        root.add(bar, BorderLayout.EAST);

        JButton colourBtn = new JButton("Colour"); colourBtn.setOpaque(true);
        JToggleButton redactBtn = new JToggleButton("Hide");
        JToggleButton lineBtn = new JToggleButton("Line");
        JToggleButton drawBtn = new JToggleButton("Draw");
        JToggleButton rectBtn = new JToggleButton("Rectangle", true);
        JToggleButton hiBtn = new JToggleButton("Highlight");
        JButton undoBtn = new JButton("← Undo");
        JButton copyBtn = new JButton("Copy");
        JButton saveBtn = new JButton("Save");

        ButtonGroup grp = new ButtonGroup();
        grp.add(rectBtn); grp.add(lineBtn); grp.add(drawBtn);
        grp.add(hiBtn); grp.add(redactBtn);

        Arrays.asList(colourBtn, redactBtn, lineBtn, drawBtn, rectBtn, hiBtn, undoBtn, copyBtn, saveBtn)
                .forEach(b -> b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize() + 2f)));

        BiFunction<Color, String, Icon> makeIcon = (c, t) -> {
            BufferedImage bi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            switch (t) {
                case "RECT" -> { g.setColor(c); g.setStroke(new BasicStroke(2f)); g.drawRect(2, 2, 11, 11); }
                case "LINE" -> { g.setColor(c); g.setStroke(new BasicStroke(3f)); g.drawLine(2, 13, 13, 2); }
                case "DRAW" -> { g.setColor(c); g.setStroke(new BasicStroke(2f)); g.drawPolyline(new int[]{2,5,9,13}, new int[]{12,7,10,3}, 4); }
                case "HIGHLIGHT" -> { g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120)); g.fillRect(2, 4, 12, 8); g.setColor(Color.DARK_GRAY); g.drawRect(2, 4, 12, 8); }
                case "REDACT" -> { g.setColor(Color.BLACK); g.fillRect(2, 2, 12, 12); }
            }
            g.dispose();
            return new ImageIcon(bi);
        };

        Runnable refreshIcons = () -> {
            rectBtn.setIcon(makeIcon.apply(curCol[0], "RECT"));
            lineBtn.setIcon(makeIcon.apply(curCol[0], "LINE"));
            drawBtn.setIcon(makeIcon.apply(curCol[0], "DRAW"));
            hiBtn.setIcon(makeIcon.apply(curCol[0], "HIGHLIGHT"));
            redactBtn.setIcon(makeIcon.apply(curCol[0], "REDACT"));
            colourBtn.setBackground(curCol[0]);
        };
        refreshIcons.run();

        ActionListener modeSel = a -> {
            if (a.getSource() == rectBtn) mode[0] = "RECT";
            else if (a.getSource() == lineBtn) mode[0] = "LINE";
            else if (a.getSource() == drawBtn) mode[0] = "DRAW";
            else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
            else if (a.getSource() == redactBtn) mode[0] = "REDACT";
            draw.setCursor(Cursor.getPredefinedCursor("LINE".equals(mode[0]) ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        };
        rectBtn.addActionListener(modeSel);
        lineBtn.addActionListener(modeSel);
        drawBtn.addActionListener(modeSel);
        hiBtn.addActionListener(modeSel);
        redactBtn.addActionListener(modeSel);

        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(editor, "Choose colour", curCol[0]);
            if (chosen != null) { curCol[0] = chosen; refreshIcons.run(); }
        });

        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                draw.repaint();
            }
        });

        ActionListener saveCopy = a -> {
            try {
                BufferedImage out = new BufferedImage(snap.getWidth(), snap.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = out.createGraphics();
                g2.drawImage(snap, 0, 0, null);
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    g2.setColor(cols.get(i));
                    if ("HIGHLIGHT".equals(kinds.get(i)) || "REDACT".equals(kinds.get(i))) g2.fill(shapes.get(i));
                    else g2.draw(shapes.get(i));
                }
                g2.dispose();

                if (a.getSource() == copyBtn) {
                    Transferable t = new Transferable() {
                        @Override
                        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                            if (f.equals(DataFlavor.imageFlavor)) return out;
                            throw new UnsupportedFlavorException(f);
                        }
                        @Override
                        public DataFlavor[] getTransferDataFlavors() {
                            return new DataFlavor[]{DataFlavor.imageFlavor};
                        }
                        @Override
                        public boolean isDataFlavorSupported(DataFlavor f) {
                            return f.equals(DataFlavor.imageFlavor);
                        }
                    };
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
                    JOptionPane.showMessageDialog(editor, "Image copied to clipboard.");
                } else {
                    JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
                    fc.setSelectedFile(new File("icarus-screenshot.png"));
                    if (fc.showSaveDialog(editor) == JFileChooser.APPROVE_OPTION) {
                        File f = fc.getSelectedFile();
                        javax.imageio.ImageIO.write(out, "png", f);
                    }
                    editor.dispose();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        };
        copyBtn.addActionListener(saveCopy);
        saveBtn.addActionListener(saveCopy);

        bar.add(colourBtn);
        bar.add(redactBtn);
        bar.add(lineBtn);
        bar.add(drawBtn);
        bar.add(rectBtn);
        bar.add(hiBtn);
        bar.add(undoBtn);
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setPreferredSize(new Dimension(100, 2));
        bar.add(sep);
        bar.add(copyBtn);
        bar.add(saveBtn);

        editor.pack();
        editor.setMinimumSize(new Dimension(minW + 180, minH));
        editor.setLocationRelativeTo(null);
        draw.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        editor.setVisible(true);
        canvas.dispatchEvent(new ComponentEvent(canvas, ComponentEvent.COMPONENT_RESIZED));
    }
}
