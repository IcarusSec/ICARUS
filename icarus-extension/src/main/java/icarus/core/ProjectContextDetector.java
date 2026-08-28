package icarus.core;

import burp.api.montoya.MontoyaApi;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic context detection engine for ICARUS.
 * Analyzes active Burp project properties, scope, findings, and environment to:
 * 1. Extract a project identifier from the Burp project name (a generic
 *    PREFIX-1234-style code, or the raw project name as a fallback).
 * 2. Classify target environment (UAT/staging vs. production).
 * 3. Infer system user and suggested report metadata.
 */
public class ProjectContextDetector {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record ProjectContext(
            String projectCode,
            String testType,
            String environment,
            List<String> primaryTargets,
            String systemUser,
            String suggestedAuthor,
            Confidence confidence,
            Map<String, String> suggestedVariables
    ) {}

    // Generic PREFIX-1234 / PREFIX1234 style project-code extraction. Not tied to any
    // organization's specific ticketing convention -- override detectContext's classification
    // here if your organization has its own project-code scheme to recognize.
    private static final Pattern PATTERN_PROJECT_CODE = Pattern.compile("(?i)\\b([A-Z]{2,8}[-_]?\\d{2,8})\\b");

    // Environment classification regexes
    private static final Pattern PATTERN_UAT = Pattern.compile("(?i).*(uat|homol|-h\\.|dev|staging).*");
    private static final Pattern PATTERN_PROD = Pattern.compile("(?i).*(prod).*");

    public static ProjectContext detectContext(MontoyaApi api, List<FindingRecord> findingRecords) {
        String rawProjectIdentifier = extractRawProjectIdentifier(api);
        Set<String> targetHosts = extractTargetHosts(findingRecords);

        String projectCode = null;
        String testType = "Offensive Security Assessment";
        Confidence confidence = Confidence.LOW;

        // 1. Classify project code
        if (rawProjectIdentifier != null && !rawProjectIdentifier.isBlank()) {
            Matcher mCode = PATTERN_PROJECT_CODE.matcher(rawProjectIdentifier);
            if (mCode.find()) {
                projectCode = mCode.group(1).toUpperCase();
                confidence = Confidence.HIGH;
            } else {
                // Fallback: extract any trailing alphanumeric token
                String[] parts = rawProjectIdentifier.split("[/\\\\]");
                String lastPart = parts[parts.length - 1].replaceAll("(?i)\\.burp$", "").trim();
                if (!lastPart.isBlank() && !lastPart.equalsIgnoreCase("Temporary Project")) {
                    projectCode = lastPart;
                    confidence = Confidence.MEDIUM;
                }
            }
        }

        // 2. Classify environment
        String environment = "UAT / Staging"; // Default for a fresh, unclassified engagement
        for (String host : targetHosts) {
            if (PATTERN_UAT.matcher(host).matches()) {
                environment = "UAT / Staging";
                break;
            } else if (PATTERN_PROD.matcher(host).matches() && !host.contains("-h.") && !host.contains("uat")) {
                environment = "Production";
            }
        }

        // 3. System user and author deduction
        String systemUser = System.getProperty("user.name", "analyst");
        String suggestedAuthor = formatAuthorName(systemUser);

        // 4. Report date
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        // 5. Construct suggested variables
        Map<String, String> suggestedVariables = new LinkedHashMap<>();
        suggestedVariables.put("project", projectCode != null ? projectCode : "");
        suggestedVariables.put("environment", environment);
        suggestedVariables.put("date", currentDate);
        suggestedVariables.put("author", suggestedAuthor);
        suggestedVariables.put("owner", suggestedAuthor);
        suggestedVariables.put("requester", "");
        suggestedVariables.put("team", "");
        suggestedVariables.put("method", "Black Box / Greybox");
        suggestedVariables.put("classification", "Confidential");

        String displayProjectName = projectCode != null ? projectCode : "Project";
        suggestedVariables.put("report_title", "Technical Report - " + testType + " - " + displayProjectName);

        List<String> primaryTargetsList = new ArrayList<>(targetHosts);
        Collections.sort(primaryTargetsList);

        return new ProjectContext(
                projectCode,
                testType,
                environment,
                primaryTargetsList,
                systemUser,
                suggestedAuthor,
                confidence,
                suggestedVariables
        );
    }

    private static String extractRawProjectIdentifier(MontoyaApi api) {
        if (api == null) return null;
        try {
            var project = api.project();
            if (project != null && project.name() != null && !project.name().isBlank()) {
                return project.name();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Set<String> extractTargetHosts(List<FindingRecord> records) {
        Set<String> hosts = new LinkedHashSet<>();
        if (records == null) return hosts;
        for (FindingRecord r : records) {
            if (r == null || r.getFinding() == null) continue;
            String path = r.getFinding().path();
            if (path != null && !path.isBlank()) {
                try {
                    if (path.startsWith("http://") || path.startsWith("https://")) {
                        java.net.URI uri = java.net.URI.create(path);
                        if (uri.getHost() != null) hosts.add(uri.getHost());
                    } else if (!path.startsWith("/")) {
                        String host = path.split("/")[0].split(":")[0];
                        if (!host.isBlank()) hosts.add(host);
                    }
                } catch (Exception ignored) {}
            }
        }
        return hosts;
    }

    private static String formatAuthorName(String systemUser) {
        if (systemUser == null || systemUser.isBlank()) return "Security Analyst";
        String[] parts = systemUser.replaceAll("[^a-zA-Z]", " ").split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase());
        }
        return sb.length() > 0 ? sb.toString() : systemUser;
    }
}
