package icarus.core;

import burp.api.montoya.MontoyaApi;

import java.nio.file.Path;
import java.util.List;

/** Resolves where evidence screenshots/reports should default to when the user hasn't explicitly picked a folder yet. */
public final class EvidencePaths {

    private EvidencePaths() {}

    /**
     * Returns the persisted {@code evidence.output_dir} if the user has already picked one
     * (via a save dialog, which pins it going forward); otherwise defaults to a subfolder next
     * to the open Burp project file, falling back to {@code ~/icarus-reports} when the project
     * file's location can't be determined.
     */
    public static String defaultOutputDir(MontoyaApi api, ModuleConfig config) {
        String explicit = config.getString("evidence.output_dir", "");
        if (!explicit.isBlank()) return explicit;

        Path projectDir = resolveProjectDirectory(api);
        if (projectDir != null) {
            return projectDir.resolve("icarus-evidence").toString();
        }
        return System.getProperty("user.home") + "/icarus-reports";
    }

    /**
     * Where screenshot PNGs should actually be written — always a dedicated "icarus-evidence"
     * subfolder, never {@link #defaultOutputDir} bare. {@code evidence.output_dir} is shared
     * with the report/project-state save dialogs, which repoint it at wherever the user last
     * saved a report (e.g. their Desktop) via {@code ReportExportService}; writing screenshots
     * straight into that folder would dump evidence PNGs alongside/into arbitrary user folders.
     * Idempotent: the no-explicit-dir default already ends in "icarus-evidence", so this
     * doesn't double-nest it.
     */
    public static Path evidenceImageDir(MontoyaApi api, ModuleConfig config) {
        Path base = Path.of(defaultOutputDir(api, config));
        Path fileName = base.getFileName();
        if (fileName != null && fileName.toString().equals("icarus-evidence")) {
            return base;
        }
        return base.resolve("icarus-evidence");
    }

    /**
     * Best-effort only: Montoya's {@code Project} API exposes just a name, not a filesystem
     * path, so this parses Burp's own launch command line (a real, documented Montoya API —
     * {@code BurpSuite#commandLineArguments()} — not internal reflection) for a
     * {@code --project-file=<path>} argument. Only present when Burp was started from the CLI
     * with an explicit project file; the common "open via the GUI project chooser" path and
     * temporary/in-memory projects have no such argument at all. Returns {@code null} whenever
     * it can't be determined so callers always have a safe fallback.
     */
    private static Path resolveProjectDirectory(MontoyaApi api) {
        try {
            List<String> args = api.burpSuite().commandLineArguments();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                String value = null;
                if (arg.startsWith("--project-file=")) {
                    value = arg.substring("--project-file=".length());
                } else if (arg.equals("--project-file") && i + 1 < args.size()) {
                    value = args.get(i + 1);
                }
                if (value != null && !value.isBlank()) {
                    Path parent = Path.of(value).toAbsolutePath().getParent();
                    if (parent != null) return parent;
                }
            }
        } catch (Exception ignored) {
            // commandLineArguments() unavailable/unparseable — caller falls back.
        }
        return null;
    }
}
