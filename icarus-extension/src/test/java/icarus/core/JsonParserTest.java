package icarus.core;

import java.util.List;
import java.util.Map;

/** Records serialize as JSON objects (MCP get_project_context relied on toString() before). */
public class JsonParserTest {
    record Ctx(String code, List<String> hosts, ProjectContextDetector.Confidence confidence, Map<String, String> vars) {}

    public static void main(String[] args) {
        String json = JsonParser.write(new Ctx("PROJ-1", List.of("a.test"), ProjectContextDetector.Confidence.LOW, Map.of("k", "v")));
        check(json.equals("{\"code\":\"PROJ-1\",\"hosts\":[\"a.test\"],\"confidence\":\"LOW\",\"vars\":{\"k\":\"v\"}}"), json);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) JsonParser.parse(JsonParser.prettyPrint(new Ctx(null, List.of(), null, Map.of()), 0));
        check(back.containsKey("code") && back.get("code") == null, String.valueOf(back));
        check(JsonParser.write(new RawNumber("12")).equals("12"), "RawNumber must stay a bare number");
        System.out.println("JsonParserTest: all checks passed.");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
