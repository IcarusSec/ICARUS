package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Headless checks that a large JSON response renders as an evidence image at most
 * {@link EvidenceImageRenderer#MAX_VIEWPORT_HEIGHT} pixels tall, with the truncation
 * marker visible, whether or not Force 1920x1080 is on. A short response is left intact.
 *
 * {@code java -Djava.awt.headless=true -cp build_manual/classes:libs/montoya-api-2026.7.jar icarus.evidence.EvidenceImageHeightTest}
 */
public final class EvidenceImageHeightTest {

    /** Stable phrase drawn by {@link EvidenceImageRenderer#drawColumnLines} when lines are cut. */
    private static final String MARKER_PHRASE = "more lines truncated";

    public static void main(String[] args) throws Exception {
        largeJsonStaysWithinViewport(true);
        largeJsonStaysWithinViewport(false);
        smallResponseIsNotTruncated(true);
        smallResponseIsNotTruncated(false);
        renderingDoesNotRewriteTheStoredBody();
        System.out.println("EvidenceImageHeightTest: all checks passed.");
    }

    private static void largeJsonStaysWithinViewport(boolean force1080) {
        String response = largeJsonResponse(2500);
        int sourceLines = response.split("\n").length;
        check(sourceLines > 2000, "fixture should be thousands of lines, was " + sourceLines);

        BufferedImage img = render(smallRequest(), response, "Large JSON array", force1080);
        check(img.getHeight() <= EvidenceImageRenderer.MAX_VIEWPORT_HEIGHT,
                (force1080 ? "force on" : "force off") + " image height " + img.getHeight()
                        + " exceeds " + EvidenceImageRenderer.MAX_VIEWPORT_HEIGHT);
        check(img.getHeight() == EvidenceImageRenderer.MAX_VIEWPORT_HEIGHT,
                (force1080 ? "force on" : "force off") + " large body should fill the viewport, height "
                        + img.getHeight());
        check(containsMarker(img),
                (force1080 ? "force on" : "force off") + " image is missing the truncation marker");
        check(response.split("\n").length == sourceLines, "render rewrote the response text");
        check(!response.contains("truncated"), "full response text picked up a truncation marker");
    }

    private static void smallResponseIsNotTruncated(boolean force1080) {
        String response = "HTTP/1.1 200 OK\nContent-Type: application/json\n\n{\n  \"ok\": true\n}\n";
        BufferedImage img = render(smallRequest(), response, "Small JSON", force1080);
        check(img.getHeight() <= EvidenceImageRenderer.MAX_VIEWPORT_HEIGHT,
                "small image height " + img.getHeight());
        if (!force1080) {
            check(img.getHeight() < EvidenceImageRenderer.MAX_VIEWPORT_HEIGHT,
                    "short response was padded/clipped to " + img.getHeight() + " with Force 1920x1080 off");
        }
        check(!containsMarker(img),
                (force1080 ? "force on" : "force off") + " small response shows a truncation marker");
        check(!response.contains("truncated"), "small response text was rewritten");
    }

    /**
     * Findings store the raw response bytes and reports export {@code response.toString()}
     * from that message. Rendering may clip the image (and formatBody may clip the
     * phase-1 display text) but must not rewrite those bytes.
     */
    private static void renderingDoesNotRewriteTheStoredBody() {
        String raw = minifiedJsonArray(2500);
        byte[] stored = raw.getBytes(StandardCharsets.UTF_8);
        byte[] before = stored.clone();
        EvidenceImageRenderer renderer = renderer();
        String display = renderer.formatBody(stored, "application/json");
        check(Arrays.equals(stored, before), "formatBody mutated the stored response bytes");
        check(raw.equals(new String(stored, StandardCharsets.UTF_8)), "stored body text changed");
        check(!raw.contains("truncated"), "stored body contains a truncation marker");

        String snapshot = display;
        renderer.renderTextToImage(smallRequest(), "HTTP/1.1 200 OK\n\n" + display,
                "Stored body", "export keeps the raw message", "HIGH", false);
        check(display.equals(snapshot), "render changed the display text it was given");
        check(new String(stored, StandardCharsets.UTF_8).equals(raw), "stored body changed after render");
        check(raw.contains("\"id\":2499"), "stored body dropped the tail of the JSON array");
    }

    private static String minifiedJsonArray(int items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i).append(",\"name\":\"item-").append(i).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static BufferedImage render(String request, String response, String title, boolean force1080) {
        return renderer().renderTextToImage(request, response, title, "evidence", "HIGH", force1080);
    }

    private static EvidenceImageRenderer renderer() {
        MontoyaApi api = (MontoyaApi) stub(MontoyaApi.class);
        return new EvidenceCapture(api, new ModuleConfig()).imageRenderer;
    }

    private static String smallRequest() {
        return "GET /api/items HTTP/1.1\nHost: example.test\nAccept: application/json\n\n";
    }

    private static String largeJsonResponse(int items) {
        return "HTTP/1.1 200 OK\nContent-Type: application/json\n\n[\n" + largeJsonBody(items) + "]\n";
    }

    /** Pretty-printed array body of {@code items} objects, one object per line. Thousands of lines. */
    private static String largeJsonBody(int items) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < items; i++) {
            body.append("  {\"id\": ").append(i).append(", \"name\": \"item-").append(i).append("\"}");
            if (i + 1 < items) body.append(",");
            body.append('\n');
        }
        return body.toString();
    }

    /**
     * Looks for the truncation phrase painted in the same italic face and dim color the
     * renderer uses, on the card background. A real hit sits well below the average
     * per-pixel difference of an empty card (measured ~10 vs ~33).
     */
    private static boolean containsMarker(BufferedImage img) {
        EvidenceColorScheme cs = EvidenceColorScheme.get("Catppuccin");
        Color card = new Color(
                Math.min(255, cs.background().getRed() + 8),
                Math.min(255, cs.background().getGreen() + 10),
                Math.min(255, cs.background().getBlue() + 12));
        BufferedImage needle = renderPhrase(MARKER_PHRASE, new Font(Font.SANS_SERIF, Font.ITALIC, 12), cs.dim(), card);
        int pixels = needle.getWidth() * needle.getHeight();
        int best = Integer.MAX_VALUE;
        int yStart = Math.max(0, img.getHeight() - 160);
        int xStart = img.getWidth() / 2;
        for (int y = yStart; y <= img.getHeight() - needle.getHeight(); y++) {
            for (int x = xStart; x <= img.getWidth() - needle.getWidth(); x += 2) {
                int sad = sad(img, needle, x, y);
                if (sad < best) best = sad;
                if (best / (double) pixels <= 15.0) return true;
            }
        }
        return best / (double) pixels <= 15.0;
    }

    private static BufferedImage renderPhrase(String text, Font font, Color fg, Color bg) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int w = Math.max(1, fm.stringWidth(text));
        int h = Math.max(1, fm.getAscent() + fm.getDescent());
        pg.dispose();
        BufferedImage label = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = label.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        g.setColor(fg);
        g.setFont(font);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();
        return label;
    }

    private static int sad(BufferedImage img, BufferedImage needle, int x, int y) {
        int sad = 0;
        for (int ny = 0; ny < needle.getHeight(); ny++) {
            for (int nx = 0; nx < needle.getWidth(); nx++) {
                int a = img.getRGB(x + nx, y + ny);
                int b = needle.getRGB(nx, ny);
                sad += Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
                sad += Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff));
                sad += Math.abs((a & 0xff) - (b & 0xff));
            }
        }
        return sad;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object stub(Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> iface.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == short.class || rt == byte.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if (rt == String.class) return null;
            if (rt.isInterface()) return stub(rt);
            if (List.class.isAssignableFrom(rt)) return List.of();
            return null;
        });
    }

}
