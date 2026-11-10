package icarus.report;

import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.report.model.CoverRendererId;
import icarus.report.model.FindingRendererId;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * Verification test suite for ReportProfile system contracts.
 */
public class ReportProfileSystemTest {

    public static void main(String[] args) throws Exception {
        System.out.println("[TEST] Starting Report Profile System verification...");

        testBuiltInProfilesLoad();
        testJsonRoundTrip();
        testImportValidation();
        testStoredInvalidProfileSkipped();
        testProfileCloning();
        testProfileManagerImmutability();
        testMigrator();
        testPreviewService();

        System.out.println("[TEST] ALL TESTS PASSED SUCCESSFULLY! ✅");
    }

    private static void testBuiltInProfilesLoad() throws Exception {
        System.out.println(" - Testing built-in profiles loading from resources...");
        try (InputStream in1 = ReportProfileSystemTest.class.getResourceAsStream("/reports/profiles/executive-modern.json");
             InputStream in2 = ReportProfileSystemTest.class.getResourceAsStream("/reports/profiles/classic-technical.json")) {
            
            assert in1 != null : "executive-modern.json resource not found!";
            assert in2 != null : "classic-technical.json resource not found!";

            ReportProfile p1 = ReportProfileCodec.fromStream(in1);
            assert "builtin:executive-modern".equals(p1.id()) : "Expected executive-modern id";
            assert p1.builtIn() : "Expected builtIn == true";
            assert p1.coverRenderer() == CoverRendererId.GRADIENT_HERO : "Expected GRADIENT_HERO cover";
            assert p1.findingRenderer() == FindingRendererId.ELEVATED_CARD : "Expected ELEVATED_CARD finding renderer";

            ReportProfile p2 = ReportProfileCodec.fromStream(in2);
            assert "builtin:classic-technical".equals(p2.id()) : "Expected classic-technical id";
            assert p2.builtIn() : "Expected builtIn == true";
            assert p2.coverRenderer() == CoverRendererId.HEADER_BAND : "Expected HEADER_BAND cover";
            assert p2.findingRenderer() == FindingRendererId.TABULAR : "Expected TABULAR finding renderer";
        }
        System.out.println("   [PASSED] Built-in profiles loaded.");
    }

    private static void testJsonRoundTrip() {
        System.out.println(" - Testing JSON serialization round-trip...");
        ReportProfile original = new ReportProfile(
            "1", "test-id", "Test Profile", "en", false, "builtin:executive-modern",
            CoverRendererId.HEADER_BAND, FindingRendererId.TABULAR,
            null, null, null, null, null
        );

        String json = ReportProfileCodec.toJson(original);
        ReportProfile deserialized = ReportProfileCodec.fromJson(json);

        assert "test-id".equals(deserialized.id()) : "ID mismatch";
        assert "Test Profile".equals(deserialized.name()) : "Name mismatch";
        assert "en".equals(deserialized.locale()) : "Locale mismatch";
        assert deserialized.coverRenderer() == CoverRendererId.HEADER_BAND : "Cover mismatch";
        assert deserialized.findingRenderer() == FindingRendererId.TABULAR : "Finding renderer mismatch";
        System.out.println("   [PASSED] JSON round-trip verified.");
    }

    private static void testImportValidation() {
        System.out.println(" - Testing profile import validation...");
        ReportProfile ok = ReportProfileCodec.fromJson("{\"name\":\"No Version\"}");
        assert "No Version".equals(ok.name()) : "profile without schemaVersion must still import";
        assert ReportProfile.CURRENT_SCHEMA_VERSION.equals(ok.schemaVersion()) : "blank version defaults to current";
        assert ReportProfileCodec.fromJson("{\"schemaVersion\":\"1\",\"name\":\"V1\"}").name().equals("V1") : "version 1 must import";

        assertImportRejected("{\"schemaVersion\":\"99\",\"id\":\"user:bad-schema\",\"name\":\"Bad Schema\"}",
            "Unsupported profile version \"99\" (this ICARUS reads version 1)");
        assertImportRejected("{}", "Not a report profile");
        assertImportRejected("[{\"name\":\"x\"}]", "Not a report profile");
        assertImportRejected("\"just a string\"", "Not a report profile");
        assertImportRejected("this is not json", "Failed to parse ReportProfile");

        ModuleConfig cfg = new ModuleConfig();
        DefaultReportProfileManager manager = new DefaultReportProfileManager(cfg);
        int before = manager.list().size();
        try {
            manager.importJson("{\"schemaVersion\":\"99\",\"name\":\"Bad Schema\"}");
            assert false : "importJson must reject unsupported version";
        } catch (IllegalArgumentException expected) {
            // expected: the message is the one the Reporting tab shows
        }
        assert manager.list().size() == before : "rejected import must not create a profile";
        System.out.println("   [PASSED] Profile import validation.");
    }

