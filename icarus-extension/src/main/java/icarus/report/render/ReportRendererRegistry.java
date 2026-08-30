package icarus.report.render;

import icarus.report.model.CoverRendererId;
import icarus.report.model.FindingRendererId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available Cover, Finding, and Section renderers.
 */
public class ReportRendererRegistry {

    private final Map<CoverRendererId, CoverRenderer> coverRenderers = new ConcurrentHashMap<>();
    private final Map<FindingRendererId, FindingRenderer> findingRenderers = new ConcurrentHashMap<>();
    private final Map<String, SectionRenderer> sectionRenderers = new ConcurrentHashMap<>();

    public ReportRendererRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        registerCover(new GradientHeroCoverRenderer());
        registerCover(new HeaderBandCoverRenderer());
        registerCover(new NoneCoverRenderer());

        registerFinding(new ElevatedCardFindingRenderer());
        registerFinding(new TabularFindingRenderer());
    }

    public void registerCover(CoverRenderer renderer) {
        if (renderer != null) coverRenderers.put(renderer.id(), renderer);
    }

    public void registerFinding(FindingRenderer renderer) {
        if (renderer != null) findingRenderers.put(renderer.id(), renderer);
    }

    public void registerSection(SectionRenderer renderer) {
        if (renderer != null && renderer.id() != null) {
            sectionRenderers.put(renderer.id().toUpperCase(), renderer);
        }
    }

    public CoverRenderer getCover(CoverRendererId id) {
        if (id == null) return coverRenderers.get(CoverRendererId.GRADIENT_HERO);
        return coverRenderers.getOrDefault(id, coverRenderers.get(CoverRendererId.GRADIENT_HERO));
    }

    public FindingRenderer getFinding(FindingRendererId id) {
        if (id == null) return findingRenderers.get(FindingRendererId.ELEVATED_CARD);
        return findingRenderers.getOrDefault(id, findingRenderers.get(FindingRendererId.ELEVATED_CARD));
    }

    public SectionRenderer getSection(String id) {
        if (id == null) return null;
        return sectionRenderers.get(id.toUpperCase());
    }
}
