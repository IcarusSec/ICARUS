/**
 * Builds a prompt that describes the free-form page schema and checks a pasted answer.
 * No network calls and no API keys. A provider can register later with registerProvider.
 * Part of ICARUS, MIT License.
 */
(function (root, factory) {
  var canvas = (root && root.IcarusCanvas) || (typeof require === "function" ? require("./canvas.js") : null);
  var api = factory(canvas);
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.IcarusAI = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (C) {
  var providers = [];

  function registerProvider(provider) {
    if (!provider || typeof provider.complete !== "function" || !provider.id) throw new Error("provider");
    providers = providers.filter(function (item) { return item.id !== provider.id; });
    providers.push({ id: String(provider.id), label: String(provider.label || provider.id), complete: provider.complete });
  }

  function exampleCover() {
    var profile = C.profileFromDoc(C.starterDoc("swiss"));
    profile.name = "Swiss poster example";
    profile.sections.nodes = profile.sections.nodes.slice(0, 1);
    return profile;
  }

  function buildPrompt(profile) {
    var current = typeof profile === "string" ? profile : JSON.stringify(profile, null, 2);
    var example = JSON.stringify(exampleCover(), null, 2);
    return [
      "You design ICARUS report covers. Return one JSON object and nothing else. No markdown fences, no commentary.",
      "",
      "The file is a ReportProfile with schemaVersion \"1\". Burp imports it from Reporting → Import.",
      "Put every designed page in sections.nodes. Do not invent a second format.",
      "",
      "Each page node:",
      "- id: \"page:\" plus a short name",
      "- enabled: true",
      "- order: 1, 2, 3... with the cover first",
      "- required: true if the team must not delete that page",
      "- rendererKey: \"CANVAS_PAGE\"",
      "- params.title: page name",
      "- params.role: \"cover\" or \"interior\"",
      "- params.spec: a STRING of JSON, not an object. The string is {\"background\":\"#FFFFFF\",\"elements\":[...]}",
      "",
      "The page is A4: 210 mm wide, 297 mm tall. Element x and y are millimetres from the top-left. w and h are the width and height in millimetres. rotation is degrees. z paints later numbers on top.",
      "",
      "Element types: text, shape, image, line, chart, table.",
      "",
      "text: {\"type\":\"text\",\"x\":10,\"y\":20,\"w\":120,\"h\":18,\"z\":2,\"text\":\"Hello {{client}}\",\"font\":\"sans\",\"size\":28,\"weight\":700,\"align\":\"left\",\"color\":\"#111111\",\"lineHeight\":1,\"letterSpacing\":0,\"blend\":\"normal\"}",
      "font is sans (Inter), serif (Source Serif), or mono (JetBrains Mono). size is points. weight is 400 or 700.",
      "blend \"difference\" with color \"#FFFFFF\" flips the text: white on a black shape, black on the white page. Use that when a title crosses a color block. Put the text at a higher z than the block.",
      "",
      "shape: {\"type\":\"shape\",\"shape\":\"rect\",\"x\":0,\"y\":0,\"w\":210,\"h\":150,\"z\":1,\"fill\":\"#111111\",\"stroke\":\"\",\"strokeWidth\":0}",
      "shape is rect, ellipse, triangle, polygon, or parallelogram. points are pairs from 0 to 1 inside the box. A downward triangle is [[0,0],[1,0],[0.5,1]]. A parallelogram is [[0.2,0],[1,0],[0.8,1],[0,1]].",
      "",
      "image: {\"type\":\"image\",\"x\":40,\"y\":50,\"w\":22,\"h\":140,\"z\":2,\"src\":\"\",\"slot\":\"\",\"clip\":\"rect\",\"grayscale\":true,\"tint\":\"#A3205A\",\"tintOpacity\":0.6}",
      "clip is rect, ellipse, triangle, or parallelogram. tint is a #RRGGBB wash. slot \"clientLogo\" is filled from the client logo in Burp. Leave src empty for that slot.",
      "",
      "line: {\"type\":\"line\",\"x1\":10,\"y1\":0,\"x2\":105,\"y2\":90,\"stroke\":\"#C4A15A\",\"strokeWidth\":0.35,\"arrow\":false,\"z\":3}",
      "",
      "chart: {\"type\":\"chart\",\"chart\":\"bar\",\"binding\":\"findingsBySeverity\",\"x\":14,\"y\":60,\"w\":180,\"h\":60,\"z\":2}",
      "binding findingsBySeverity is filled from real findings. Do not hard-code those counts.",
      "",
      "table: {\"type\":\"table\",\"mode\":\"findings\",\"x\":14,\"y\":130,\"w\":180,\"h\":100,\"z\":2,\"columns\":[{\"key\":\"title\",\"label\":\"Finding\"},{\"key\":\"severity\",\"label\":\"Severity\"},{\"key\":\"path\",\"label\":\"Path\"}]}",
      "",
      "Variables, written exactly like this and chosen from this list: {{report_title}} {{project}} {{client}} {{tester}} {{date}} {{version}} {{classification}} {{finding_count}} {{count_critical}} {{count_high}} {{count_medium}} {{count_low}} {{count_info}} {{page}} {{pages}}.",
      "coverRenderer must be \"NONE\" when the cover is a canvas page. Keep locale, branding, and the theme objects.",
      "locked:true means the person who uses the template should not move that element.",
      "Colors are #RRGGBB. Unknown element types may stay in the file; they are skipped when the report is drawn.",
      "",
      "Worked example. A black top half and a huge white title that crosses into the white bottom half, so the lower part of the title turns black:",
      example,
      "",
      "Now edit this profile. Keep pages the user did not ask to change.",
      current
    ].join("\n");
  }

  function extractJson(text) {
    var raw = String(text || "").trim();
    if (!raw) return { ok: false, error: "Paste the model's answer first." };
    var fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(raw);
    if (fenced) raw = fenced[1].trim();
    var start = raw.indexOf("{");
    var end = raw.lastIndexOf("}");
    if (start === -1 || end <= start) return { ok: false, error: "No JSON object was found in the answer." };
    try {
      return { ok: true, value: JSON.parse(raw.slice(start, end + 1)) };
    } catch (err) {
      return { ok: false, error: "That text is not valid JSON. " + err.message };
    }
  }

  function parseResponse(text) {
    var extracted = extractJson(text);
    if (!extracted.ok) return { ok: false, errors: [extracted.error], warnings: [], doc: null };
    var checked = C.validateProfile(extracted.value);
    return checked;
  }

  function selfCheck() {
    var prompt = buildPrompt(C.profileFromDoc(C.starterDoc("triangles")));
    if (prompt.indexOf("CANVAS_PAGE") === -1 || prompt.indexOf("difference") === -1) throw new Error("prompt");
    if (prompt.indexOf("Graphic") === -1) throw new Error("example");
    var bad = parseResponse("hello");
    if (bad.ok || bad.errors[0].indexOf("No JSON") === -1) throw new Error("bad paste");
    var good = parseResponse(JSON.stringify(C.profileFromDoc(C.starterDoc("swiss"))));
    if (!good.ok) throw new Error(good.errors.join("; "));
    var broken = parseResponse('{"schemaVersion":"1","sections":{"nodes":[{"id":"page:a","rendererKey":"CANVAS_PAGE","params":{"title":"A","spec":"{nope"}}]}}');
    if (broken.ok || !broken.errors.length) throw new Error("broken");
    return "ai ok";
  }

  return {
    registerProvider: registerProvider,
    listProviders: function () { return providers.map(function (item) { return { id: item.id, label: item.label }; }); },
    buildPrompt: buildPrompt,
    extractJson: extractJson,
    parseResponse: parseResponse,
    selfCheck: selfCheck
  };
});
