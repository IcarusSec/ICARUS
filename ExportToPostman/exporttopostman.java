// ============================================================
// Export to Postman
// Burp Suite Custom Action
//
// Description:
// Exports the current Repeater request to a Postman Collection JSON.
//
// Version: 0.1.0
// ============================================================

// ============================================================
// ================= BASIC USAGE =================
// ------------------------------------------------------------
// Paste this script in:
// Repeater > Custom actions > New > Blank
//
// Objective:
//  - Generates a Postman Collection JSON from the request.
//  - Outputs the JSON to the Custom Action log.
// ============================================================

var req = requestResponse.request();

if (req == null) {
    logging.logToOutput("[!] No request available to export.");
    return;
}

java.util.function.Function<String, String> escapeJson = (s) -> {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
};

String method = req.method();
String rawUrl = req.url();
String body = req.bodyToString();

java.net.URL parsedUrl;
try {
    parsedUrl = new java.net.URL(rawUrl);
} catch (Exception e) {
    logging.logToOutput("Failed to parse URL: " + rawUrl);
    return;
}

String host = parsedUrl.getHost();
String path = parsedUrl.getPath();
String query = parsedUrl.getQuery();

java.lang.StringBuilder json = new java.lang.StringBuilder();
json.append("{\n");
json.append("  \"info\": {\n");
json.append("    \"name\": \"Burp Suite Export\",\n");
json.append("    \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"\n");
json.append("  },\n");
json.append("  \"item\": [\n");
json.append("    {\n");
json.append("      \"name\": \"").append(escapeJson.apply(path == null || path.isEmpty() ? "/" : path)).append("\",\n");
json.append("      \"request\": {\n");
json.append("        \"method\": \"").append(method).append("\",\n");

// Headers
json.append("        \"header\": [\n");
var headers = req.headers();
boolean firstHeader = true;
for (var h : headers) {
    String name = h.name();
    String value = h.value();
    
    // Skip pseudo-headers if any
    if (name.trim().isEmpty() || name.startsWith(":")) continue; 
    
    if (!firstHeader) {
        json.append(",\n");
    }
    json.append("          {\n");
    json.append("            \"key\": \"").append(escapeJson.apply(name)).append("\",\n");
    json.append("            \"value\": \"").append(escapeJson.apply(value)).append("\"\n");
    json.append("          }");
    firstHeader = false;
}
json.append("\n        ],\n");

// Body
if (body != null && !body.isEmpty()) {
    json.append("        \"body\": {\n");
    json.append("          \"mode\": \"raw\",\n");
    json.append("          \"raw\": \"").append(escapeJson.apply(body)).append("\",\n");
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
json.append("          \"raw\": \"").append(escapeJson.apply(rawUrl)).append("\",\n");

// Host
json.append("          \"host\": [\n");
if (host != null && !host.isEmpty()) {
    String[] hostParts = host.split("\\.");
    for (int i = 0; i < hostParts.length; i++) {
        json.append("            \"").append(escapeJson.apply(hostParts[i])).append("\"");
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
            json.append("            \"").append(escapeJson.apply(pathParts[i])).append("\"");
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
        json.append("              \"key\": \"").append(escapeJson.apply(qKey)).append("\",\n");
        json.append("              \"value\": \"").append(escapeJson.apply(qVal)).append("\"\n");
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

logging.logToOutput("========== Postman Collection ==========\n");
logging.logToOutput(json.toString());
logging.logToOutput("\n========================================");
