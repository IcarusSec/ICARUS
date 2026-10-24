/**
 * Free-form A4 pages for the ICARUS template builder.
 * Coordinates are millimetres. A page is a list of elements.
 * The same JSON is stored on a ReportProfile section (rendererKey CANVAS_PAGE,
 * params.spec as a string) so Burp can draw it later.
 * Part of ICARUS, MIT License. No network calls.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.IcarusCanvas = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  var PAGE_W = 210;
  var PAGE_H = 297;
  var TYPES = ["text", "shape", "image", "line", "chart", "table"];
  var FONTS = [
    { id: "sans", label: "Inter", css: "'ICARUS Sans', Inter, Helvetica, Arial, sans-serif" },
    { id: "serif", label: "Source Serif", css: "'ICARUS Serif', Georgia, serif" },
    { id: "mono", label: "JetBrains Mono", css: "'ICARUS Mono', ui-monospace, monospace" }
  ];
  var VARIABLES = [
    { id: "report_title", label: "Report title" },
    { id: "project", label: "Project" },
    { id: "client", label: "Client" },
    { id: "tester", label: "Tester" },
    { id: "date", label: "Date" },
    { id: "version", label: "Version" },
    { id: "classification", label: "Classification" },
    { id: "finding_count", label: "Finding count" },
    { id: "count_critical", label: "Critical count" },
    { id: "count_high", label: "High count" },
    { id: "count_medium", label: "Medium count" },
    { id: "count_low", label: "Low count" },
    { id: "count_info", label: "Info count" },
    { id: "page", label: "Page number" },
    { id: "pages", label: "Page count" }
  ];
  var SEVERITY = [
    { key: "CRITICAL", label: "Critical", color: "#B42318" },
    { key: "HIGH", label: "High", color: "#C2410C" },
    { key: "MEDIUM", label: "Medium", color: "#A16207" },
    { key: "LOW", label: "Low", color: "#0F766E" },
    { key: "INFO", label: "Info", color: "#475569" }
  ];

  function uid(prefix) {
    return (prefix || "el") + "_" + Math.random().toString(36).slice(2, 10);
  }

  function clamp(n, min, max) {
    return Math.max(min, Math.min(max, n));
  }

  function num(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : fallback;
  }

  function hex(value, fallback) {
    var text = String(value || "").trim();
    return /^#[0-9a-fA-F]{6}$/.test(text) ? text.toUpperCase() : fallback;
  }

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function fontCss(id) {
    var found = FONTS.filter(function (font) { return font.id === id; })[0];
    return (found || FONTS[0]).css;
  }

  function round(n) {
    return Math.round(n * 10) / 10;
  }

  function placeholder(kind) {
    var scenes = {
      wheel: { a: "#d9d3cc", b: "#8d8680", c: "#5c564f", label: "" },
      shop: { a: "#d5dde3", b: "#8aa0b2", c: "#4d6474", label: "" },
      people: { a: "#e4e0d8", b: "#b7b1a6", c: "#6e6a62", label: "" },
      band: { a: "#d7e3ea", b: "#7f9aab", c: "#3e5a6b", label: "" },
      field: { a: "#e7e2d9", b: "#b5aa9a", c: "#6d6256", label: "" }
    };
    var scene = scenes[kind] || scenes.field;
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 360">' +
      '<rect width="240" height="360" fill="' + scene.a + '"/>' +
      '<circle cx="120" cy="140" r="54" fill="' + scene.b + '"/>' +
      '<rect x="78" y="200" width="84" height="120" rx="6" fill="' + scene.c + '"/>' +
      '<rect x="0" y="300" width="240" height="60" fill="' + scene.b + '" opacity="0.45"/>' +
      "</svg>";
    return "data:image/svg+xml," + encodeURIComponent(svg);
  }

  function defaultPoints(kind) {
    if (kind === "triangle" || kind === "triangle-down") return [[0, 0], [1, 0], [0.5, 1]];
    if (kind === "triangle-up") return [[0.5, 0], [0, 1], [1, 1]];
    if (kind === "parallelogram") return [[0.2, 0], [1, 0], [0.8, 1], [0, 1]];
    if (kind === "polygon") return [[0.5, 0], [1, 0.25], [0.8, 1], [0.2, 1], [0, 0.25]];
    return [[0, 0], [1, 0], [1, 1], [0, 1]];
  }

  function sanitizePoints(points, fallback) {
    if (!Array.isArray(points)) return fallback.slice();
    var clean = points.slice(0, 12).map(function (point) {
      return [round(clamp(num(point && point[0], 0), -0.5, 1.5)), round(clamp(num(point && point[1], 0), -0.5, 1.5))];
    }).filter(function (point) { return point.length === 2; });
    return clean.length >= 3 ? clean : fallback.slice();
  }

  function normalizeElement(raw) {
    raw = raw && typeof raw === "object" ? raw : {};
    var type = TYPES.indexOf(raw.type) === -1 ? "text" : raw.type;
    var el = {
      id: typeof raw.id === "string" && raw.id ? raw.id.slice(0, 40) : uid("el"),
      type: type,
      name: String(raw.name || "").slice(0, 80),
      x: round(clamp(num(raw.x, 10), -40, 400)),
      y: round(clamp(num(raw.y, 10), -40, 500)),
      w: round(clamp(num(raw.w, 40), 1, 400)),
      h: round(clamp(num(raw.h, 12), 1, 500)),
      rotation: round(clamp(num(raw.rotation, 0), -180, 180)),
      z: Math.round(clamp(num(raw.z, 1), 0, 500)),
      opacity: round(clamp(num(raw.opacity, 1), 0, 1)),
      locked: !!raw.locked,
      hidden: !!raw.hidden,
      groupId: typeof raw.groupId === "string" ? raw.groupId.slice(0, 40) : ""
    };
    if (type === "line") {
      el.x1 = round(num(raw.x1, el.x));
      el.y1 = round(num(raw.y1, el.y));
      el.x2 = round(num(raw.x2, el.x + el.w));
      el.y2 = round(num(raw.y2, el.y + el.h));
      syncLineBox(el);
      el.stroke = hex(raw.stroke, "#1A1A1A");
      el.strokeWidth = round(clamp(num(raw.strokeWidth, 0.4), 0.1, 8));
      el.arrow = !!raw.arrow;
      return el;
    }
    if (type === "text") {
      el.text = String(raw.text == null ? "Text" : raw.text).slice(0, 2000);
      el.font = FONTS.some(function (font) { return font.id === raw.font; }) ? raw.font : "sans";
      el.size = round(clamp(num(raw.size, 16), 6, 200));
      el.weight = num(raw.weight, 400) >= 600 ? 700 : 400;
      el.italic = !!raw.italic;
      el.align = raw.align === "center" || raw.align === "right" ? raw.align : "left";
      el.color = hex(raw.color, "#1A1A1A");
      el.lineHeight = round(clamp(num(raw.lineHeight, 1.15), 0.7, 2));
      el.letterSpacing = round(clamp(num(raw.letterSpacing, 0), -4, 8));
      el.fill = raw.fill ? hex(raw.fill, "") : "";
      el.blend = raw.blend === "difference" ? "difference" : "normal";
      el.padding = round(clamp(num(raw.padding, 0), 0, 20));
      return el;
    }
    if (type === "shape") {
      var shape = ["rect", "ellipse", "triangle", "polygon", "parallelogram"].indexOf(raw.shape) === -1 ? "rect" : raw.shape;
      el.shape = shape;
      el.fill = hex(raw.fill, "#1A1A1A");
      el.stroke = raw.stroke ? hex(raw.stroke, "#1A1A1A") : "";
      el.strokeWidth = round(clamp(num(raw.strokeWidth, 0), 0, 8));
      el.radius = round(clamp(num(raw.radius, 0), 0, 40));
      el.points = sanitizePoints(raw.points, defaultPoints(shape === "triangle" ? "triangle-down" : shape));
      return el;
    }
    if (type === "image") {
      el.src = typeof raw.src === "string" ? raw.src.slice(0, 700000) : "";
      el.slot = raw.slot === "clientLogo" || raw.slot === "companyLogo" ? raw.slot : "";
      el.alt = String(raw.alt || "").slice(0, 160);
      el.fit = "cover";
      el.clip = ["rect", "ellipse", "triangle", "parallelogram", "polygon", "none"].indexOf(raw.clip) === -1 ? "rect" : raw.clip;
      el.points = sanitizePoints(raw.points, defaultPoints(el.clip));
      el.grayscale = !!raw.grayscale;
      el.tint = raw.tint ? hex(raw.tint, "") : "";
      el.tintOpacity = round(clamp(num(raw.tintOpacity, 0.45), 0, 1));
      return el;
    }
    if (type === "chart") {
      el.chart = raw.chart === "pie" || raw.chart === "line" ? raw.chart : "bar";
      el.binding = raw.binding === "static" ? "static" : "findingsBySeverity";
      el.categories = Array.isArray(raw.categories) && raw.categories.length
        ? raw.categories.slice(0, 8).map(function (item, index) {
          var key = String(item && item.key || SEVERITY[index].key).slice(0, 20);
          return {
            key: key,
            label: String(item && item.label || key).slice(0, 24),
            value: clamp(num(item && item.value, 0), 0, 999),
            color: hex(item && item.color, "#64748B")
          };
        })
        : SEVERITY.map(function (item) { return { key: item.key, label: item.label, value: 0, color: item.color }; });
      return el;
    }
    el.mode = raw.mode === "static" ? "static" : "findings";
    el.columns = Array.isArray(raw.columns) && raw.columns.length
      ? raw.columns.slice(0, 6).map(function (col, index) {
        return {
          key: String(col && col.key || ("c" + index)).slice(0, 24),
          label: String(col && col.label || col.key || "Column").slice(0, 40)
        };
      })
      : [
        { key: "title", label: "Finding" },
        { key: "severity", label: "Severity" },
        { key: "path", label: "Path" }
      ];
    el.rows = Array.isArray(raw.rows) ? raw.rows.slice(0, 12).map(function (row) {
      return (Array.isArray(row) ? row : []).slice(0, 6).map(function (cell) { return String(cell == null ? "" : cell).slice(0, 180); });
    }) : [];
    el.headerFill = hex(raw.headerFill, "#F4F4F5");
    el.color = hex(raw.color, "#1A1A1A");
    el.size = round(clamp(num(raw.size, 9), 6, 18));
    el.font = FONTS.some(function (font) { return font.id === raw.font; }) ? raw.font : "sans";
    return el;
  }

  function syncLineBox(el) {
    var pad = Math.max(1.2, (el.strokeWidth || 0.4) + 1);
    el.x = round(Math.min(el.x1, el.x2) - pad);
    el.y = round(Math.min(el.y1, el.y2) - pad);
    el.w = round(Math.max(pad * 2, Math.abs(el.x2 - el.x1) + pad * 2));
    el.h = round(Math.max(pad * 2, Math.abs(el.y2 - el.y1) + pad * 2));
  }

  function normalizePage(raw) {
    raw = raw && typeof raw === "object" ? raw : {};
    var elements = Array.isArray(raw.elements) ? raw.elements.map(normalizeElement) : [];
    elements.sort(function (a, b) { return a.z - b.z; });
    return {
      id: typeof raw.id === "string" && raw.id ? raw.id.slice(0, 40) : uid("page"),
      name: String(raw.name || "Page").slice(0, 80),
      role: raw.role === "cover" ? "cover" : "interior",
      required: !!raw.required,
      background: hex(raw.background, "#FFFFFF"),
      elements: elements
    };
  }

  function normalizeDoc(raw) {
    raw = raw && typeof raw === "object" ? raw : {};
    var colors = Array.isArray(raw.brand && raw.brand.colors) ? raw.brand.colors : [];
    var brandColors = [0, 1, 2, 3, 4, 5].map(function (index) {
      return hex(colors[index], ["#111111", "#FFFFFF", "#A3205A", "#0B2A5B", "#C4A15A", "#1F6F8B"][index]);
    });
    return {
      id: typeof raw.id === "string" && raw.id ? raw.id.slice(0, 80) : uid("user"),
      name: String(raw.name || "Untitled report").slice(0, 120),
      locale: raw.locale === "pt-BR" ? "pt-BR" : "en",
      templateId: raw.templateId || null,
      brand: {
        colors: brandColors,
        logo: typeof raw.brand === "object" && typeof raw.brand.logo === "string" ? raw.brand.logo.slice(0, 700000) : ""
      },
      fields: {
        documentTitle: String(raw.fields && raw.fields.documentTitle || "Security assessment").slice(0, 160),
        project: String(raw.fields && raw.fields.project || "Northwind portal").slice(0, 160),
        client: String(raw.fields && raw.fields.client || "Northwind").slice(0, 160),
        author: String(raw.fields && raw.fields.author || "A. Silva").slice(0, 160),
        classification: String(raw.fields && raw.fields.classification || "Confidential").slice(0, 80),
        version: String(raw.fields && raw.fields.version || "1.0").slice(0, 40),
        date: String(raw.fields && raw.fields.date || "24 Oct 2026").slice(0, 40)
      },
      pages: (Array.isArray(raw.pages) && raw.pages.length ? raw.pages : [blankPage("Page")]).map(normalizePage)
    };
  }

  function blankPage(name) {
    return normalizePage({ id: uid("page"), name: name || "Page", role: "interior", background: "#FFFFFF", elements: [] });
  }

  function sampleVars(doc, pageIndex, pageCount) {
    var fields = (doc && doc.fields) || {};
    return {
      report_title: fields.documentTitle || "Security assessment",
      project: fields.project || "Northwind portal",
      client: fields.client || "Northwind",
      tester: fields.author || "A. Silva",
      author: fields.author || "A. Silva",
      date: fields.date || "24 Oct 2026",
      version: fields.version || "1.0",
      classification: fields.classification || "Confidential",
      finding_count: "3",
      count_critical: "1",
      count_high: "0",
      count_medium: "1",
      count_low: "0",
      count_info: "1",
      page: String((pageIndex || 0) + 1),
      pages: String(pageCount || (doc && doc.pages ? doc.pages.length : 1))
    };
  }

  function applyVars(text, vars) {
    return String(text == null ? "" : text).replace(/\{\{\s*([a-z0-9_]+)\s*\}\}/gi, function (token, key) {
      var value = vars && Object.prototype.hasOwnProperty.call(vars, key) ? vars[key] : null;
      return value == null || value === "" ? token : String(value);
    });
  }

  function sampleFindings() {
    return [
      ["SQL injection in the user lookup", "Critical", "/api/v1/users"],
      ["Reflected script in search", "Medium", "/search"],
      ["Server version banner", "Info", "/"]
    ];
  }

  function chartRows(el, vars) {
    return (el.categories || []).map(function (item) {
      var countKey = "count_" + String(item.key || "").toLowerCase();
      var value = el.binding === "static" ? num(item.value, 0) : num(vars && vars[countKey], 0);
      return { label: item.label || item.key, value: value, color: item.color };
    });
  }

  function newElement(type, partial) {
    partial = partial || {};
    var base = { id: uid("el"), type: type, x: 16, y: 16, z: 1 };
    if (type === "text") {
      return normalizeElement(Object.assign(base, {
        name: partial.preset === "heading" ? "Heading" : partial.preset === "subheading" ? "Subheading" : "Text",
        w: partial.preset === "body" ? 160 : 120,
        h: partial.preset === "heading" ? 18 : partial.preset === "subheading" ? 12 : 28,
        text: partial.preset === "heading" ? "Heading" : partial.preset === "subheading" ? "Subheading" : "Body text",
        size: partial.preset === "heading" ? 28 : partial.preset === "subheading" ? 16 : 11,
        weight: partial.preset === "body" ? 400 : 700,
        color: "#1A1A1A"
      }, partial));
    }
    if (type === "shape") {
      return normalizeElement(Object.assign(base, {
        name: partial.shape || "Shape",
        w: 40, h: 24, shape: partial.shape || "rect", fill: "#1A1A1A"
      }, partial));
    }
    if (type === "line") {
      var line = normalizeElement(Object.assign({
        type: "line", name: partial.arrow ? "Arrow" : "Line",
        x1: 20, y1: 20, x2: 80, y2: 40, stroke: "#1A1A1A", strokeWidth: 0.45, arrow: !!partial.arrow
      }, partial));
      return line;
    }
    if (type === "image") {
      return normalizeElement(Object.assign(base, {
        name: partial.slot === "clientLogo" ? "Client logo" : "Image",
        w: partial.slot ? 28 : 50,
        h: partial.slot ? 14 : 70,
        src: partial.src || "",
        clip: partial.clip || "rect"
      }, partial));
    }
    if (type === "chart") {
      return normalizeElement(Object.assign(base, {
        name: "Findings chart", w: 170, h: 70, chart: "bar", binding: "findingsBySeverity"
      }, partial));
    }
    return normalizeElement(Object.assign(base, {
      name: "Findings table", type: "table", w: 178, h: 60, mode: "findings"
    }, partial));
  }

  function el(partial) {
    return normalizeElement(partial);
  }

  function offsetSegment(x1, y1, x2, y2, dist) {
    var dx = x2 - x1;
    var dy = y2 - y1;
    var len = Math.hypot(dx, dy) || 1;
    var nx = -dy / len;
    var ny = dx / len;
    return [round(x1 + nx * dist), round(y1 + ny * dist), round(x2 + nx * dist), round(y2 + ny * dist)];
  }

  function coverStripes() {
    var strips = [];
    var kinds = ["wheel", "field", "wheel", "shop", "field"];
    for (var i = 0; i < 5; i++) {
      strips.push(el({
        id: "strip" + i, type: "image", name: "Photo strip " + (i + 1),
        x: 38 + i * 27, y: 46, w: 22, h: 148, z: 2,
        src: placeholder(kinds[i]), clip: "rect",
        grayscale: true,
        tint: i === 0 || i === 2 ? "#A3205A" : "",
        tintOpacity: 0.62
      }));
    }
    return normalizePage({
      id: "page:stripes", name: "Cover", role: "cover", required: true, background: "#FFFFFF",
      elements: [
        el({ id: "mark", type: "shape", name: "Mark", shape: "ellipse", x: 97, y: 12, w: 8, h: 8, z: 3, fill: "#FFFFFF", stroke: "#A3205A", strokeWidth: 0.4 }),
        el({ id: "markletter", type: "text", name: "Mark letter", x: 97, y: 13.2, w: 8, h: 6, z: 4, text: "C", size: 8, weight: 700, align: "center", color: "#A3205A" }),
        el({ id: "biz", type: "text", name: "Business name", x: 40, y: 21, w: 130, h: 6, z: 3, text: "BUSINESS NAME", size: 8, weight: 700, align: "center", color: "#A3205A", letterSpacing: 1.2 }),
        el({ id: "tag", type: "text", name: "Tagline", x: 40, y: 27, w: 130, h: 5, z: 3, text: "YOUR TAGLINE HERE", size: 6, align: "center", color: "#9AA0A6", letterSpacing: 0.8 })
      ].concat(strips).concat([
        el({ id: "kicker", type: "text", name: "Kicker", x: 22, y: 214, w: 70, h: 8, z: 3, text: "CORPORATE", size: 13, weight: 500, color: "#8A8F98", letterSpacing: 0.6 }),
        el({ id: "coverword", type: "text", name: "Cover", x: 22, y: 224, w: 70, h: 14, z: 3, text: "COVER", size: 26, weight: 700, color: "#A3205A", lineHeight: 0.9 }),
        el({ id: "designword", type: "text", name: "Design", x: 22, y: 238, w: 70, h: 14, z: 3, text: "DESIGN", size: 26, weight: 700, color: "#A3205A", lineHeight: 0.9 }),
        el({ id: "yearA", type: "text", name: "Year", x: 118, y: 216, w: 22, h: 16, z: 3, text: "20", size: 32, weight: 700, color: "#1A1A1A" }),
        el({ id: "yearB", type: "text", name: "Year accent", x: 138, y: 216, w: 28, h: 16, z: 3, text: "32", size: 32, weight: 700, color: "#A3205A" }),
        el({ id: "blurb", type: "text", name: "Blurb", x: 118, y: 236, w: 68, h: 22, z: 3, text: "Prepared for {{client}}\n{{date}}  ·  v{{version}}", size: 8, color: "#6B7280", lineHeight: 1.35 })
      ])
    });
  }

  function coverSwiss() {
    var blackH = 158;
    return normalizePage({
      id: "page:swiss", name: "Cover", role: "cover", required: true, background: "#FFFFFF",
      elements: [
        el({ id: "block", type: "shape", name: "Black block", shape: "rect", x: 0, y: 0, w: 210, h: blackH, z: 1, fill: "#111111" }),
        el({ id: "date", type: "text", name: "Date", x: 108, y: 36, w: 86, h: 8, z: 3, text: "Wednesday 12 December 2012", size: 9, align: "right", color: "#FFFFFF" }),
        el({ id: "venue", type: "text", name: "Venue", x: 108, y: 62, w: 86, h: 28, z: 3, text: "London College of\nCommunication\nMain Lecture Theatre\nElephant and Castle\nLondon SE1 6SB", size: 9, align: "right", color: "#FFFFFF", lineHeight: 1.25 }),
        el({
          id: "title", type: "text", name: "Title",
          x: -2, y: 146, w: 250, h: 92, z: 4,
          text: "Graphic\nDialogue",
          size: 78, weight: 700, color: "#FFFFFF", align: "left",
          lineHeight: 0.78, letterSpacing: -2, blend: "difference", font: "sans"
        }),
        el({ id: "series", type: "text", name: "Series", x: 12, y: 228, w: 90, h: 12, z: 3, text: "A series of visual conversations", size: 11, weight: 700, color: "#111111" }),
        el({ id: "talks", type: "text", name: "Programme", x: 118, y: 224, w: 78, h: 48, z: 3, text: "10.00\nChao Sioelong &\nHong Chong Ip\n\n14.00\nPhilippe Apeloig &\nApril Grieman\n\n16.00\nEdward Tufte &\nDavid McCandless", size: 8, color: "#222222", lineHeight: 1.2 }),
        el({ id: "book", type: "text", name: "Booking", x: 118, y: 276, w: 78, h: 8, z: 3, text: "To book  ·  {{tester}}", size: 7, color: "#4B5563" })
      ]
    });
  }

  function coverTriangles() {
    var apexX = 105;
    var apexY = 92;
    var left = offsetSegment(8, 0, apexX, apexY, 5);
    var left2 = offsetSegment(8, 0, apexX, apexY, 8.5);
    var right = offsetSegment(202, 0, apexX, apexY, -5);
    var right2 = offsetSegment(202, 0, apexX, apexY, -8.5);
    function line(id, pts, color) {
      return el({ id: id, type: "line", name: "Accent line", x1: pts[0], y1: pts[1], x2: pts[2], y2: pts[3], stroke: color, strokeWidth: 0.35, z: 3 });
    }
    return normalizePage({
      id: "page:triangles", name: "Cover", role: "cover", required: true, background: "#FFFFFF",
      elements: [
        el({ id: "toptri", type: "shape", name: "Top triangle", shape: "triangle", x: 0, y: 0, w: 210, h: apexY, z: 2, fill: "#2A2E33", points: [[0.04, 0], [0.96, 0], [0.5, 1]] }),
        line("goldL", left, "#C4A15A"),
        line("redL", left2, "#D4534A"),
        line("goldR", right, "#C4A15A"),
        line("redR", right2, "#D4534A"),
        el({ id: "word", type: "text", name: "Word", x: 22, y: 128, w: 28, h: 10, z: 3, text: "WORD", size: 15, weight: 700, color: "#1A1A1A", letterSpacing: 0.4 }),
        el({ id: "cover", type: "text", name: "Cover", x: 50, y: 128, w: 32, h: 10, z: 3, text: "COVER", size: 15, weight: 400, color: "#1A1A1A", letterSpacing: 0.4 }),
        el({ id: "design", type: "text", name: "Design", x: 82, y: 128, w: 40, h: 10, z: 3, text: "DESIGN", size: 15, weight: 700, color: "#1A1A1A", letterSpacing: 0.4 }),
        el({ id: "bar1", type: "shape", name: "Logo bar", shape: "rect", x: 154, y: 236, w: 3.2, h: 12, z: 4, rotation: -28, fill: "#C5C7CB" }),
        el({ id: "bar2", type: "shape", name: "Logo bar accent", shape: "rect", x: 160, y: 236, w: 3.2, h: 12, z: 4, rotation: -28, fill: "#D4534A" }),
        el({ id: "logoword", type: "text", name: "Logo word", x: 168, y: 238, w: 24, h: 8, z: 4, text: "logo", size: 11, italic: true, color: "#1A1A1A" }),
        el({ id: "bottom", type: "shape", name: "Bottom triangle", shape: "triangle", x: 48, y: 252, w: 114, h: 45, z: 2, fill: "#2A2E33", points: [[0.5, 0], [0.08, 1], [0.92, 1]] }),
        el({ id: "issued", type: "text", name: "Date", x: 78, y: 274, w: 54, h: 8, z: 5, text: "{{date}}", size: 8, align: "center", color: "#FFFFFF" })
      ]
    });
  }

  function coverViewbook() {
    function photo(id, name, x, y, w, h, kind, z) {
      return el({
        id: id, type: "image", name: name, x: x, y: y, w: w, h: h, z: z,
        src: placeholder(kind), clip: "parallelogram",
        points: [[0.22, 0], [1, 0], [0.78, 1], [0, 1]], grayscale: false
      });
    }
    return normalizePage({
      id: "page:viewbook", name: "Cover", role: "cover", required: true, background: "#FFFFFF",
      elements: [
        el({ id: "crest", type: "shape", name: "Crest", shape: "ellipse", x: 14, y: 12, w: 12, h: 12, z: 3, fill: "#0B2A5B" }),
        el({ id: "school", type: "text", name: "School", x: 28, y: 13, w: 42, h: 10, z: 3, text: "NORTHWIND\nCOLLEGE", size: 7, weight: 700, color: "#0B2A5B", lineHeight: 1.15 }),
        el({ id: "rule", type: "shape", name: "Rule", shape: "rect", x: 74, y: 13, w: 0.4, h: 10, z: 3, fill: "#CBD5E1" }),
        el({ id: "faculty", type: "text", name: "Faculty", x: 80, y: 14, w: 50, h: 8, z: 3, text: "Engineering", size: 14, weight: 700, color: "#0B2A5B" }),
        el({ id: "discover", type: "text", name: "Discover", x: 14, y: 36, w: 90, h: 12, z: 3, text: "DISCOVER", size: 18, weight: 600, color: "#8B9098", letterSpacing: 0.4 }),
        el({ id: "engineering", type: "text", name: "Engineering", x: 14, y: 48, w: 110, h: 16, z: 3, text: "ENGINEERING", size: 26, weight: 700, color: "#0B2A5B", letterSpacing: -0.4 }),
        photo("ph1", "Photo left", 6, 118, 62, 150, "shop", 2),
        photo("ph2", "Photo center", 58, 86, 78, 185, "people", 3),
        photo("ph3", "Photo right", 128, 58, 74, 214, "band", 2),
        el({ id: "accentL", type: "shape", name: "Left accent", shape: "parallelogram", x: 2, y: 150, w: 8, h: 70, z: 4, fill: "#1F6F8B", points: [[0.35, 0], [1, 0], [0.65, 1], [0, 1]] }),
        el({ id: "accentR", type: "shape", name: "Right accent", shape: "parallelogram", x: 196, y: 70, w: 10, h: 90, z: 4, fill: "#94A3B8", points: [[0.3, 0], [1, 0], [0.7, 1], [0, 1]] }),
        el({ id: "viewbook", type: "text", name: "Viewbook", x: 142, y: 248, w: 40, h: 14, z: 5, text: "VIEWBOOK\n{{date}}", size: 9, weight: 700, color: "#FFFFFF", lineHeight: 1.2 })
      ]
    });
  }

  function interiorPage() {
    return normalizePage({
      id: "page:findings", name: "Findings", role: "interior", required: true, background: "#FFFFFF",
      elements: [
        el({ id: "headrule", type: "shape", name: "Header rule", shape: "rect", x: 14, y: 16, w: 182, h: 0.3, z: 2, fill: "#E5E7EB" }),
        el({ id: "running", type: "text", name: "Running title", x: 14, y: 8, w: 120, h: 7, z: 2, text: "{{report_title}}", size: 8, color: "#6B7280" }),
        el({ id: "clientslot", type: "image", name: "Client logo", x: 168, y: 6, w: 28, h: 10, z: 2, slot: "clientLogo", clip: "rect" }),
        el({ id: "h1", type: "text", name: "Page title", x: 14, y: 24, w: 180, h: 12, z: 2, text: "{{project}}", size: 20, weight: 700, color: "#111111" }),
        el({ id: "intro", type: "text", name: "Intro", x: 14, y: 40, w: 180, h: 16, z: 2, text: "Assessment for {{client}}. Tester {{tester}}. {{finding_count}} findings on {{date}}, version {{version}}.", size: 11, color: "#374151", lineHeight: 1.35 }),
        el({ id: "chart", type: "chart", name: "Severity chart", x: 14, y: 60, w: 182, h: 62, z: 2, chart: "bar", binding: "findingsBySeverity" }),
        el({ id: "table", type: "table", name: "Findings", x: 14, y: 130, w: 182, h: 120, z: 2, mode: "findings" }),
        el({ id: "footrule", type: "shape", name: "Footer rule", shape: "rect", x: 14, y: 282, w: 182, h: 0.3, z: 2, fill: "#E5E7EB" }),
        el({ id: "footer", type: "text", name: "Page number", x: 14, y: 284, w: 182, h: 6, z: 2, text: "{{classification}}          {{page}} / {{pages}}", size: 8, align: "left", color: "#6B7280" })
      ]
    });
  }

  function starterDoc(which) {
    var covers = {
      stripes: coverStripes,
      swiss: coverSwiss,
      triangles: coverTriangles,
      viewbook: coverViewbook
    };
    var pages = [];
    if (covers[which]) pages.push(covers[which]());
    if (which !== "interior") pages.push(interiorPage());
    if (which === "interior") pages = [interiorPage()];
    if (which === "blank") pages = [normalizePage({ id: uid("page"), name: "Cover", role: "cover", required: true, background: "#FFFFFF", elements: [] })];
    var names = {
      stripes: "Corporate stripes",
      swiss: "Swiss poster",
      triangles: "Triangle cover",
      viewbook: "Viewbook",
      interior: "Findings page",
      blank: "Blank canvas"
    };
    return normalizeDoc({
      name: names[which] || "Report",
      templateId: which || "blank",
      pages: pages
    });
  }

  var STARTERS = [
    { id: "swiss", name: "Swiss poster", blurb: "A black block with a title that changes color as it crosses into the white." },
    { id: "triangles", name: "Triangle cover", blurb: "Dark triangles, thin accent lines, a bold and light title." },
    { id: "stripes", name: "Photo stripes", blurb: "Vertical photos, two of them tinted, and a two-tone year." },
    { id: "viewbook", name: "Diagonal photos", blurb: "Photos clipped into parallelograms, with a two-color headline." },
    { id: "interior", name: "Findings page", blurb: "Header, severity chart, findings table, and page numbers." },
    { id: "blank", name: "Blank page", blurb: "An empty A4 page. Add anything from the left." }
  ];

  function clipCss(el) {
    if (!el.clip || el.clip === "rect" || el.clip === "none") return "";
    if (el.clip === "ellipse") return "ellipse(50% 50% at 50% 50%)";
    var pts = el.points && el.points.length >= 3 ? el.points : defaultPoints(el.clip);
    return "polygon(" + pts.map(function (point) {
      return (point[0] * 100) + "% " + (point[1] * 100) + "%";
    }).join(", ") + ")";
  }

  function pointsAttr(points) {
    return (points || []).map(function (point) {
      return (point[0] * 100) + "," + (point[1] * 100);
    }).join(" ");
  }

  function chartSvg(el, vars) {
    var rows = chartRows(el, vars);
    if (!rows.length) return "";
    if (el.chart === "pie") {
      var total = rows.reduce(function (sum, row) { return sum + Math.max(0, row.value); }, 0) || 1;
      var angle = -Math.PI / 2;
      var paths = rows.map(function (row) {
        var slice = Math.max(0, row.value) / total * Math.PI * 2;
        if (slice <= 0) return "";
        var end = angle + slice;
        var x1 = 50 + 36 * Math.cos(angle);
        var y1 = 50 + 36 * Math.sin(angle);
        var x2 = 50 + 36 * Math.cos(end);
        var y2 = 50 + 36 * Math.sin(end);
        var large = slice > Math.PI ? 1 : 0;
        var d = "M50 50 L" + x1.toFixed(1) + " " + y1.toFixed(1) + " A36 36 0 " + large + " 1 " + x2.toFixed(1) + " " + y2.toFixed(1) + " Z";
        angle = end;
        return '<path d="' + d + '" fill="' + esc(row.color) + '"/>';
      }).join("");
      return '<svg class="chart" viewBox="0 0 100 100" role="img">' + paths + "</svg>";
    }
    var max = Math.max.apply(null, rows.map(function (row) { return row.value; }).concat([1]));
    if (el.chart === "line") {
      var d = rows.map(function (row, index) {
        var x = rows.length === 1 ? 50 : 8 + index * (84 / (rows.length - 1));
        var y = 78 - (row.value / max) * 62;
        return (index ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1);
      }).join(" ");
      return '<svg class="chart" viewBox="0 0 100 100" role="img"><path d="' + d + '" fill="none" stroke="#2563EB" stroke-width="1.6"/></svg>';
    }
    var gap = 3;
    var barW = (88 - gap * (rows.length - 1)) / rows.length;
    var bars = rows.map(function (row, index) {
      var h = Math.max(row.value > 0 ? 1.2 : 0, (row.value / max) * 62);
      var x = 6 + index * (barW + gap);
      var y = 78 - h;
      return '<rect x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + barW.toFixed(1) + '" height="' + h.toFixed(1) + '" fill="' + esc(row.color) + '"/>' +
        '<text x="' + (x + barW / 2).toFixed(1) + '" y="92" text-anchor="middle" font-size="6" fill="#4B5563">' + esc(row.label) + "</text>";
    }).join("");
    return '<svg class="chart" viewBox="0 0 100 100" preserveAspectRatio="none" role="img">' + bars + "</svg>";
  }

  function tableHtml(element, vars) {
    var rows = element.mode === "static" ? element.rows : sampleFindings();
    var head = element.columns.map(function (col) { return "<th>" + esc(applyVars(col.label, vars)) + "</th>"; }).join("");
    var body = rows.map(function (row) {
      return "<tr>" + element.columns.map(function (col, index) {
        return "<td>" + esc(row[index] == null ? "" : row[index]) + "</td>";
      }).join("") + "</tr>";
    }).join("");
    if (!body) body = '<tr><td colspan="' + element.columns.length + '">—</td></tr>';
    return '<table style="width:100%;border-collapse:collapse;font-family:' + esc(fontCss(element.font)) + ";font-size:" + element.size + 'pt;color:' + esc(element.color) + '"><thead style="background:' + esc(element.headerFill) + '"><tr>' + head + "</tr></thead><tbody>" + body + "</tbody></table>";
  }

  function elementInner(element, vars, logo) {
    if (element.type === "text") {
      var shown = applyVars(element.text, vars);
      return esc(shown).replace(/\n/g, "<br>");
    }
    if (element.type === "shape") {
      if (element.shape === "rect") return "";
      if (element.shape === "ellipse") return "";
      var pts = element.points;
      var sw = element.strokeWidth || 0;
      return '<svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><polygon points="' + pointsAttr(pts) + '" fill="' + esc(element.fill) + '" stroke="' + esc(element.stroke || "none") + '" stroke-width="' + sw + '" vector-effect="non-scaling-stroke"/></svg>';
    }
    if (element.type === "image") {
      var src = element.src;
      if (element.slot === "companyLogo" && logo) src = logo;
      if (element.slot === "clientLogo" && !src) src = "";
      var img = src
        ? '<img alt="' + esc(element.alt) + '" src="' + esc(src) + '" style="width:100%;height:100%;object-fit:cover;' + (element.grayscale ? "filter:grayscale(1);" : "") + '">'
        : '<div class="slot-empty">Client logo</div>';
      var tint = element.tint ? '<div class="tint" style="background:' + esc(element.tint) + ";opacity:" + element.tintOpacity + '"></div>' : "";
      return img + tint;
    }
    if (element.type === "line") return "";
    if (element.type === "chart") return chartSvg(element, vars);
    if (element.type === "table") return tableHtml(element, vars);
    return "";
  }

  function elementStyle(element) {
    var style = [
      "left:" + element.x + "mm",
      "top:" + element.y + "mm",
      "width:" + element.w + "mm",
      "height:" + element.h + "mm",
      "transform:rotate(" + (element.rotation || 0) + "deg)",
      "z-index:" + element.z,
      "opacity:" + (element.hidden ? 0 : element.opacity)
    ];
    if (element.type === "text") {
      style.push("font-family:" + fontCss(element.font));
      style.push("font-size:" + element.size + "pt");
      style.push("font-weight:" + element.weight);
      style.push("font-style:" + (element.italic ? "italic" : "normal"));
      style.push("color:" + element.color);
      style.push("text-align:" + element.align);
      style.push("line-height:" + element.lineHeight);
      style.push("letter-spacing:" + element.letterSpacing + "px");
      style.push("padding:" + (element.padding || 0) + "mm");
      if (element.fill) style.push("background:" + element.fill);
      if (element.blend === "difference") style.push("mix-blend-mode:difference");
    }
    if (element.type === "shape" && (element.shape === "rect" || element.shape === "ellipse")) {
      style.push("background:" + element.fill);
      if (element.shape === "ellipse") style.push("border-radius:50%");
      else if (element.radius) style.push("border-radius:" + element.radius + "mm");
      if (element.stroke && element.strokeWidth) style.push("box-shadow:inset 0 0 0 " + element.strokeWidth + "mm " + element.stroke);
    }
    if (element.type === "image") {
      var clip = clipCss(element);
      if (clip) style.push("clip-path:" + clip);
    }
    if (element.type === "shape" && element.shape !== "rect" && element.shape !== "ellipse") {
      style.push("background:transparent");
    }
    return style.join(";");
  }

  function renderPage(page, options) {
    options = options || {};
    var vars = options.vars || {};
    var logo = options.logo || "";
    var bits = (page.elements || []).filter(function (element) { return !element.hidden; }).map(function (element) {
      var cls = "el el-" + element.type + (element.locked ? " is-locked" : "");
      var extra = "";
      if (element.type === "line") {
        var x1 = ((element.x1 - element.x) / element.w) * 100;
        var y1 = ((element.y1 - element.y) / element.h) * 100;
        var x2 = ((element.x2 - element.x) / element.w) * 100;
        var y2 = ((element.y2 - element.y) / element.h) * 100;
        extra = '<svg class="line" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">' +
          (element.arrow ? '<defs><marker id="ah-' + esc(element.id) + '" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="' + esc(element.stroke) + '"/></marker></defs>' : "") +
          '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="' + esc(element.stroke) + '" stroke-width="' + element.strokeWidth + '" vector-effect="non-scaling-stroke"' +
          (element.arrow ? ' marker-end="url(#ah-' + esc(element.id) + ')"' : "") + "/></svg>";
      }
      return '<div class="' + cls + '" data-id="' + esc(element.id) + '" style="' + elementStyle(element) + '">' + extra + elementInner(element, vars, logo) + "</div>";
    }).join("");
    return bits;
  }

  function profileFromDoc(doc) {
    doc = normalizeDoc(doc);
    var nodes = doc.pages.map(function (page, index) {
      var spec = JSON.stringify({
        background: page.background,
        elements: page.elements
      });
      return {
        id: page.id.indexOf("page:") === 0 ? page.id : "page:" + page.id,
        enabled: true,
        order: index + 1,
        required: !!page.required,
        rendererKey: "CANVAS_PAGE",
        params: {
          title: page.name,
          role: page.role,
          spec: spec
        }
      };
    });
    var primary = doc.brand.colors[2] || "#111111";
    return {
      schemaVersion: "1",
      id: doc.id.indexOf("user:") === 0 || doc.id.indexOf("builtin:") === 0 ? doc.id : "user:" + doc.id.replace(/^user:/, ""),
      name: doc.name,
      locale: doc.locale,
      builtIn: false,
      basedOnId: doc.templateId ? "builder:" + doc.templateId : null,
      coverRenderer: "NONE",
      findingRenderer: "ELEVATED_CARD",
      sections: { nodes: nodes },
      branding: {
        companyLogoPath: null,
        clientLogoPath: null,
        author: doc.fields.author,
        reviewer: "",
        approver: "",
        classification: doc.fields.classification,
        environment: "",
        documentTitle: doc.fields.documentTitle,
        team: "",
        component: doc.fields.project,
        requester: doc.fields.client,
        owner: "",
        assessmentPeriod: doc.fields.date,
        method: ""
      },
      content: {
        includeEvidence: true,
        includeHttpRequest: false,
        includeHttpResponse: false,
        maxRequestBytes: 4096,
        maxResponseBytes: 4096,
        findingFields: ["DESCRIPTION", "IMPACT", "REMEDIATION", "EVIDENCE"],
        cweMode: "HARDCODED_CATALOG",
        cweAllowlist: [],
        includeTocBookmarks: true
      },
      pdfTheme: {
        primaryHex: primary,
        secondaryHex: doc.brand.colors[3] || "#333333",
        textHex: "#1A1A1A",
        headingHex: "#111111",
        tableHeaderHex: "#F4F4F5",
        severityHex: { CRITICAL: "#B42318", HIGH: "#C2410C", MEDIUM: "#A16207", LOW: "#0F766E", INFO: "#475569", FIXED: "#15803D", NOT_FIXED: "#B91C1C" },
        fontStack: "Helvetica",
        baseFontSize: 11,
        pageBox: { pageSize: "A4", marginTop: 0, marginBottom: 0, marginLeft: 0, marginRight: 0, showHeader: false, showFooter: false }
      },
      htmlTheme: {
        primaryHex: primary,
        secondaryHex: doc.brand.colors[3] || "#333333",
        backgroundHex: "#FFFFFF",
        cardBackgroundHex: "#F7F7F7",
        textHex: "#1A1A1A",
        borderHex: "#E5E7EB",
        severityHex: { CRITICAL: "#B42318", HIGH: "#C2410C", MEDIUM: "#A16207", LOW: "#0F766E", INFO: "#475569" },
        fontStack: fontCss("sans"),
        dark: false
      },
      builder: {
        spec: "canvas-1",
        templateId: doc.templateId,
        brand: { colors: doc.brand.colors, logo: doc.brand.logo || null },
        fields: doc.fields,
        note: "Pages live in sections whose rendererKey is CANVAS_PAGE. params.spec is a JSON string of {background, elements}. Coordinates are millimetres on A4. Ignored fields in builder are the brand swatches."
      }
    };
  }

  function pageFromNode(node) {
    var params = node.params || {};
    var parsed = {};
    var invalid = false;
    if (typeof params.spec === "string" && params.spec.trim()) {
      try {
        parsed = JSON.parse(params.spec);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) invalid = true;
      } catch (err) {
        invalid = true;
      }
    }
    if (invalid) parsed = { background: "#FFFFFF", elements: [] };
    return {
      page: normalizePage({
        id: node.id,
        name: params.title || "Page",
        role: params.role === "cover" ? "cover" : "interior",
        required: !!node.required,
        background: parsed.background,
        elements: parsed.elements || []
      }),
      invalid: invalid
    };
  }

  function docFromProfile(data) {
    var errors = [];
    var warnings = [];
    if (!data || typeof data !== "object" || Array.isArray(data)) {
      return { ok: false, errors: ["The answer is not a JSON object."], warnings: [], doc: null };
    }
    if (data.schemaVersion != null && String(data.schemaVersion) !== "1" && String(data.schemaVersion) !== "2") {
      errors.push("schemaVersion must be \"1\". This file says \"" + data.schemaVersion + "\".");
    }
    if (!data.sections || !Array.isArray(data.sections.nodes)) {
      errors.push("sections.nodes must be a list of pages.");
      return { ok: false, errors: errors, warnings: warnings, doc: null };
    }
    var canvasNodes = data.sections.nodes.filter(function (node) {
      return node && (node.rendererKey === "CANVAS_PAGE" || (typeof node.id === "string" && node.id.indexOf("page:") === 0));
    });
    var pages = [];
    if (!canvasNodes.length) {
      warnings.push("This profile has no free-form pages. A simple page was built from its sections so you can keep editing.");
      pages = legacyPages(data);
    } else {
      canvasNodes.sort(function (a, b) { return num(a.order, 0) - num(b.order, 0); }).forEach(function (node, index) {
        var parsed = pageFromNode(node);
        if (parsed.invalid) errors.push("Page " + (index + 1) + " (" + (node.params && node.params.title || node.id) + "): the design text is not valid JSON.");
        (parsed.page.elements || []).forEach(function (element, elementIndex) {
          if (TYPES.indexOf(element.type) === -1) {
            warnings.push("Page " + (index + 1) + ", element " + (elementIndex + 1) + ": unknown type. It was kept and will be skipped when the report is drawn.");
          }
        });
        pages.push(parsed.page);
      });
    }
    if (errors.length) return { ok: false, errors: errors, warnings: warnings, doc: null };
    var branding = data.branding || {};
    var builder = data.builder && typeof data.builder === "object" ? data.builder : {};
    var doc = normalizeDoc({
      id: data.id,
      name: data.name,
      locale: data.locale,
      templateId: builder.templateId || null,
      brand: builder.brand || {},
      fields: Object.assign({
        documentTitle: branding.documentTitle || data.name || "Security assessment",
        project: branding.component || "",
        client: branding.requester || "",
        author: branding.author || "",
        classification: branding.classification || "",
        date: branding.assessmentPeriod || "",
        version: builder.fields && builder.fields.version || "1.0"
      }, builder.fields || {}),
      pages: pages
    });
    return { ok: true, errors: [], warnings: warnings, doc: doc };
  }

  function legacyPages(data) {
    var nodes = (data.sections.nodes || []).slice().sort(function (a, b) { return num(a.order, 0) - num(b.order, 0); });
    var y = 20;
    var elements = [];
    if (data.coverRenderer && data.coverRenderer !== "NONE") {
      elements.push(el({ type: "text", name: "Title", x: 16, y: 40, w: 178, h: 20, text: data.branding && data.branding.documentTitle || data.name || "Report", size: 28, weight: 700, z: 1 }));
      y = 70;
    }
    nodes.forEach(function (node) {
      if (!node || node.id === "COVER") return;
      var title = node.params && node.params.title || node.id;
      elements.push(el({ type: "text", name: title, x: 16, y: y, w: 178, h: 10, text: String(title), size: 16, weight: 700, z: elements.length + 1 }));
      y += 12;
      var body = node.params && node.params.content || "";
      if (body) {
        elements.push(el({ type: "text", name: title + " body", x: 16, y: y, w: 178, h: 24, text: String(body).slice(0, 400), size: 11, weight: 400, z: elements.length + 1 }));
        y += 28;
      }
    });
    if (y > 270) {
      return [
        normalizePage({ name: "Cover", role: "cover", background: "#FFFFFF", elements: elements.slice(0, 2) }),
        normalizePage({ name: "Body", role: "interior", background: "#FFFFFF", elements: elements.slice(2) })
      ];
    }
    return [normalizePage({ name: "Page", role: "cover", background: "#FFFFFF", elements: elements })];
  }

  function validateProfile(data) {
    var result = docFromProfile(data);
    if (!result.ok) return result;
    result.doc.pages.forEach(function (page, pageIndex) {
      page.elements.forEach(function (element, elementIndex) {
        ["x", "y", "w", "h"].forEach(function (key) {
          if (typeof element[key] !== "number" || !isFinite(element[key])) {
            result.errors.push("Page " + (pageIndex + 1) + ", element " + (elementIndex + 1) + ": " + key + " must be a number of millimetres.");
          }
        });
        if (element.type === "text" && element.color && !/^#[0-9A-F]{6}$/.test(element.color)) {
          result.errors.push("Page " + (pageIndex + 1) + ", " + (element.name || "text") + ": color must look like #112233.");
        }
      });
    });
    result.ok = result.errors.length === 0;
    if (!result.ok) result.doc = null;
    return result;
  }

  function boundsOf(element) {
    return { x: element.x, y: element.y, w: element.w, h: element.h, rotation: element.rotation || 0 };
  }

  function unionBounds(elements) {
    if (!elements.length) return null;
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    elements.forEach(function (element) {
      corners(element).forEach(function (point) {
        minX = Math.min(minX, point[0]);
        minY = Math.min(minY, point[1]);
        maxX = Math.max(maxX, point[0]);
        maxY = Math.max(maxY, point[1]);
      });
    });
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
  }

  function corners(element) {
    var cx = element.x + element.w / 2;
    var cy = element.y + element.h / 2;
    var rad = (element.rotation || 0) * Math.PI / 180;
    return [[-element.w / 2, -element.h / 2], [element.w / 2, -element.h / 2], [element.w / 2, element.h / 2], [-element.w / 2, element.h / 2]].map(function (point) {
      return [
        cx + point[0] * Math.cos(rad) - point[1] * Math.sin(rad),
        cy + point[0] * Math.sin(rad) + point[1] * Math.cos(rad)
      ];
    });
  }

  function alignElements(elements, mode) {
    if (elements.length < 2) return;
    var box = unionBounds(elements);
    elements.forEach(function (element) {
      if (mode === "left") element.x = round(box.x);
      if (mode === "center") element.x = round(box.x + (box.w - element.w) / 2);
      if (mode === "right") element.x = round(box.x + box.w - element.w);
      if (mode === "top") element.y = round(box.y);
      if (mode === "middle") element.y = round(box.y + (box.h - element.h) / 2);
      if (mode === "bottom") element.y = round(box.y + box.h - element.h);
      if (element.type === "line") shiftLineToBox(element);
    });
  }

  function shiftLineToBox(element) {
    var dx = element.x - (Math.min(element.x1, element.x2) - Math.max(1.2, element.strokeWidth + 1));
    var dy = element.y - (Math.min(element.y1, element.y2) - Math.max(1.2, element.strokeWidth + 1));
    if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return;
  }

  function moveElement(element, dx, dy) {
    if (element.type === "line") {
      element.x1 = round(element.x1 + dx);
      element.y1 = round(element.y1 + dy);
      element.x2 = round(element.x2 + dx);
      element.y2 = round(element.y2 + dy);
      syncLineBox(element);
      return;
    }
    element.x = round(element.x + dx);
    element.y = round(element.y + dy);
  }

  function distribute(elements, axis) {
    if (elements.length < 3) return;
    var sorted = elements.slice().sort(function (a, b) { return axis === "h" ? a.x - b.x : a.y - b.y; });
    var first = sorted[0];
    var last = sorted[sorted.length - 1];
    var span = axis === "h"
      ? (last.x + last.w) - first.x
      : (last.y + last.h) - first.y;
    var size = sorted.reduce(function (sum, element) { return sum + (axis === "h" ? element.w : element.h); }, 0);
    var gap = (span - size) / (sorted.length - 1);
    var cursor = axis === "h" ? first.x : first.y;
    sorted.forEach(function (element) {
      var before = axis === "h" ? element.x : element.y;
      var next = round(cursor);
      if (axis === "h") element.x = next;
      else element.y = next;
      if (element.type === "line") moveElement(element, axis === "h" ? next - before : 0, axis === "h" ? 0 : next - before);
      cursor += (axis === "h" ? element.w : element.h) + gap;
    });
  }

  function snapDelta(box, others, threshold) {
    var guides = [];
    var dx = 0;
    var dy = 0;
    var bestX = threshold + 1;
    var bestY = threshold + 1;
    var xLines = [0, PAGE_W / 2, PAGE_W];
    var yLines = [0, PAGE_H / 2, PAGE_H];
    others.forEach(function (element) {
      xLines.push(element.x, element.x + element.w / 2, element.x + element.w);
      yLines.push(element.y, element.y + element.h / 2, element.y + element.h);
    });
    [[box.x, "left"], [box.x + box.w / 2, "center"], [box.x + box.w, "right"]].forEach(function (edge) {
      xLines.forEach(function (line) {
        var dist = Math.abs(edge[0] - line);
        if (dist < bestX && dist <= threshold) {
          bestX = dist;
          dx = line - edge[0];
          guides = guides.filter(function (guide) { return guide.axis !== "x"; });
          guides.push({ axis: "x", at: line });
        }
      });
    });
    [[box.y, "top"], [box.y + box.h / 2, "middle"], [box.y + box.h, "bottom"]].forEach(function (edge) {
      yLines.forEach(function (line) {
        var dist = Math.abs(edge[0] - line);
        if (dist < bestY && dist <= threshold) {
          bestY = dist;
          dy = line - edge[0];
          guides = guides.filter(function (guide) { return guide.axis !== "y"; });
          guides.push({ axis: "y", at: line });
        }
      });
    });
    return { dx: dx, dy: dy, guides: guides };
  }

  function selfCheck() {
    var swiss = starterDoc("swiss");
    var profile = profileFromDoc(swiss);
    if (profile.schemaVersion !== "1") throw new Error("schema");
    if (profile.coverRenderer !== "NONE") throw new Error("cover");
    var node = profile.sections.nodes[0];
    if (node.rendererKey !== "CANVAS_PAGE") throw new Error("renderer");
    var spec = JSON.parse(node.params.spec);
    if (!spec.elements.some(function (element) { return element.blend === "difference"; })) throw new Error("blend");
    if (!spec.elements.some(function (element) { return element.type === "shape" && element.h > 140; })) throw new Error("block");
    var back = docFromProfile(profile);
    if (!back.ok || back.doc.pages[0].elements.length < 5) throw new Error("roundtrip");
    var triangles = starterDoc("triangles");
    var shapes = triangles.pages[0].elements.filter(function (element) { return element.type === "shape" && element.shape === "triangle"; });
    if (shapes.length < 2) throw new Error("triangles");
    if (triangles.pages[0].elements.filter(function (element) { return element.type === "line"; }).length < 4) throw new Error("lines");
    var stripes = starterDoc("stripes");
    var images = stripes.pages[0].elements.filter(function (element) { return element.type === "image"; });
    if (images.length !== 5) throw new Error("strips");
    if (images.filter(function (element) { return element.tint === "#A3205A"; }).length !== 2) throw new Error("tint");
    var view = starterDoc("viewbook");
    if (!view.pages[0].elements.some(function (element) { return element.clip === "parallelogram"; })) throw new Error("clip");
    var html = renderPage(swiss.pages[0], { vars: sampleVars(swiss, 0, 2) });
    if (html.indexOf("mix-blend-mode:difference") === -1) throw new Error("css blend");
    if (html.indexOf("<script") !== -1) throw new Error("script");
    var filled = applyVars("Hello {{client}}", { client: "Northwind" });
    if (filled !== "Hello Northwind") throw new Error("vars");
    var bad = validateProfile({ schemaVersion: "9", sections: { nodes: [] } });
    if (bad.ok || !bad.errors.length) throw new Error("validate");
    var broken = validateProfile({
      schemaVersion: "1",
      sections: { nodes: [{ id: "page:x", rendererKey: "CANVAS_PAGE", params: { title: "P", spec: "{no" } }] }
    });
    if (broken.ok) throw new Error("broken spec should fail");
    var group = [newElement("shape", { x: 10, y: 10, w: 20, h: 10 }), newElement("shape", { x: 40, y: 30, w: 20, h: 10 })];
    alignElements(group, "left");
    if (group[0].x !== group[1].x) throw new Error("align");
    var snap = snapDelta({ x: 99, y: 10, w: 10, h: 10 }, [], 2);
    if (Math.abs((99 + snap.dx + 5) - 105) > 0.2) throw new Error("snap center");
    if (profileFromDoc(starterDoc("stripes")).sections.nodes.length !== 2) throw new Error("interior included");
    return "canvas ok";
  }

  return {
    PAGE_W: PAGE_W,
    PAGE_H: PAGE_H,
    FONTS: FONTS,
    VARIABLES: VARIABLES,
    STARTERS: STARTERS,
    uid: uid,
    starterDoc: starterDoc,
    blankPage: blankPage,
    normalizeDoc: normalizeDoc,
    normalizePage: normalizePage,
    newElement: newElement,
    renderPage: renderPage,
    profileFromDoc: profileFromDoc,
    docFromProfile: docFromProfile,
    validateProfile: validateProfile,
    sampleVars: sampleVars,
    applyVars: applyVars,
    alignElements: alignElements,
    distribute: distribute,
    moveElement: moveElement,
    snapDelta: snapDelta,
    unionBounds: unionBounds,
    corners: corners,
    boundsOf: boundsOf,
    fontCss: fontCss,
    selfCheck: selfCheck
  };
});
