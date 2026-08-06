package icarus.evidence;

import icarus.core.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bundled, offline CWE reference (id/name/shortDescription/top25) loaded once from
 * classpath. No HTTP client, no update logic — dataset is a static snapshot, refreshed
 * manually before a release.
 */
public final class CweRepository {

    public record Cwe(String id, String name, String shortDescription, boolean top25) {
        public String label() { return id + " – " + name; }
    }

    private final List<Cwe> entries;

    public CweRepository() {
        this.entries = load();
    }

    @SuppressWarnings("unchecked")
    private static List<Cwe> load() {
        var result = new ArrayList<Cwe>();
        try (InputStream in = CweRepository.class.getResourceAsStream("/cwe_dataset.json")) {
            if (in == null) return result;
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (Object o : (List<Object>) JsonParser.parse(json)) {
                var m = (Map<String, Object>) o;
                result.add(new Cwe(
                        (String) m.get("id"),
                        (String) m.get("name"),
                        (String) m.get("shortDescription"),
                        Boolean.TRUE.equals(m.get("top25"))
                ));
            }
        } catch (IOException e) {
            // Bundled dataset missing/unreadable — CWE features degrade to empty results.
        }
        return result;
    }

    /** Case-insensitive prefix match on id or name; top 10 results. */
    public List<Cwe> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.strip().toLowerCase();
        return entries.stream()
                .filter(c -> c.id().toLowerCase().contains(q) || c.name().toLowerCase().contains(q))
                .limit(10)
                .toList();
    }

    public List<Cwe> top25() {
        return entries.stream().filter(Cwe::top25).toList();
    }

    public Cwe byId(String id) {
        return entries.stream().filter(c -> c.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }
}
