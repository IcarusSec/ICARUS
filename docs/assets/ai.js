/**
 * Client-side AI assist for the ICARUS template builder.
 *
 * This module builds a prompt and checks a pasted answer. It does not call a
 * network API and it does not read API keys. A later provider can register
 * with registerProvider({ id, label, complete }) where complete(prompt)
 * returns a Promise of the model text. Nothing in this file ships a provider.
 *
 * Part of ICARUS, MIT License.
 */
(function (root, factory) {
  var profile = (root && root.IcarusProfile) || (typeof require === "function" ? require("./profile.js") : null);
  var api = factory(profile);
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.IcarusAI = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (P) {
  var providers = [];

  function registerProvider(provider) {
    if (!provider || typeof provider.complete !== "function" || !provider.id) {
      throw new Error("provider");
    }
    providers = providers.filter(function (item) { return item.id !== provider.id; });
    providers.push({ id: String(provider.id), label: String(provider.label || provider.id), complete: provider.complete });
  }

  function listProviders() {
    return providers.map(function (item) { return { id: item.id, label: item.label }; });
  }

  function buildPrompt(profile) {
    var json = typeof profile === "string" ? profile : JSON.stringify(profile, null, 2);
    return [
      "You are editing an ICARUS ReportProfile for a penetration-test report.",
      "Return one JSON object and nothing else. No commentary.",
      "",
      "Schema rules:",
      '- schemaVersion must stay "1".',
      "- Keep id, name, locale, builtIn false, coverRenderer (GRADIENT_HERO, HEADER_BAND, or NONE), and findingRenderer (ELEVATED_CARD or TABULAR).",
      "- sections.nodes is an ordered list. Do not include a node whose id is COVER. Cover is coverRenderer.",
      "- Each node has id, enabled, order, required, rendererKey, and params.",
      "- params values are strings. Markdown sections use params.title and params.content.",
      "- A required node must stay enabled. Do not delete a node with required true.",
      "- Custom blocks are nodes with rendererKey CUSTOM_BLOCK.",
      "- A custom block's params.blockType is chart, table, drawing, image, or callout.",
      "- params.spec is a JSON string, not an object. Unknown block types may stay, but do not invent a second schema.",
      "- chart spec: {\"type\":\"chart\",\"chart\":\"bar|pie|line\",\"binding\":\"findingsBySeverity|static\",\"title\":\"...\",\"categories\":[{\"key\":\"HIGH\",\"label\":\"High\",\"value\":0,\"color\":\"#D9711F\"}]}",
      "- binding findingsBySeverity means the report fills values from real findings. Leave those values at 0.",
      "- table spec: {\"type\":\"table\",\"mode\":\"findings|static\",\"title\":\"...\",\"columns\":[{\"key\":\"title\",\"label\":\"Finding\"}],\"rows\":[]}",
      "- findings mode ignores rows. Column keys may be title, severity, path, cwe, description, impact, remediation.",
      "- drawing spec: {\"type\":\"drawing\",\"width\":520,\"height\":220,\"shapes\":[...],\"svg\":\"\"}. shapes use rect, ellipse, line, arrow, text, freehand.",
      "- image spec: {\"type\":\"image\",\"dataUrl\":null,\"alt\":\"\",\"caption\":\"\",\"widthMm\":120}. dataUrl is a data:image png, jpeg, gif, or webp URL, or null.",
      "- callout spec: {\"type\":\"callout\",\"tone\":\"info|warning|note\",\"title\":\"...\",\"text\":\"...\"}.",
      "- Placeholders such as {{author}}, {{project}}, {{finding_count}}, and {{finding_types}} must stay written as tokens.",
      "- The builder object may be copied through. Do not put secrets in it.",
      "- Hex colors are #RRGGBB.",
      "",
      "Current profile:",
      json
    ].join("\n");
  }

  function extractJson(text) {
    var raw = String(text || "").trim();
    if (!raw) return { ok: false, error: "empty" };
    var fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(raw);
    if (fenced) raw = fenced[1].trim();
    var start = raw.indexOf("{");
    var end = raw.lastIndexOf("}");
    if (start === -1 || end <= start) return { ok: false, error: "no-object" };
    try {
      return { ok: true, value: JSON.parse(raw.slice(start, end + 1)) };
    } catch (err) {
      return { ok: false, error: "json", detail: err.message };
    }
  }

  function validate(data) {
    var errors = [];
    if (!data || typeof data !== "object" || Array.isArray(data)) {
      return { ok: false, errors: ["The answer is not a JSON object."] };
    }
    if (data.schemaVersion != null && String(data.schemaVersion) !== "1" && String(data.schemaVersion) !== "2") {
      errors.push("schemaVersion must be \"1\". This file says \"" + String(data.schemaVersion) + "\".");
    }
    if (!data.sections || !Array.isArray(data.sections.nodes)) {
      errors.push("sections.nodes must be an array.");
    } else {
      var seen = {};
      data.sections.nodes.forEach(function (node, index) {
        var where = "Section " + (index + 1);
        if (!node || typeof node !== "object") {
          errors.push(where + " is not an object.");
          return;
        }
        if (!node.id || typeof node.id !== "string") errors.push(where + " is missing an id.");
        else if (node.id === "COVER") errors.push("COVER is not a section node. Use coverRenderer.");
        else if (seen[node.id]) errors.push("Duplicate section id " + node.id + ".");
        else seen[node.id] = true;
        if (node.rendererKey === "CUSTOM_BLOCK" || (typeof node.id === "string" && node.id.indexOf("block:") === 0)) {
          var params = node.params || {};
          var type = String(params.blockType || "");
          if (["chart", "table", "drawing", "image", "callout"].indexOf(type) === -1 && !/^[a-z][a-z0-9_-]{0,30}$/.test(type)) {
            errors.push(where + " has a blockType ICARUS cannot store.");
          }
          if (typeof params.spec !== "string" || !params.spec.trim()) {
            errors.push(where + " needs params.spec as a JSON string.");
          } else {
            try { JSON.parse(params.spec); } catch (err) { errors.push(where + " has a spec that is not valid JSON."); }
          }
        }
      });
    }
    if (data.coverRenderer && ["GRADIENT_HERO", "HEADER_BAND", "NONE"].indexOf(data.coverRenderer) === -1) {
      errors.push("coverRenderer must be GRADIENT_HERO, HEADER_BAND, or NONE.");
    }
    if (data.findingRenderer && ["ELEVATED_CARD", "TABULAR"].indexOf(data.findingRenderer) === -1) {
      errors.push("findingRenderer must be ELEVATED_CARD or TABULAR.");
    }
    ["pdfTheme", "htmlTheme"].forEach(function (key) {
      var theme = data[key];
      if (!theme) return;
      ["primaryHex", "secondaryHex", "textHex", "headingHex"].forEach(function (field) {
        if (theme[field] && !/^#[0-9a-fA-F]{6}$/.test(String(theme[field]))) {
          errors.push(key + "." + field + " must be a #RRGGBB color.");
        }
      });
    });
    return { ok: errors.length === 0, errors: errors };
  }

  function parseResponse(text) {
    var extracted = extractJson(text);
    if (!extracted.ok) {
      var message = extracted.error === "empty"
        ? "Paste the model's JSON answer first."
        : extracted.error === "json"
          ? "That text is not valid JSON" + (extracted.detail ? " (" + extracted.detail + ")." : ".")
          : "No JSON object was found in the answer.";
      return { ok: false, errors: [message] };
    }
    var checked = validate(extracted.value);
    if (!checked.ok) return { ok: false, errors: checked.errors, value: extracted.value };
    try {
      var state = P.fromReportProfile(extracted.value);
      return { ok: true, errors: [], profile: extracted.value, state: state };
    } catch (err) {
      return { ok: false, errors: ["The JSON matches the shape but could not be loaded (" + err.message + ")."] };
    }
  }

  function selfCheck() {
    var errors = [];
    function assert(cond, msg) { if (!cond) errors.push(msg); }
    var state = P.makeTemplate({ name: "AI", locale: "en" });
    var prompt = buildPrompt(P.toReportProfile(state));
    assert(prompt.indexOf("schemaVersion") !== -1, "prompt mentions schema");
    assert(prompt.indexOf("CUSTOM_BLOCK") !== -1, "prompt mentions blocks");
    assert(prompt.indexOf("findingsBySeverity") !== -1, "prompt mentions binding");
    var profile = P.toReportProfile(state);
    var fenced = "Sure:\n```json\n" + JSON.stringify(profile) + "\n```";
    var parsed = parseResponse(fenced);
    assert(parsed.ok, "fenced import " + (parsed.errors || []).join("; "));
    var bad = parseResponse("{");
    assert(!bad.ok && bad.errors.length, "bad json explains");
    var unknown = JSON.parse(JSON.stringify(profile));
    unknown.sections.nodes.push({
      id: "block:nope",
      enabled: true,
      order: 99,
      required: false,
      rendererKey: "CUSTOM_BLOCK",
      params: { title: "Nope", blockType: "hologram", spec: "{\"type\":\"hologram\"}" }
    });
    var kept = parseResponse(JSON.stringify(unknown));
    assert(kept.ok, "unknown block still imports");
    assert(kept.state.sections.some(function (section) { return section.block && section.block.type === "hologram"; }), "unknown type preserved");
    var broken = parseResponse("{\"schemaVersion\":\"9\",\"sections\":{\"nodes\":[]}}");
    assert(!broken.ok && broken.errors.some(function (line) { return line.indexOf("schemaVersion") !== -1; }), "version error");
    assert(listProviders().length === 0, "no provider by default");
    return errors;
  }

  return {
    registerProvider: registerProvider,
    listProviders: listProviders,
    buildPrompt: buildPrompt,
    extractJson: extractJson,
    validate: validate,
    parseResponse: parseResponse,
    selfCheck: selfCheck
  };
});
