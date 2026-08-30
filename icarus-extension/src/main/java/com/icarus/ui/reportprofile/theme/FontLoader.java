package com.icarus.ui.reportprofile.theme;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FontLoader {
    private static final Logger LOG = Logger.getLogger(FontLoader.class.getName());
    private static final Map<String, Font> CACHE = new HashMap<>();
    private static boolean loaded = false;

    /** Call once from the extension's initialize(). Never throws. */
    public static synchronized void loadAll() {
        if (loaded) return;
        loaded = true;
        loadOne("sans-regular", "/fonts/IBMPlexSans-Regular.ttf");
        loadOne("sans-medium",  "/fonts/IBMPlexSans-Medium.ttf");
        loadOne("sans-semibold","/fonts/IBMPlexSans-SemiBold.ttf");
        loadOne("mono-regular", "/fonts/IBMPlexMono-Regular.ttf");
    }

    private static void loadOne(String key, String resourcePath) {
        try (InputStream in = FontLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("resource not found: " + resourcePath);
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            CACHE.put(key, f);
        } catch (FontFormatException | IOException e) {
            LOG.log(Level.WARNING, "Falling back to logical font for " + key, e);
        }
    }

    public static Font sans(int style, float size) {
        Font base = CACHE.getOrDefault("sans-regular", new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        return base.deriveFont(style, size);
    }

    public static Font mono(float size) {
        Font base = CACHE.getOrDefault("mono-regular", new Font(Font.MONOSPACED, Font.PLAIN, 1));
        return base.deriveFont(size);
    }
}