    private static void testStoredInvalidProfileSkipped() {
        System.out.println(" - Testing stored profiles with an unsupported version are skipped...");
        ModuleConfig cfg = new ModuleConfig();
        cfg.set(DefaultReportProfileManager.KEY_USER_PROFILES,
            "[{\"schemaVersion\":\"1\",\"id\":\"user:good\",\"name\":\"Good\"},"
            + "{\"schemaVersion\":\"99\",\"id\":\"user:bad\",\"name\":\"Bad\"}]");
        DefaultReportProfileManager manager = new DefaultReportProfileManager(cfg);
        assert manager.get("user:good") != null : "valid stored profile must still load";
        assert manager.get("user:bad") == null : "schemaVersion 99 stored profile must be skipped";
        System.out.println("   [PASSED] Invalid stored profile skipped, others loaded.");
    }

    private static void assertImportRejected(String json, String messagePrefix) {
        try {
            ReportProfileCodec.fromJson(json);
            assert false : "expected rejection of: " + json;
        } catch (RuntimeException e) {
            assert e.getMessage() != null && e.getMessage().startsWith(messagePrefix)
                : "message was: " + e.getMessage();
        }
    }

    private static void testProfileCloning() {
        System.out.println(" - Testing profile cloning contract...");
        ModuleConfig cfg = new ModuleConfig();
        ReportProfileManager manager = new DefaultReportProfileManager(cfg);

        ReportProfile cloned = manager.clone("builtin:executive-modern", "My Custom Modern");
        assert cloned != null : "Clone failed";
        assert !cloned.builtIn() : "Cloned profile must not be built-in";
        assert "My Custom Modern".equals(cloned.name()) : "Name mismatch";
        assert "builtin:executive-modern".equals(cloned.basedOnId()) : "basedOnId mismatch";
        assert cloned.id().startsWith("user:") : "Expected user: prefix for cloned ID";

        ReportProfile retrieved = manager.get(cloned.id());
        assert retrieved != null : "Retrieved cloned profile was null";
        assert retrieved.name().equals("My Custom Modern") : "Retrieved profile name mismatch";
        System.out.println("   [PASSED] Profile cloning verified.");
    }

    private static void testProfileManagerImmutability() {
        System.out.println(" - Testing built-in profile immutability protection...");
        ModuleConfig cfg = new ModuleConfig();
        ReportProfileManager manager = new DefaultReportProfileManager(cfg);

        ReportProfile builtIn = manager.get("builtin:executive-modern");
        assert builtIn != null;

        boolean caughtSave = false;
        try {
            manager.saveUserProfile(builtIn);
        } catch (IllegalStateException e) {
            caughtSave = true;
        }
        assert caughtSave : "Expected saveUserProfile on built-in to throw IllegalStateException";

        boolean caughtDelete = false;
        try {
            manager.deleteUserProfile("builtin:executive-modern");
        } catch (IllegalStateException e) {
            caughtDelete = true;
        }
        assert caughtDelete : "Expected deleteUserProfile on built-in to throw IllegalStateException";
        System.out.println("   [PASSED] Immutability checks verified.");
    }

    private static void testMigrator() {
        System.out.println(" - Testing ReportTemplateConfig legacy migration...");
        ModuleConfig cfg = new ModuleConfig();
        ReportTemplateConfig legacy = ReportTemplateConfig.fromConfig(cfg);
        legacy.setPrimaryColor("#123456");
        legacy.setSecondaryColor("#654321");
        legacy.saveTo(cfg);

        ReportProfileManager manager = new DefaultReportProfileManager(cfg);
        ReportProfile base = manager.get("builtin:executive-modern");
        ReportProfile migrated = ReportTemplateConfigMigrator.migrate(legacy, base);

        assert migrated != null : "Migration returned null";
        assert "#123456".equalsIgnoreCase(migrated.pdfTheme().primaryHex()) : "Primary color not migrated";
        assert "#654321".equalsIgnoreCase(migrated.pdfTheme().secondaryHex()) : "Secondary color not migrated";
        assert !migrated.builtIn() : "Migrated profile should be user-editable";
        System.out.println("   [PASSED] Migrator verified.");
    }

    private static void testPreviewService() throws Exception {
        System.out.println(" - Testing PreviewService PDF & HTML rendering...");
        ModuleConfig cfg = new ModuleConfig();
        ReportProfileManager manager = new DefaultReportProfileManager(cfg);
        ReportProfile profile = manager.active();

        File pdfFile = PreviewService.generatePreviewFile(profile, PreviewService.Format.PDF);
        assert pdfFile != null && pdfFile.exists() && pdfFile.length() > 0 : "PDF preview file empty or not created";

        File htmlFile = PreviewService.generatePreviewFile(profile, PreviewService.Format.HTML);
        assert htmlFile != null && htmlFile.exists() && htmlFile.length() > 0 : "HTML preview file empty or not created";

        pdfFile.deleteOnExit();
        htmlFile.deleteOnExit();
        System.out.println("   [PASSED] PreviewService generated valid PDF and HTML.");
    }
}
