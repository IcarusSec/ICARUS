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
