/**
 * Custom report blocks for the ICARUS template builder.
 * Charts, tables, drawings, images, and callouts render in the browser.
 * The same spec JSON is what ICARUS reads from a CUSTOM_BLOCK section.
 * Part of ICARUS, MIT License. No network calls.
 */
(function (root, factory) {
  var profile = (root && root.IcarusProfile) || (typeof require === "function" ? require("./profile.js") : null);
  var api = factory(profile);
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.IcarusBlocks = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (P) {
  var KNOWN = ["chart", "table", "drawing", "image", "callout"];

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function num(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : fallback;
  }

  function renderInner(section, state) {
    var block = section && section.block ? section.block : { type: "unknown", unsupported: true };
    if (block.invalid) return '<p class="block-note">' + esc(note(state, "invalid")) + "</p>";
    if (KNOWN.indexOf(block.type) === -1) {
      return '<p class="block-note">' + esc(note(state, "unsupported", block.type)) + "</p>";
    }
    if (block.type === "chart") return chartSvg(block, state);
    if (block.type === "table") return tableHtml(block, state);
    if (block.type === "drawing") return drawingSvg(block);
    if (block.type === "image") return imageHtml(block);
    return calloutHtml(block, state);
  }

  function note(state, kind, type) {
    var br = state && state.locale === "pt-BR";
    if (kind === "invalid") return br ? "Este bloco não pôde ser lido." : "This block could not be read.";
    var label = String(type || "unknown").replace(/[^a-z0-9_-]/gi, "");
    return br
      ? "Bloco não suportado (" + label + ") omitido."
      : "Unsupported block (" + label + ") omitted.";
  }

  function chartValues(block, state) {
    var text = state && state.locale === "pt-BR"
      ? { CRITICAL: "Crítica", HIGH: "Alta", MEDIUM: "Média", LOW: "Baixa", INFO: "Info" }
      : { CRITICAL: "Critical", HIGH: "High", MEDIUM: "Medium", LOW: "Low", INFO: "Info" };
    if (block.binding === "findingsBySeverity") {
      var counts = P.severityCounts(state || {});
      return (block.categories || []).map(function (item) {
        var key = String(item.key || "").toUpperCase();
        return {
          label: text[key] || item.label || key,
          value: counts[key] != null ? counts[key] : 0,
          color: item.color || (P.SEVERITY_COLORS && P.SEVERITY_COLORS[key]) || "#6E6E6E"
        };
      });
    }
    return (block.categories || []).map(function (item) {
      return { label: item.label || item.key, value: num(item.value, 0), color: item.color || "#3E7BB8" };
    });
  }

  function chartSvg(block, state) {
    var rows = chartValues(block, state);
    if (!rows.length) return '<p class="block-note">—</p>';
    if (block.chart === "pie") return pieSvg(rows);
    if (block.chart === "line") return lineSvg(rows);
    return barSvg(rows);
  }

  function barSvg(rows) {
    var width = 640;
    var height = 240;
    var padL = 36;
    var padB = 48;
    var padT = 16;
    var padR = 12;
    var max = rows.reduce(function (m, row) { return Math.max(m, row.value); }, 0);
    if (max < 1) max = 1;
    var innerW = width - padL - padR;
    var innerH = height - padT - padB;
    var gap = 12;
    var barW = Math.max(8, (innerW - gap * (rows.length - 1)) / rows.length);
    var parts = ['<svg class="chart chart-bar" viewBox="0 0 ' + width + " " + height + '" role="img">'];
    parts.push('<line x1="' + padL + '" y1="' + (padT + innerH) + '" x2="' + (width - padR) + '" y2="' + (padT + innerH) + '" stroke="#E4E7EC" stroke-width="1"/>');
    rows.forEach(function (row, index) {
      var h = Math.max(row.value > 0 ? 2 : 0, (row.value / max) * innerH);
      var x = padL + index * (barW + gap);
      var y = padT + innerH - h;
      parts.push('<rect x="' + round(x) + '" y="' + round(y) + '" width="' + round(barW) + '" height="' + round(h) + '" rx="3" fill="' + esc(row.color) + '"/>');
      parts.push('<text x="' + round(x + barW / 2) + '" y="' + (y - 6) + '" text-anchor="middle" font-size="12" fill="#1A1A1A" font-family="Helvetica, Arial, sans-serif">' + esc(String(row.value)) + "</text>");
      parts.push('<text x="' + round(x + barW / 2) + '" y="' + (height - 18) + '" text-anchor="middle" font-size="11" fill="#4A5568" font-family="Helvetica, Arial, sans-serif">' + esc(row.label) + "</text>");
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function lineSvg(rows) {
    var width = 640;
    var height = 240;
    var padL = 36;
    var padB = 48;
    var padT = 16;
    var padR = 16;
    var max = rows.reduce(function (m, row) { return Math.max(m, row.value); }, 0);
    if (max < 1) max = 1;
    var innerW = width - padL - padR;
    var innerH = height - padT - padB;
    var step = rows.length > 1 ? innerW / (rows.length - 1) : 0;
    var points = rows.map(function (row, index) {
      var x = padL + index * step;
      var y = padT + innerH - (row.value / max) * innerH;
      return [x, y];
    });
    var d = points.map(function (point, index) { return (index ? "L" : "M") + round(point[0]) + " " + round(point[1]); }).join(" ");
    var parts = ['<svg class="chart chart-line" viewBox="0 0 ' + width + " " + height + '" role="img">'];
    parts.push('<line x1="' + padL + '" y1="' + (padT + innerH) + '" x2="' + (width - padR) + '" y2="' + (padT + innerH) + '" stroke="#E4E7EC"/>');
    parts.push('<path d="' + d + '" fill="none" stroke="#2563EB" stroke-width="2.5" stroke-linejoin="round"/>');
    points.forEach(function (point, index) {
      parts.push('<circle cx="' + round(point[0]) + '" cy="' + round(point[1]) + '" r="4" fill="' + esc(rows[index].color) + '"/>');
      parts.push('<text x="' + round(point[0]) + '" y="' + (height - 18) + '" text-anchor="middle" font-size="11" fill="#4A5568" font-family="Helvetica, Arial, sans-serif">' + esc(rows[index].label) + "</text>");
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function pieSvg(rows) {
    var total = rows.reduce(function (sum, row) { return sum + Math.max(0, row.value); }, 0);
    var size = 220;
    var cx = 110;
    var cy = 110;
    var r = 78;
    var parts = ['<svg class="chart chart-pie" viewBox="0 0 ' + size + " " + size + '" role="img">'];
    if (total <= 0) {
      parts.push('<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="#F3F4F6" stroke="#E5E7EB"/>');
      parts.push("</svg>");
      return parts.join("");
    }
    var angle = -Math.PI / 2;
    rows.forEach(function (row) {
      var slice = (Math.max(0, row.value) / total) * Math.PI * 2;
      if (slice <= 0) return;
      var end = angle + slice;
      var large = slice > Math.PI ? 1 : 0;
      var x1 = cx + r * Math.cos(angle);
      var y1 = cy + r * Math.sin(angle);
      var x2 = cx + r * Math.cos(end);
      var y2 = cy + r * Math.sin(end);
      if (Math.abs(slice - Math.PI * 2) < 0.001) {
        parts.push('<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + esc(row.color) + '"/>');
      } else {
        parts.push('<path d="M ' + cx + " " + cy + " L " + round(x1) + " " + round(y1) + " A " + r + " " + r + " 0 " + large + " 1 " + round(x2) + " " + round(y2) + ' Z" fill="' + esc(row.color) + '"/>');
      }
      angle = end;
    });
    parts.push("</svg>");
    var legend = rows.map(function (row) {
      return '<li><i style="background:' + esc(row.color) + '"></i><span>' + esc(row.label) + "</span><b>" + esc(String(row.value)) + "</b></li>";
    }).join("");
    return '<div class="pie-wrap">' + parts.join("") + '<ul class="pie-legend">' + legend + "</ul></div>";
  }

  function tableHtml(block, state) {
    var columns = block.columns || [];
    var rows = block.mode === "findings" ? findingRows(columns, state) : (block.rows || []);
    var head = columns.map(function (col) { return "<th>" + esc(col.label) + "</th>"; }).join("");
    var body = rows.map(function (row) {
      return "<tr>" + columns.map(function (_, index) { return "<td>" + esc(row[index] == null ? "" : row[index]) + "</td>"; }).join("") + "</tr>";
    }).join("");
    if (!body) body = "<tr><td colspan=\"" + columns.length + "\">—</td></tr>";
    return '<div class="table-wrap" data-role="table" data-section="' + esc(sectionId(block)) + '"><table><thead><tr>' + head + "</tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function sectionId() { return ""; }

  function findingRows(columns, state) {
    var findings = P.sampleFindings(state && state.locale);
    var labels = state && state.locale === "pt-BR"
      ? { CRITICAL: "Crítica", HIGH: "Alta", MEDIUM: "Média", LOW: "Baixa", INFO: "Info" }
      : { CRITICAL: "Critical", HIGH: "High", MEDIUM: "Medium", LOW: "Low", INFO: "Info" };
    return findings.map(function (finding) {
      return columns.map(function (col) {
        var key = String(col.key || "").toLowerCase();
        if (key === "title" || key === "finding") return finding.title;
        if (key === "severity") return labels[finding.severity] || finding.severity;
        if (key === "path" || key === "where") return finding.path;
        if (key === "cwe") return finding.cwe;
        if (key === "type" || key === "category") return finding.type;
        if (key === "description") return finding.description;
        if (key === "impact") return finding.impact;
        if (key === "remediation") return finding.remediation;
        return finding[key] || "";
      });
    });
  }

  function imageHtml(block) {
    var src = P.sanitizeLogo(block.dataUrl);
    if (!src) return '<p class="block-note">PNG, JPEG, GIF, or WebP.</p>';
    var width = num(block.widthMm, 120);
    return '<figure class="figure-block"><img alt="' + esc(block.alt || "") + '" src="' + src + '" style="width:' + width + 'mm;max-width:100%;height:auto">' +
      (block.caption ? "<figcaption>" + esc(block.caption) + "</figcaption>" : "") + "</figure>";
  }

  function calloutHtml(block) {
    var tone = block.tone === "warning" || block.tone === "note" ? block.tone : "info";
    var text = esc(block.text || "").replace(/\n/g, "<br>");
    return '<aside class="callout ' + tone + '" data-role="body">' + text + "</aside>";
  }

  function drawingSvg(block) {
    var raw = typeof block.svg === "string" ? block.svg.trim() : "";
    if (raw.indexOf("<svg") !== -1) {
      var safe = sanitizeSvg(raw);
      if (safe) return '<div class="diagram-wrap">' + safe + "</div>";
    }
    var w = num(block.width, 520);
    var h = num(block.height, 220);
    var shapes = block.shapes || [];
    var marker = shapes.some(function (shape) { return shape && shape.type === "arrow"; })
      ? '<defs><marker id="arrowhead" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 Z" fill="#1A1A1A"/></marker></defs>'
      : "";
    var body = shapes.map(shapeSvg).join("");
    return '<svg class="diagram" viewBox="0 0 ' + w + " " + h + '" data-draw="1">' + marker + body + "</svg>";
  }

  function shapeSvg(shape) {
    if (!shape) return "";
    if (shape.type === "rect") {
      return '<rect x="' + shape.x + '" y="' + shape.y + '" width="' + shape.w + '" height="' + shape.h + '" rx="4" fill="' + esc(shape.fill || "none") + '" stroke="' + esc(shape.stroke) + '" stroke-width="1.5"/>';
    }
    if (shape.type === "ellipse") {
      return '<ellipse cx="' + (shape.x + shape.w / 2) + '" cy="' + (shape.y + shape.h / 2) + '" rx="' + (shape.w / 2) + '" ry="' + (shape.h / 2) + '" fill="' + esc(shape.fill || "none") + '" stroke="' + esc(shape.stroke) + '" stroke-width="1.5"/>';
    }
    if (shape.type === "line") {
      return '<line x1="' + shape.x1 + '" y1="' + shape.y1 + '" x2="' + shape.x2 + '" y2="' + shape.y2 + '" stroke="' + esc(shape.stroke) + '" stroke-width="1.75"/>';
    }
    if (shape.type === "arrow") {
      return '<line x1="' + shape.x1 + '" y1="' + shape.y1 + '" x2="' + shape.x2 + '" y2="' + shape.y2 + '" stroke="' + esc(shape.stroke) + '" stroke-width="1.75" marker-end="url(#arrowhead)"/>';
    }
    if (shape.type === "text") {
      return '<text x="' + shape.x + '" y="' + shape.y + '" fill="' + esc(shape.fill || "#1A1A1A") + '" font-size="' + shape.size + '" font-family="Helvetica, Arial, sans-serif">' + esc(shape.text) + "</text>";
    }
    if (shape.type === "freehand" && shape.points && shape.points.length) {
      var d = shape.points.map(function (point, index) {
        return (index ? "L" : "M") + point[0] + " " + point[1];
      }).join(" ");
      return '<path d="' + d + '" fill="none" stroke="' + esc(shape.stroke) + '" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>';
    }
    return "";
  }

  function sanitizeSvg(svg) {
    var text = String(svg || "");
    if (text.length > 80000) text = text.slice(0, 80000);
    if (typeof DOMParser !== "undefined") {
      var doc = new DOMParser().parseFromString(text, "image/svg+xml");
      var rootNode = doc.documentElement;
      if (!rootNode || rootNode.nodeName.toLowerCase() !== "svg") return "";
      if (doc.querySelector("parsererror")) return "";
      stripUnsafe(rootNode);
      return rootNode.outerHTML;
    }
    if (/<\s*script|<\s*foreignObject|<\s*iframe|on[a-z]+\s*=|javascript:/i.test(text)) return "";
    if (!/^<svg[\s>]/i.test(text.trim())) return "";
    return text;
  }

  function stripUnsafe(node) {
    var allowed = { svg: 1, g: 1, rect: 1, circle: 1, ellipse: 1, line: 1, polyline: 1, polygon: 1, path: 1, text: 1, tspan: 1, title: 1, desc: 1, defs: 1, marker: 1 };
    var children = Array.prototype.slice.call(node.children || []);
    children.forEach(function (child) {
      var name = child.nodeName.toLowerCase();
      if (!allowed[name]) {
        child.remove();
        return;
      }
      Array.prototype.slice.call(child.attributes || []).forEach(function (attr) {
        var attrName = attr.name.toLowerCase();
        var value = attr.value || "";
        if (attrName.indexOf("on") === 0 || /javascript:/i.test(value) || attrName === "href" || attrName === "xlink:href") {
          child.removeAttribute(attr.name);
        }
      });
      stripUnsafe(child);
    });
  }

  function round(n) { return Math.round(n * 10) / 10; }

  function selfCheck() {
    var errors = [];
    function assert(cond, msg) { if (!cond) errors.push(msg); }
    var state = P.makeTemplate({ name: "Blocks", locale: "en", enabled: ["COVER", "FINDINGS"] });
    var chart = P.makeBlock({ type: "chart", chart: "bar", binding: "findingsBySeverity", title: "Findings by severity" });
    state.sections.push(chart);
    var html = renderInner(chart, state);
    assert(html.indexOf("<svg") !== -1, "bar svg");
    assert(html.indexOf(">1<") !== -1 || html.indexOf(">1</text>") !== -1, "sample count");
    var pie = P.makeBlock({ type: "chart", chart: "pie", binding: "findingsBySeverity" });
    assert(renderInner(pie, state).indexOf("<path") !== -1 || renderInner(pie, state).indexOf("<circle") !== -1, "pie");
    var line = P.makeBlock({ type: "chart", chart: "line", binding: "static", categories: [
      { key: "A", label: "A", value: 1, color: "#111111" },
      { key: "B", label: "B", value: 3, color: "#222222" }
    ] });
    assert(renderInner(line, state).indexOf("<path") !== -1, "line");
    var table = P.makeBlock({ type: "table", mode: "findings", columns: [{ key: "title", label: "Finding" }, { key: "severity", label: "Severity" }] });
    var tableHtmlText = renderInner(table, state);
    assert(tableHtmlText.indexOf("<table") !== -1, "table");
    assert(tableHtmlText.indexOf("SQL") !== -1 || tableHtmlText.indexOf("Injeção") !== -1 || tableHtmlText.indexOf("sql") !== -1, "finding row");
    var callout = P.makeBlock({ type: "callout", tone: "warning", text: "Retest required" });
    assert(renderInner(callout, state).indexOf("Retest required") !== -1, "callout");
    var drawing = P.makeBlock({ type: "drawing", shapes: [{ type: "rect", x: 4, y: 4, w: 40, h: 20, fill: "#E8EEF8", stroke: "#2563EB" }, { type: "arrow", x1: 10, y1: 40, x2: 80, y2: 40, stroke: "#111111" }] });
    assert(renderInner(drawing, state).indexOf("<rect") !== -1, "rect");
    var hostile = P.makeBlock({ type: "drawing", svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>alert(1)</script><rect width="10" height="10"/></svg>' });
    var safe = renderInner(hostile, state);
    assert(safe.indexOf("<script") === -1, "script stripped");
    var unknown = { block: { type: "hologram", unsupported: true } };
    assert(renderInner(unknown, state).indexOf("Unsupported") !== -1, "unknown degrades");
    return errors;
  }

  return {
    renderInner: renderInner,
    sanitizeSvg: sanitizeSvg,
    selfCheck: selfCheck
  };
});
