package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class PostmanExportModule implements IcarusModule {

    private final MontoyaApi api;

    public PostmanExportModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "Postman Export";
    }

    @Override
    public boolean includeInBulkScan() {
        // Exporting a request isn't a security test — it always "finds" something,
        // which would clutter results if it fired on every "Run All Modules".
        // Stays available via its own individual menu item.
        return false;
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config) {
        if (!config.getBool("export.enabled", true)) {
            return List.of();
        }

        HttpRequest req = requestResponse.request();
        if (req == null) {
            return List.of();
        }

        String method = req.method();
        String rawUrl = req.url();
        String body = req.bodyToString();

        URL parsedUrl;
        try {
            parsedUrl = new URL(rawUrl);
        } catch (Exception e) {
            api.logging().logToError("Failed to parse URL for Postman export: " + rawUrl);
            return List.of();
        }

        String host = parsedUrl.getHost();
        String path = parsedUrl.getPath();
        String query = parsedUrl.getQuery();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"info\": {\n");
        json.append("    \"name\": \"Burp Suite Export\",\n");
        json.append("    \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"\n");
        json.append("  },\n");
        json.append("  \"item\": [\n");
        json.append("    {\n");
        json.append("      \"name\": \"").append(JsonParser.escape(path == null || path.isEmpty() ? "/" : path)).append("\",\n");
        json.append("      \"request\": {\n");
        json.append("        \"method\": \"").append(method).append("\",\n");

        // Headers
        json.append("        \"header\": [\n");
        List<HttpHeader> headers = req.headers();
        boolean firstHeader = true;
        for (HttpHeader h : headers) {
            String name = h.name();
            String value = h.value();

            if (name.trim().isEmpty() || name.startsWith(":")) continue;

            if (!firstHeader) {
                json.append(",\n");
            }
            json.append("          {\n");
            json.append("            \"key\": \"").append(JsonParser.escape(name)).append("\",\n");
            json.append("            \"value\": \"").append(JsonParser.escape(value)).append("\"\n");
            json.append("          }");
            firstHeader = false;
        }
        json.append("\n        ],\n");

        // Body
        if (body != null && !body.isEmpty()) {
            json.append("        \"body\": {\n");
            json.append("          \"mode\": \"raw\",\n");
            json.append("          \"raw\": \"").append(JsonParser.escape(body)).append("\",\n");
            json.append("          \"options\": {\n");
            json.append("            \"raw\": {\n");
            String lang = "text";
            if (body.trim().startsWith("{") || body.trim().startsWith("[")) lang = "json";
            json.append("              \"language\": \"").append(lang).append("\"\n");
            json.append("            }\n");
            json.append("          }\n");
            json.append("        },\n");
        }

        // URL
        json.append("        \"url\": {\n");
        json.append("          \"raw\": \"").append(JsonParser.escape(rawUrl)).append("\",\n");

        // Host
        json.append("          \"host\": [\n");
        if (host != null && !host.isEmpty()) {
            String[] hostParts = host.split("\\.");
            for (int i = 0; i < hostParts.length; i++) {
                json.append("            \"").append(JsonParser.escape(hostParts[i])).append("\"");
                if (i < hostParts.length - 1) json.append(",\n");
            }
        }
        json.append("\n          ],\n");

        // Path
        json.append("          \"path\": [\n");
        if (path != null && !path.isEmpty()) {
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            if (!cleanPath.isEmpty()) {
                String[] pathParts = cleanPath.split("/");
                for (int i = 0; i < pathParts.length; i++) {
                    json.append("            \"").append(JsonParser.escape(pathParts[i])).append("\"");
                    if (i < pathParts.length - 1) json.append(",\n");
                }
            }
        }
        json.append("\n          ]");

        // Query
        if (query != null && !query.isEmpty()) {
            json.append(",\n          \"query\": [\n");
            String[] queryPairs = query.split("&");
            for (int i = 0; i < queryPairs.length; i++) {
                String[] pair = queryPairs[i].split("=", 2);
                String qKey = pair[0];
                String qVal = pair.length > 1 ? pair[1] : "";
                json.append("            {\n");
                json.append("              \"key\": \"").append(JsonParser.escape(qKey)).append("\",\n");
                json.append("              \"value\": \"").append(JsonParser.escape(qVal)).append("\"\n");
                json.append("            }");
                if (i < queryPairs.length - 1) json.append(",\n");
            }
            json.append("\n          ]\n");
        } else {
            json.append("\n");
        }

        json.append("        }\n");
        json.append("      }\n");
        json.append("    }\n");
        json.append("  ]\n");
        json.append("}\n");

        String savedPath = promptSaveToFile(json.toString(), path);

        Finding finding = Finding.builder("Postman Export", "EXPORT")
                .description(savedPath != null
                        ? "Postman Collection exported and saved to " + savedPath
                        : "Postman Collection exported (not saved to disk)")
                .severity(Severity.INFO)
                .category(Category.EXPORT)
                .evidence(requestResponse)
                .meta("postman_json", json.toString())
                .build();

        return List.of(finding);
    }

    /**
     * Prompts the user to confirm/choose where to save the exported collection.
     *
     * @return the absolute path saved to, or null if the user canceled or the save failed.
     */
    private String promptSaveToFile(String json, String requestPath) {
        String[] savedPath = { null };

        Runnable showDialog = () -> {
            JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
            fc.setSelectedFile(new File(suggestedFileName(requestPath)));
            java.awt.Frame parent = api.userInterface().swingUtils().suiteFrame();
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f.exists()) {
                    int overwrite = JOptionPane.showConfirmDialog(parent,
                            f.getName() + " already exists. Overwrite?",
                            "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (overwrite != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                try {
                    Files.writeString(f.toPath(), json);
                    savedPath[0] = f.getAbsolutePath();
                    api.logging().logToOutput("Postman collection saved to: " + f.getAbsolutePath());
                } catch (Exception e) {
                    api.logging().logToError("Failed to save Postman collection: " + e);
                }
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            api.logging().logToError("Failed to show Postman export save dialog: " + e);
        }

        return savedPath[0];
    }

    private String suggestedFileName(String requestPath) {
        String base = (requestPath == null || requestPath.isBlank() || requestPath.equals("/")) ? "root" : requestPath;
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 40) sanitized = sanitized.substring(0, 40);
        return "postman-export-" + sanitized + ".json";
    }
}
