package icarus.report.blocks;

import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import icarus.report.model.SectionNode;
import java.io.InputStream;
import java.util.Map;

/**
 * Custom blocks stay inside schemaVersion 1 section params.
 * Older profiles must still load, and a block type this build does not
 * know must come back as data instead of an exception.
 */
public class CustomBlockParserTest {
    public static void main(String[] args) throws Exception {
        testLegacyProfileStillLoads();
        testKnownChartRoundTrip();
        testUnknownAndBrokenSpecs();
        System.out.println("[TEST] CustomBlockParser passed");
    }

    private static void testLegacyProfileStillLoads() throws Exception {
        try (InputStream in = CustomBlockParserTest.class.getResourceAsStream("/reports/profiles/executive-modern.json")) {
            assert in != null;
            ReportProfile profile = ReportProfileCodec.fromStream(in);
            assert "1".equals(profile.schemaVersion());
            assert profile.sections().nodes().stream().noneMatch(CustomBlockParser::isCustomBlock);
        }
    }

    private static void testKnownChartRoundTrip() {
        String spec = "{\"type\":\"chart\",\"chart\":\"bar\",\"binding\":\"findingsBySeverity\",\"title\":\"Findings by severity\",\"categories\":[{\"key\":\"HIGH\",\"label\":\"High\",\"value\":0,\"color\":\"#D9711F\"}]}";
        SectionNode node = SectionNode.of(
            "block:severity", true, 2, false, "CUSTOM_BLOCK",
            Map.of("title", "Findings by severity", "blockType", "chart", "spec", spec));
        String json = ReportProfileCodec.toJson(profileWith(node));
        ReportProfile loaded = ReportProfileCodec.fromJson(json);
        SectionNode back = loaded.sections().nodes().get(0);
        assert CustomBlockParser.isCustomBlock(back);
        CustomBlock block = CustomBlockParser.parse(back);
        assert "chart".equals(block.type()) : block.type();
        assert !block.invalid();
        assert !block.unsupported();
        assert "findingsBySeverity".equals(block.spec().get("binding").asText());
        assert "1".equals(loaded.schemaVersion());
    }

    private static void testUnknownAndBrokenSpecs() {
        SectionNode unknown = SectionNode.of(
            "block:mystery", true, 1, false, "CUSTOM_BLOCK",
            Map.of("title", "Mystery", "blockType", "hologram", "spec", "{\"type\":\"hologram\"}"));
        CustomBlock mystery = CustomBlockParser.parse(unknown);
        assert "hologram".equals(mystery.type());
        assert mystery.unsupported();
        assert !mystery.invalid();

        SectionNode broken = SectionNode.of(
            "block:broken", true, 1, false, "CUSTOM_BLOCK",
            Map.of("title", "Broken", "blockType", "chart", "spec", "{not json"));
        CustomBlock bad = CustomBlockParser.parse(broken);
        assert bad.invalid();

        ReportProfile loaded = ReportProfileCodec.fromJson(ReportProfileCodec.toJson(profileWith(unknown, broken)));
        assert loaded.sections().nodes().size() == 2;
        assert CustomBlockParser.parse(loaded.sections().nodes().get(0)).unsupported();
    }

    private static ReportProfile profileWith(SectionNode... nodes) {
        ReportProfile base = new ReportProfile(
            "1", "user:blocks", "Blocks", "en", false, null,
            null, null, new icarus.report.model.SectionGraph(java.util.List.of(nodes)),
            null, null, null, null);
        return base;
    }
}
