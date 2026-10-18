package icarus.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filename slugs for evidence screenshots. Leading and trailing whitespace must not become
 * underscores, and a whitespace-only title must match an empty title.
 *
 * {@code java -cp build_manual/classes icarus.core.EvidencePathsTest}
 */
public final class EvidencePathsTest {

    public static void main(String[] args) throws Exception {
        leadingAndTrailingWhitespaceAreTrimmed();
        whitespaceOnlyMatchesEmptyTitle();
        nullTitleKeepsTheFindingFallback();
        reservingAFileDoesNotChangeTheStoredTitle();
        System.out.println("EvidencePathsTest: all checks passed.");
    }

    private static void leadingAndTrailingWhitespaceAreTrimmed() throws Exception {
        String expected = "Tall_JSON_force_off";
        check(expected.equals(EvidencePaths.slugFindingTitle(" Tall JSON force off")),
                "leading space became " + EvidencePaths.slugFindingTitle(" Tall JSON force off"));
        check(expected.equals(EvidencePaths.slugFindingTitle("Tall JSON force off ")),
                "trailing space became " + EvidencePaths.slugFindingTitle("Tall JSON force off "));
        check(expected.equals(EvidencePaths.slugFindingTitle("  Tall JSON force off  ")),
                "both ends became " + EvidencePaths.slugFindingTitle("  Tall JSON force off  "));

        Path dir = Files.createTempDirectory("icarus-evidence-slug");
        Path leading = EvidencePaths.reserveEvidenceFile(dir, "evidence", " Tall JSON force off");
        Path trailing = EvidencePaths.reserveEvidenceFile(dir, "evidence", "Tall JSON force off ");
        String leadingName = leading.getFileName().toString();
        String trailingName = trailing.getFileName().toString();
        check(leadingName.startsWith("evidence-Tall_JSON_force_off-"),
                "saved name kept a leading underscore: " + leadingName);
        check(!leadingName.contains("_Tall"), "leading space was slugified: " + leadingName);
        check(normalized(leadingName).equals(normalized(trailingName)),
                "trailing-space name " + trailingName + " differed from " + leadingName);
    }

    private static void whitespaceOnlyMatchesEmptyTitle() throws Exception {
        String emptySlug = EvidencePaths.slugFindingTitle("");
        check(emptySlug.equals(EvidencePaths.slugFindingTitle("   ")),
                "spaces slug " + EvidencePaths.slugFindingTitle("   ") + " != empty slug '" + emptySlug + "'");
        check(emptySlug.equals(EvidencePaths.slugFindingTitle(" \t\n ")),
                "mixed whitespace slug " + EvidencePaths.slugFindingTitle(" \t\n "));
        check(emptySlug.isEmpty(), "empty title slug is '" + emptySlug + "', expected empty");

        Path dir = Files.createTempDirectory("icarus-evidence-blank");
        Path blank = EvidencePaths.reserveEvidenceFile(dir, "evidence", "   ");
        Path empty = EvidencePaths.reserveEvidenceFile(dir, "evidence", "");
        check(normalized(blank.getFileName().toString()).equals(normalized(empty.getFileName().toString())),
                "whitespace-only file " + blank.getFileName() + " differed from empty " + empty.getFileName());
        check(blank.getFileName().toString().startsWith("evidence--"),
                "whitespace-only file was " + blank.getFileName());
    }

    private static void nullTitleKeepsTheFindingFallback() {
        check("finding".equals(EvidencePaths.slugFindingTitle(null)),
                "null title slug was " + EvidencePaths.slugFindingTitle(null));
    }

    private static void reservingAFileDoesNotChangeTheStoredTitle() throws Exception {
        String title = " Tall JSON force off";
        Finding finding = Finding.builder("Manual", title).build();
        Path dir = Files.createTempDirectory("icarus-evidence-title");
        EvidencePaths.reserveEvidenceFile(dir, "evidence", finding.type());
        check(title.equals(finding.type()), "stored title changed to '" + finding.type() + "'");
    }

    /** Drops the millisecond stamp and any same-millisecond collision suffix. */
    private static String normalized(String filename) {
        return filename.replaceFirst("-\\d{10,}(?:-\\d+)?\\.png$", "-TS.png");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
