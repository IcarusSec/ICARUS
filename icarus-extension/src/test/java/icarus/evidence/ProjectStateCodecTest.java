package icarus.evidence;

import icarus.core.KnowledgeBaseEntry;
import icarus.core.ReportTemplateConfig;
import icarus.core.ModuleConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Format-2 .icarus round trip: workspace keys, Knowledge Base, embedded logo files, format-1 compatibility. */
public class ProjectStateCodecTest {
    public static void main(String[] args) throws Exception {
        Path logo = Files.createTempFile("icarus-logo", ".png");
        byte[] logoBytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Files.write(logo, logoBytes);

        String profiles = "[{\"id\":\"user:1\",\"name\":\"Client \\\"A\\\"\",\"branding\":{\"companyLogoPath\":\"" + logo.toString().replace("\\", "\\\\")
                + "\",\"clientLogoPath\":\"/no/such/file.png\"},\"sections\":[{\"rendererKey\":\"CANVAS_PAGE\",\"x\":12.5}]}]";
        String registry = "{\"auditLog\":[\"one\"],\"activeFindings\":[]}";
        var kb = List.of(new KnowledgeBaseEntry("Custom vuln", "HIGH", "desc, with comma", "imp", "rec", "Alto", "Media", "CWE-79", false),
                new KnowledgeBaseEntry("Removed", "", "", "", "", "", "", "", true));
        var ws = new ProjectStateCodec.Workspace(registry, List.of("h1", "h2"), profiles, "user:1", kb);

        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(new ModuleConfig());
        rtc.setLogoPath(logo.toString());
        String json = ProjectStateCodec.export(List.of(), ce -> true, rtc, ws);
        check(!json.contains(logo.toString()), "logo path must be replaced by an asset id");

        var result = ProjectStateCodec.importFrom(json);
        var back = result.workspace();
        check(back.registryJson().contains("\"one\""), back.registryJson());
        check(back.suppressedHashes().equals(List.of("h1", "h2")), String.valueOf(back.suppressedHashes()));
        check("user:1".equals(back.activeProfileId()), back.activeProfileId());
        check(back.knowledgeBase().equals(kb), String.valueOf(back.knowledgeBase()));
        check(result.assets().size() == 1, "one readable logo, got " + result.assets().keySet());
        String id = result.assets().keySet().iterator().next();
        check(java.util.Arrays.equals(result.assets().get(id), logoBytes), "asset bytes changed");

        // Both logo references (profile + template) share one asset; the missing file keeps its path.
        Map<String, String> local = Map.of(id, "/new/home/assets/" + id);
        String resolved = ProjectStateCodec.resolveAssets(back.reportProfilesJson(), local);
        check(resolved.contains("\"/new/home/assets/" + id + "\""), resolved);
        check(resolved.contains("/no/such/file.png") && resolved.contains("12.5") && resolved.contains("Client \\\"A\\\""), resolved);
        check(("/new/home/assets/" + id).equals(ProjectStateCodec.resolveAsset(result.reportTemplateConfig().logoPath(), local)),
                result.reportTemplateConfig().logoPath());

        // Asset ids become file names: anything else is dropped.
        var hostile = ProjectStateCodec.importFrom("{\"formatVersion\":2,\"assets\":{\"../../.bashrc\":\"AA==\",\"0123456789abcdef.png\":\"AA==\"}}");
        check(hostile.assets().keySet().equals(java.util.Set.of("0123456789abcdef.png")), String.valueOf(hostile.assets().keySet()));

        // A format-1 file has no workspace data.
        var v1 = ProjectStateCodec.importFrom("{\"formatVersion\":1.0,\"findings\":[]}");
        check(v1.workspace().registryJson() == null && v1.workspace().reportProfilesJson() == null && v1.assets().isEmpty(), "v1 must import as before");

        Files.deleteIfExists(logo);
        System.out.println("ProjectStateCodecTest: all checks passed.");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
