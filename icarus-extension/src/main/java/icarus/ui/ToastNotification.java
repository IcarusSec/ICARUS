package icarus.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Lightweight, non-modal notification that briefly appears bottom-right of the suite frame
 * without interrupting the user's workflow. Minimum viable: a borderless JWindow, a Swing
 * Timer to dismiss it, no animation.
 */
public final class ToastNotification {

    private static final int DISMISS_MS = 3000;
    private static final Color BG_TRANSLUCENT = new Color(20, 20, 20, 230);
    private static final Color BG_OPAQUE = new Color(20, 20, 20);

    // A second toast reuses/replaces the current one instead of stacking a new window on
    // top — matters once the background passive scanner can fire several of these in a row.
    private static JWindow current;
    private static Timer dismissTimer;

    private ToastNotification() {}

    public static void show(Frame parent, String message) {
        SwingUtilities.invokeLater(() -> showOnEdt(parent, message));
    }

    private static void showOnEdt(Frame parent, String message) {
        if (dismissTimer != null) dismissTimer.stop();
        if (current != null) current.dispose();

        JWindow toast = new JWindow();
        // A toast must never steal focus from whatever the user is doing (e.g. mid-type in
        // Repeater) — JWindow requests focus on setVisible(true) unless told not to.
        toast.setFocusableWindowState(false);

        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        boolean translucent = isTranslucencySupported();
        if (translucent) {
            toast.setBackground(new Color(0, 0, 0, 0));
            panel.setBackground(BG_TRANSLUCENT);
        } else {
            panel.setBackground(BG_OPAQUE);
        }
        panel.setOpaque(true);

        JLabel label = new JLabel(message);
        label.setForeground(Color.WHITE);
        panel.add(label);

        toast.setContentPane(panel);
        toast.pack();

        Rectangle screen = referenceBounds(parent);
        toast.setLocation(screen.x + screen.width - toast.getWidth() - 24,
                screen.y + screen.height - toast.getHeight() - 24);
        toast.setVisible(true);
        current = toast;

        dismissTimer = new Timer(DISMISS_MS, e -> {
            toast.dispose();
            if (current == toast) current = null;
        });
        dismissTimer.setRepeats(false);
        dismissTimer.start();
    }

    private static boolean isTranslucencySupported() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Exception e) {
            return false;
        }
    }

    private static Rectangle referenceBounds(Frame parent) {
        if (parent != null && parent.isShowing()) {
            return parent.getBounds();
        }
        return new Rectangle(new Point(0, 0), Toolkit.getDefaultToolkit().getScreenSize());
    }
}
