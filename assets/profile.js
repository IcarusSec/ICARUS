/**
 * ICARUS report profile helpers (schemaVersion 1).
 * Part of ICARUS, MIT License. Runs in the browser and under Node.
 *
 * The object produced by toReportProfile() is the JSON Burp imports from
 * ICARUS → Reporting → Import (see ReportProfileCodec). The "builder" property
 * is ignored by Jackson. HTML and Markdown are layout previews.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.IcarusProfile = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  var SPEC = "icarus-template-builder/1";
  var SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"];
  var SEVERITY_ALL = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "FIXED", "NOT_FIXED"];
  var FINDING_FIELDS = ["WHY", "WHEN", "WHERE", "HOW", "IMPACT", "DESCRIPTION", "REMEDIATION", "EVIDENCE"];
  var FIELD_ORDER = ["DESCRIPTION", "WHY", "WHEN", "WHERE", "HOW", "IMPACT", "REMEDIATION", "EVIDENCE"];
  var SEVERITY_COLORS = {
    CRITICAL: "#CC2E2E",
    HIGH: "#D9711F",
    MEDIUM: "#B38F00",
    LOW: "#2F7A77",
    INFO: "#6E6E6E",
    FIXED: "#2F9E44",
    NOT_FIXED: "#CC2E2E"
  };
  var PRESETS = {
    icarus: { primary: "#FF6633", secondary: "#6E6E6E" },
    navy: { primary: "#002F6C", secondary: "#4A5568" },
    forest: { primary: "#1F4D3A", secondary: "#6B705C" }
  };
  var CATALOG = [
    { id: "COVER", kind: "cover" },
    { id: "CONFIDENTIALITY", kind: "markdown" },
    { id: "TABLE_OF_CONTENTS", kind: "toc" },
    { id: "EXECUTIVE_SUMMARY", kind: "markdown" },
    { id: "ASSESSMENT_SCOPE", kind: "markdown" },
    { id: "METHODOLOGY", kind: "markdown" },
    { id: "RISK_RATING", kind: "markdown" },
    { id: "VULNERABILITY_SUMMARY", kind: "table" },
    { id: "FINDINGS", kind: "detail" },
    { id: "REMEDIATION", kind: "markdown" },
    { id: "APPENDIX", kind: "markdown" },
    { id: "SIGNATURE", kind: "markdown" }
  ];
  var BLOCK_TYPES = ["chart", "table", "drawing", "image", "callout"];
  var STYLE_ROLES = ["coverTitle", "coverMeta", "heading", "body", "table", "findingTitle"];
  var FONT_IDS = ["helvetica", "georgia", "palatino", "segoe", "courier"];
  var TITLES = {
    en: {
      COVER: "Cover",
      CONFIDENTIALITY: "Confidentiality",
      TABLE_OF_CONTENTS: "Contents",
      EXECUTIVE_SUMMARY: "Executive summary",
      ASSESSMENT_SCOPE: "Scope",
      METHODOLOGY: "Methodology",
      RISK_RATING: "Risk rating",
      CVSS4_MATRIX: "Risk rating",
      VULNERABILITY_SUMMARY: "Findings",
      FINDINGS: "Finding detail",
      REMEDIATION: "Remediation",
      APPENDIX: "Appendix",
      SIGNATURE: "Approval",
      DOCUMENT_CONTROL: "Document control",
      REVISION_HISTORY: "Revision history",
      ACCESS_PROFILES: "Access profiles",
      TECHNICAL_REFERENCES: "Technical references"
    },
    "pt-BR": {
      COVER: "Capa",
      CONFIDENTIALITY: "Confidencialidade",
      TABLE_OF_CONTENTS: "Sumário",
      EXECUTIVE_SUMMARY: "Sumário executivo",
      ASSESSMENT_SCOPE: "Escopo",
      METHODOLOGY: "Metodologia",
      RISK_RATING: "Classificação de risco",
      CVSS4_MATRIX: "Classificação de risco",
      VULNERABILITY_SUMMARY: "Achados",
      FINDINGS: "Detalhe dos achados",
      REMEDIATION: "Remediação",
      APPENDIX: "Apêndice",
      SIGNATURE: "Aprovação",
      DOCUMENT_CONTROL: "Controle do documento",
      REVISION_HISTORY: "Histórico de revisões",
      ACCESS_PROFILES: "Perfis de acesso",
      TECHNICAL_REFERENCES: "Referências técnicas"
    }
  };
  var REPORT_TEXT = {
    en: {
      prepared: "Prepared by",
      yourLogo: "Your logo",
      sample: "Sample",
      finding: "Finding",
      severity: "Severity",
      number: "#",
      evidence: "Evidence",
      request: "Request",
      response: "Response",
      path: "Path",
      cwe: "CWE",
      footer: "Sample findings for layout review. Generate the engagement report from ICARUS. This file was built in the browser and nothing was uploaded.",
      CRITICAL: "Critical",
      HIGH: "High",
      MEDIUM: "Medium",
      LOW: "Low",
      INFO: "Info",
      FIXED: "Fixed",
      NOT_FIXED: "Not fixed",
      DESCRIPTION: "Description",
      WHY: "Why it matters",
      WHEN: "When",
      WHERE: "Where",
      HOW: "How",
      IMPACT: "Impact",
      REMEDIATION: "Remediation",
      EVIDENCE: "Evidence"
    },
    "pt-BR": {
      prepared: "Preparado por",
      yourLogo: "Sua logo",
      sample: "Exemplo",
      finding: "Achado",
      severity: "Severidade",
      number: "#",
      evidence: "Evidência",
      request: "Requisição",
      response: "Resposta",
      path: "Caminho",
      cwe: "CWE",
      footer: "Achados de exemplo para revisar o layout. O relatório do trabalho sai do ICARUS. Este arquivo foi montado no navegador e nada foi enviado.",
      CRITICAL: "Crítica",
      HIGH: "Alta",
      MEDIUM: "Média",
      LOW: "Baixa",
      INFO: "Info",
      FIXED: "Corrigida",
      NOT_FIXED: "Não corrigida",
      DESCRIPTION: "Descrição",
      WHY: "Por que importa",
      WHEN: "Quando",
      WHERE: "Onde",
      HOW: "Como",
      IMPACT: "Impacto",
      REMEDIATION: "Remediação",
      EVIDENCE: "Evidência"
    }
  };

  function pt(locale) { return locale === "pt-BR"; }

  function reportText(locale) {
    return pt(locale) ? REPORT_TEXT["pt-BR"] : REPORT_TEXT.en;
  }

  function titleFor(id, locale) {
    var pack = pt(locale) ? TITLES["pt-BR"] : TITLES.en;
    return pack[id] || humanize(id);
  }

  function humanize(id) {
    return String(id || "").toLowerCase().split("_").filter(Boolean).map(function (part) {
      return part.charAt(0).toUpperCase() + part.slice(1);
    }).join(" ");
  }

  function kindOf(id) {
    if (id === "COVER") return "cover";
    if (id === "TABLE_OF_CONTENTS") return "toc";
    if (id === "VULNERABILITY_SUMMARY") return "table";
    if (id === "FINDINGS") return "detail";
    if (String(id || "").indexOf("block:") === 0) return "block";
    return "markdown";
  }

  function isRiskId(id) {
    return id === "RISK_RATING" || id === "CVSS4_MATRIX";
  }

  function legendFor(scale, locale) {
    var br = pt(locale);
    if (scale === "simple") {
      return br
        ? "Os achados usam uma escala qualitativa. Este modelo não pede nota CVSS; o rótulo é a classificação.\n\n| Severidade | O que significa |\n| --- | --- |\n| Crítica | Risco imediato de comprometimento ou de perda relevante de dados |\n| Alta | Impacto sério; corrigir neste ciclo |\n| Média | Fraqueza real, com uma condição ou um encadeamento curto |\n| Baixa | Impacto limitado ou hardening |\n| Info | Observação ou recomendação de endurecimento |"
        : "Findings use a qualitative scale. This template does not ask for a CVSS number; the label is the rating.\n\n| Severity | What it means |\n| --- | --- |\n| Critical | Immediate risk of compromise or major data loss |\n| High | Serious impact; fix it in this cycle |\n| Medium | A real weakness that needs a precondition or a short chain |\n| Low | Limited impact or defense in depth |\n| Info | An observation or hardening advice |";
    }
    if (scale === "four") {
      return br
        ? "Este modelo usa quatro faixas. Não há uma faixa separada para Crítica: o que seria crítico entra como Alta, e o achado explica o porquê.\n\n| Severidade | O que significa |\n| --- | --- |\n| Alta | Impacto sério ou um caminho prático de exploração |\n| Média | Fraqueza real, com uma condição ou um encadeamento curto |\n| Baixa | Impacto limitado ou hardening |\n| Info | Observação ou recomendação de endurecimento |"
        : "This template uses four ratings. There is no separate Critical band: treat that case as High and explain why in the finding.\n\n| Severity | What it means |\n| --- | --- |\n| High | Serious impact or a practical exploit path |\n| Medium | A real weakness that needs a precondition or a short chain |\n| Low | Limited impact or defense in depth |\n| Info | An observation or hardening advice |";
    }
    return br
      ? "Os achados deste relatório são classificados com o Common Vulnerability Scoring System (CVSS). A tabela é a escala deste modelo. O contexto de negócio pode subir ou descer a prioridade da correção.\n\n| Severidade | Faixa CVSS | O que significa |\n| --- | --- | --- |\n| Crítica | 9.0–10.0 | Comprometimento confiável de dados sensíveis ou controle do alvo, com pouco esforço |\n| Alta | 7.0–8.9 | Impacto sério e um caminho prático de exploração |\n| Média | 4.0–6.9 | Fraqueza real que depende de uma condição ou de um encadeamento curto |\n| Baixa | 0.1–3.9 | Impacto limitado ou esforço alto para explorar |\n| Info | Sem nota | Observação ou hardening, sem uma vulnerabilidade direta |"
      : "Findings in this report are rated with the Common Vulnerability Scoring System (CVSS). The table is the scale for this template. Business context may raise or lower the priority of a fix.\n\n| Severity | CVSS range | What it means |\n| --- | --- | --- |\n| Critical | 9.0–10.0 | Reliable compromise of sensitive data or control of the target, with little effort |\n| High | 7.0–8.9 | Serious impact and a practical exploit path |\n| Medium | 4.0–6.9 | A real weakness that needs a precondition or a short chain |\n| Low | 0.1–3.9 | Limited impact or substantial effort to exploit |\n| Info | Not scored | Hardening note or observation without a direct vulnerability |";
  }

  function defaultBody(id, locale) {
    var br = pt(locale);
    var map = br ? {
      CONFIDENTIALITY: "Este documento é **{{classification}}**. Destina-se a {{requester}} e a {{owner}}. Não circule sem autorização de {{approver}} ou de {{author}}.\n\nAs evidências descrevem fraquezas reais de {{component}}. Trate o arquivo com o mesmo cuidado do ambiente **{{environment}}**.",
      TABLE_OF_CONTENTS: "",
      EXECUTIVE_SUMMARY: "Este relatório reúne o resultado da avaliação de **{{component}}**, solicitada por {{requester}} e realizada por {{author}} no ambiente **{{environment}}** ({{assessment_period}}).\n\nForam registrados **{{finding_count}}** achados: {{finding_types}}.",
      ASSESSMENT_SCOPE: "## Dentro do escopo\n\n- Alvo: {{component}}\n- Ambiente: {{environment}}\n- Período: {{assessment_period}}\n\n## Fora do escopo\n\n- Negação de serviço\n- Engenharia social\n- Sistemas de terceiros que não pertençam a {{owner}}",
      METHODOLOGY: "O trabalho seguiu o método **{{method}}**, com validação manual das falhas antes de registrá-las.\n\n1. Mapeamento\n2. Testes de autenticação, acesso e entrada\n3. Análise de impacto e recomendações",
      REMEDIATION: "## Prioridade\n\n1. Corrigir achados críticos e altos primeiro.\n2. Encaixar os médios no ciclo atual.\n3. Tratar baixos e informativos como hardening.\n\nDepois do deploy em {{environment}}, peça um reteste.",
      APPENDIX: "## Referências\n\n- OWASP Top 10\n- NIST National Vulnerability Database\n- CWE\n\nClassificação: {{classification}}. Versão {{version}}. Data: {{date}}.",
      SIGNATURE: "Preparado por {{author}}.\n\nRevisado por {{reviewer}}.\n\nAprovado por {{approver}}.\n\nData: {{date}}. Versão {{version}}. Projeto {{project}}."
    } : {
      CONFIDENTIALITY: "This document is **{{classification}}**. It is meant for {{requester}} and {{owner}}. Do not circulate it without approval from {{approver}} or {{author}}.\n\nThe evidence describes real weaknesses in {{component}}. Handle the file with the same care as the **{{environment}}** environment.",
      TABLE_OF_CONTENTS: "",
      EXECUTIVE_SUMMARY: "This report records the assessment of **{{component}}**, requested by {{requester}} and performed by {{author}} in **{{environment}}** during {{assessment_period}}.\n\n**{{finding_count}}** findings were recorded: {{finding_types}}.",
      ASSESSMENT_SCOPE: "## In scope\n\n- Target: {{component}}\n- Environment: {{environment}}\n- Window: {{assessment_period}}\n\n## Out of scope\n\n- Denial of service\n- Social engineering\n- Third-party systems not owned by {{owner}}",
      METHODOLOGY: "Work followed the **{{method}}** method. Findings were validated by hand before they were recorded.\n\n1. Mapping\n2. Authentication, access, and input testing\n3. Impact analysis and recommendations",
      REMEDIATION: "## Priority\n\n1. Fix critical and high findings first.\n2. Schedule medium findings into the current cycle.\n3. Track low and info items as hardening.\n\nAfter the fix is deployed to {{environment}}, request a retest.",
      APPENDIX: "## References\n\n- OWASP Top 10\n- NIST National Vulnerability Database\n- CWE\n\nClassification: {{classification}}. Version {{version}}. Date: {{date}}.",
      SIGNATURE: "Prepared by {{author}}.\n\nReviewed by {{reviewer}}.\n\nApproved by {{approver}}.\n\nDate: {{date}}. Version {{version}}. Project {{project}}."
    };
    return map[id] || "";
  }

  function defaultFields(locale, extra) {
    var br = pt(locale);
    var fields = {
      companyName: br ? "Segurança Exemplo" : "Example Security",
      documentTitle: br ? "Relatório de Teste de Intrusão" : "Penetration Test Report",
      author: br ? "A. Analista" : "A. Analyst",
      reviewer: br ? "Revisor" : "Reviewer",
      approver: "",
      classification: br ? "Confidencial" : "Confidential",
      environment: br ? "Homologação" : "Staging",
      assessmentPeriod: br ? "outubro de 2026" : "October 2026",
      method: br ? "Caixa cinza" : "Gray box",
      team: br ? "Time do produto" : "Product team",
      component: br ? "app.exemplo" : "app.example",
      requester: br ? "Patrocinador do projeto" : "Project sponsor",
      owner: br ? "Dono do sistema" : "System owner",
      project: br ? "Projeto Exemplo" : "Example Project",
      version: "1.0",
      date: today()
    };
    if (extra) {
      Object.keys(extra).forEach(function (key) {
        if (extra[key] != null) fields[key] = String(extra[key]);
      });
    }
    return fields;
  }

  function defaultPolicy(extra) {
    var policy = {
      includeEvidence: true,
      includeHttpRequest: true,
      includeHttpResponse: true,
      maxRequestBytes: 4096,
      maxResponseBytes: 4096,
      findingFields: FINDING_FIELDS.slice(),
      cweMode: "HARDCODED_CATALOG",
      cweAllowlist: [],
      includeTocBookmarks: true
    };
    if (!extra) return policy;
    if (typeof extra.includeEvidence === "boolean") policy.includeEvidence = extra.includeEvidence;
    if (typeof extra.includeHttpRequest === "boolean") policy.includeHttpRequest = extra.includeHttpRequest;
    if (typeof extra.includeHttpResponse === "boolean") policy.includeHttpResponse = extra.includeHttpResponse;
    if (typeof extra.includeTocBookmarks === "boolean") policy.includeTocBookmarks = extra.includeTocBookmarks;
    if (Array.isArray(extra.findingFields)) {
      policy.findingFields = FINDING_FIELDS.filter(function (name) {
        return extra.findingFields.indexOf(name) !== -1;
      });
    }
    if (extra.cweMode === "PROFILE_LIST" || extra.cweMode === "HARDCODED_CATALOG") policy.cweMode = extra.cweMode;
    if (Array.isArray(extra.cweAllowlist)) {
      policy.cweAllowlist = extra.cweAllowlist.filter(function (item) { return typeof item === "string"; });
    }
    if (isPosInt(extra.maxRequestBytes)) policy.maxRequestBytes = extra.maxRequestBytes;
    if (isPosInt(extra.maxResponseBytes)) policy.maxResponseBytes = extra.maxResponseBytes;
    return policy;
  }

  function defaultColors(preset) {
    var p = PRESETS[preset] || PRESETS.icarus;
    return {
      primary: p.primary,
      secondary: p.secondary,
      severity: Object.assign({}, SEVERITY_COLORS)
    };
  }

  function blankStyle(extra) {
    var style = {
      fontFamily: "helvetica",
      fontSize: 11,
      weight: "regular",
      italic: false,
      underline: false,
      color: "#1A1A1A",
      align: "left",
      spaceBefore: 0,
      spaceAfter: 3,
      lineHeight: 1.45
    };
    if (extra) Object.keys(extra).forEach(function (key) { style[key] = extra[key]; });
    return style;
  }

  function defaultStyles() {
    return {
      coverTitle: blankStyle({ fontSize: 26, weight: "bold", spaceAfter: 4, lineHeight: 1.15 }),
      coverMeta: blankStyle({ fontSize: 11, color: "#4A5568", spaceBefore: 1, spaceAfter: 1, lineHeight: 1.4 }),
      heading: blankStyle({ fontSize: 16, weight: "bold", spaceBefore: 1, spaceAfter: 3, lineHeight: 1.25 }),
      body: blankStyle({ fontSize: 11, align: "left" }),
      table: blankStyle({ fontSize: 10, spaceBefore: 1, spaceAfter: 3, lineHeight: 1.35 }),
      findingTitle: blankStyle({ fontSize: 13, weight: "bold", spaceBefore: 1, spaceAfter: 2, lineHeight: 1.3 })
    };
  }

  function normalizeStyle(raw) {
    var base = blankStyle();
    var src = raw && typeof raw === "object" ? raw : {};
    var family = String(src.fontFamily || base.fontFamily);
    base.fontFamily = FONT_IDS.indexOf(family) !== -1 ? family : "helvetica";
    var size = Number(src.fontSize);
    base.fontSize = size >= 8 && size <= 48 ? Math.round(size) : base.fontSize;
    base.weight = src.weight === "bold" ? "bold" : "regular";
    base.italic = !!src.italic;
    base.underline = !!src.underline;
    base.color = hex(src.color, base.color);
    var align = String(src.align || "left");
    base.align = align === "center" || align === "right" || align === "justify" ? align : "left";
    var before = Number(src.spaceBefore);
    var after = Number(src.spaceAfter);
    base.spaceBefore = before >= 0 && before <= 40 ? before : 0;
    base.spaceAfter = after >= 0 && after <= 40 ? after : 3;
    var leading = Number(src.lineHeight);
    base.lineHeight = leading >= 1 && leading <= 2.2 ? Math.round(leading * 100) / 100 : 1.45;
    return base;
  }

  function normalizeStyles(raw) {
    var styles = defaultStyles();
    var src = raw && typeof raw === "object" ? raw : {};
    STYLE_ROLES.forEach(function (role) {
      styles[role] = normalizeStyle(Object.assign({}, styles[role], src[role] || {}));
    });
    return styles;
  }

  function defaultPage() {
    return { marginTop: 16, marginRight: 16, marginBottom: 16, marginLeft: 16, showHeader: true, showFooter: true, showPageNumbers: true };
  }

  function normalizePage(raw) {
    var page = defaultPage();
    var src = raw && typeof raw === "object" ? raw : {};
    ["marginTop", "marginRight", "marginBottom", "marginLeft"].forEach(function (key) {
      var n = Number(src[key]);
      if (n >= 8 && n <= 40) page[key] = Math.round(n);
    });
    if (typeof src.showHeader === "boolean") page.showHeader = src.showHeader;
    if (typeof src.showFooter === "boolean") page.showFooter = src.showFooter;
    if (typeof src.showPageNumbers === "boolean") page.showPageNumbers = src.showPageNumbers;
    return page;
  }

  function defaultLogoBox() {
    return {
      cover: { x: 16, y: 16, w: 36, h: 14 },
      header: { show: true, align: "left", w: 24, h: 8 }
    };
  }

  function normalizeLogoBox(raw) {
    var box = defaultLogoBox();
    var src = raw && typeof raw === "object" ? raw : {};
    ["x", "y", "w", "h"].forEach(function (key) {
      var n = Number(src.cover && src.cover[key]);
      if (n >= 0 && n <= 200) box.cover[key] = Math.round(n * 10) / 10;
    });
    if (box.cover.w < 8) box.cover.w = 8;
    if (box.cover.h < 6) box.cover.h = 6;
    if (src.header && typeof src.header === "object") {
      if (typeof src.header.show === "boolean") box.header.show = src.header.show;
      if (src.header.align === "center" || src.header.align === "right") box.header.align = src.header.align;
      var w = Number(src.header.w);
      var h = Number(src.header.h);
      if (w >= 8 && w <= 80) box.header.w = Math.round(w);
      if (h >= 4 && h <= 30) box.header.h = Math.round(h);
    }
    return box;
  }

  function pdfFontName(family) {
    if (family === "georgia" || family === "palatino") return "Times";
    if (family === "courier") return "Courier";
    return "Helvetica";
  }

  function fontStack(family) {
    if (family === "georgia") return "Georgia, 'Iowan Old Style', 'Times New Roman', serif";
    if (family === "palatino") return "Palatino, 'Palatino Linotype', 'Book Antiqua', serif";
    if (family === "segoe") return "'Segoe UI', system-ui, sans-serif";
    if (family === "courier") return "'Courier New', Courier, monospace";
    return "Helvetica, Arial, sans-serif";
  }

  function mmToPt(mm) {
    return Math.round((Number(mm) || 0) * 72 / 25.4 * 10) / 10;
  }

  function defaultBlockTitle(type, locale) {
    var br = pt(locale);
    if (type === "chart") return br ? "Achados por severidade" : "Findings by severity";
    if (type === "table") return br ? "Tabela" : "Table";
    if (type === "drawing") return br ? "Diagrama" : "Diagram";
    if (type === "image") return br ? "Imagem" : "Image";
    if (type === "callout") return br ? "Nota" : "Note";
    return br ? "Bloco" : "Block";
  }

  function defaultCategories() {
    return SEVERITY_ORDER.map(function (name) {
      return { key: name, label: name.charAt(0) + name.slice(1).toLowerCase(), value: 0, color: SEVERITY_COLORS[name] };
    });
  }

  function cleanBlockType(type) {
    var value = String(type || "").toLowerCase();
    if (BLOCK_TYPES.indexOf(value) !== -1) return value;
    if (/^[a-z][a-z0-9_-]{0,30}$/.test(value)) return value;
    return "unknown";
  }

  function normalizeBlock(spec) {
    spec = spec && typeof spec === "object" ? spec : {};
    var type = cleanBlockType(spec.type || spec.blockType);
    var title = String(spec.title || "").slice(0, 180);
    if (type === "chart") {
      var chart = spec.chart === "pie" || spec.chart === "line" ? spec.chart : "bar";
      var binding = spec.binding === "static" ? "static" : "findingsBySeverity";
      var categories = Array.isArray(spec.categories) ? spec.categories.slice(0, 12).map(function (item, index) {
        var key = String(item && item.key || ("item-" + (index + 1))).slice(0, 40);
        var value = Number(item && item.value);
        return {
          key: key,
          label: String(item && item.label || key).slice(0, 40),
          value: value >= 0 && value <= 10000 ? value : 0,
          color: hex(item && item.color, SEVERITY_COLORS[key] || "#6E6E6E")
        };
      }) : [];
      if (!categories.length) categories = defaultCategories();
      return { type: "chart", chart: chart, binding: binding, title: title, categories: categories };
    }
    if (type === "table") {
      var mode = spec.mode === "static" ? "static" : "findings";
      var columns = Array.isArray(spec.columns) ? spec.columns.slice(0, 8).map(function (col, index) {
        return {
          key: String(col && col.key || ("c" + index)).slice(0, 40),
          label: String(col && col.label || col.key || ("Column " + (index + 1))).slice(0, 60)
        };
      }) : [];
      if (!columns.length) {
        columns = [
          { key: "title", label: "Finding" },
          { key: "severity", label: "Severity" },
          { key: "path", label: "Path" }
        ];
      }
      var rows = [];
      if (Array.isArray(spec.rows)) {
        rows = spec.rows.slice(0, 40).map(function (row) {
          var cells = Array.isArray(row) ? row : [];
          return columns.map(function (_, index) { return String(cells[index] == null ? "" : cells[index]).slice(0, 400); });
        });
      }
      return { type: "table", mode: mode, title: title, columns: columns, rows: rows };
    }
    if (type === "drawing") {
      var shapes = Array.isArray(spec.shapes) ? spec.shapes.slice(0, 80).map(normalizeShape).filter(Boolean) : [];
      var svg = typeof spec.svg === "string" ? spec.svg.slice(0, 80000) : "";
      var width = Number(spec.width);
      var height = Number(spec.height);
      return {
        type: "drawing",
        title: title,
        width: width >= 40 && width <= 800 ? Math.round(width) : 520,
        height: height >= 40 && height <= 600 ? Math.round(height) : 220,
        shapes: shapes,
        svg: svg
      };
    }
    if (type === "image") {
      return {
        type: "image",
        title: title,
        alt: String(spec.alt || "").slice(0, 180),
        caption: String(spec.caption || "").slice(0, 240),
        widthMm: (function () {
          var n = Number(spec.widthMm);
          return n >= 20 && n <= 170 ? Math.round(n) : 120;
        })(),
        dataUrl: sanitizeLogo(spec.dataUrl)
      };
    }
    if (type === "callout") {
      var tone = spec.tone === "warning" || spec.tone === "note" ? spec.tone : "info";
      return { type: "callout", tone: tone, title: title, text: String(spec.text || "").slice(0, 4000) };
    }
    return { type: type, title: title, unsupported: true };
  }

  function normalizeShape(shape) {
    if (!shape || typeof shape !== "object") return null;
    var type = String(shape.type || "");
    if (["rect", "ellipse", "line", "arrow", "text", "freehand"].indexOf(type) === -1) return null;
    var stroke = hex(shape.stroke, "#1A1A1A");
    var fill = shape.fill ? hex(shape.fill, "#E8EEF8") : "";
    function num(value, min, max, fallback) {
      var n = Number(value);
      if (!(n >= min && n <= max)) return fallback;
      return Math.round(n * 10) / 10;
    }
    if (type === "text") {
      return { type: "text", x: num(shape.x, 0, 800, 8), y: num(shape.y, 0, 600, 20), text: String(shape.text || "").slice(0, 160), fill: fill || "#1A1A1A", size: num(shape.size, 8, 48, 14) };
    }
    if (type === "freehand") {
      var points = Array.isArray(shape.points) ? shape.points.slice(0, 400).map(function (point) {
        return [num(point && point[0], 0, 800, 0), num(point && point[1], 0, 600, 0)];
      }) : [];
      if (points.length < 2) return null;
      return { type: "freehand", points: points, stroke: stroke };
    }
    if (type === "line" || type === "arrow") {
      return {
        type: type,
        x1: num(shape.x1, 0, 800, 8),
        y1: num(shape.y1, 0, 600, 8),
        x2: num(shape.x2, 0, 800, 80),
        y2: num(shape.y2, 0, 600, 40),
        stroke: stroke
      };
    }
    return {
      type: type,
      x: num(shape.x, 0, 800, 8),
      y: num(shape.y, 0, 600, 8),
      w: num(shape.w, 2, 800, 40),
      h: num(shape.h, 2, 600, 24),
      fill: fill,
      stroke: stroke
    };
  }

  function blockFromParams(params) {
    params = params || {};
    var parsed = {};
    if (typeof params.spec === "string" && params.spec.trim()) {
      try { parsed = JSON.parse(params.spec); } catch (err) { parsed = { type: "unknown", unsupported: true, invalid: true }; }
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) parsed = {};
    if (!parsed.type) parsed.type = params.blockType || "unknown";
    if (!parsed.title && params.title) parsed.title = params.title;
    var block = normalizeBlock(parsed);
    if (parsed.invalid) block.invalid = true;
    return block;
  }

  function makeBlock(spec) {
    spec = spec || {};
    var block = normalizeBlock(spec);
    if (spec.title) block.title = String(spec.title).slice(0, 180);
    var id = typeof spec.id === "string" && spec.id.indexOf("block:") === 0 ? spec.id : "block:" + uid().slice(5, 17);
    return {
      id: id,
      kind: "block",
      enabled: spec.enabled !== false,
      required: !!spec.required,
      rendererKey: "CUSTOM_BLOCK",
      coverStyle: "GRADIENT_HERO",
      params: { title: block.title || defaultBlockTitle(block.type), content: "" },
      block: block
    };
  }

  function tocMarkdown(state) {
    var lines = [];
    (state.sections || []).forEach(function (section) {
      if (!section || !(section.enabled || section.required)) return;
      if (section.id === "COVER" || section.id === "TABLE_OF_CONTENTS" || section.kind === "toc") return;
      lines.push("- " + sectionTitle(state, section));
    });
    return lines.join("\n");
  }

  function makeTemplate(spec) {
    spec = spec || {};
    var locale = normalizeLocale(spec.locale);
    var scale = normalizeScale(spec.severityScale);
    var enabled = Array.isArray(spec.enabled) ? spec.enabled : CATALOG.map(function (item) { return item.id; });
    var required = Array.isArray(spec.required) ? spec.required : [];
    var custom = spec.sections || {};
    var sections = CATALOG.map(function (item) {
      var fromSpec = custom[item.id] || {};
      var content = fromSpec.content;
      if (content == null) {
        content = item.id === "RISK_RATING" ? legendFor(scale, locale) : defaultBody(item.id, locale);
      }
      var isRequired = required.indexOf(item.id) !== -1 || !!fromSpec.required;
      return {
        id: item.id,
        kind: item.kind,
        enabled: isRequired || enabled.indexOf(item.id) !== -1,
        required: isRequired,
        rendererKey: item.id,
        coverStyle: spec.coverStyle === "HEADER_BAND" ? "HEADER_BAND" : "GRADIENT_HERO",
        params: {
          title: fromSpec.title || titleFor(item.id, locale),
          content: content || ""
        }
      };
    });
    return {
      templateId: spec.id || null,
      profileId: uid(),
      name: spec.name || (pt(locale) ? "Modelo personalizado" : "Custom template"),
      locale: locale,
      severityScale: scale,
      findingLayout: spec.findingLayout === "TABULAR" ? "TABULAR" : "ELEVATED_CARD",
      colors: spec.colors ? {
        primary: hex(spec.colors.primary, PRESETS.icarus.primary),
        secondary: hex(spec.colors.secondary, PRESETS.icarus.secondary),
        severity: Object.assign({}, SEVERITY_COLORS, spec.colors.severity || {})
      } : defaultColors(spec.preset || "icarus"),
      contentPolicy: defaultPolicy(spec.contentPolicy),
      fields: defaultFields(locale, spec.fields),
      sections: sections,
      styles: normalizeStyles(spec.styles),
      page: normalizePage(spec.page),
      logoBox: normalizeLogoBox(spec.logoBox),
      logoDataUrl: null,
      selectedId: "EXECUTIVE_SUMMARY"
    };
  }

  function toReportProfile(state) {
    var cover = findSection(state, "COVER");
    var coverOn = !!(cover && (cover.enabled || cover.required));
    var style = cover && cover.coverStyle === "HEADER_BAND" ? "HEADER_BAND" : "GRADIENT_HERO";
    var primary = hex(state.colors && state.colors.primary, "#FF6633");
    var secondary = hex(state.colors && state.colors.secondary, "#6E6E6E");
    var severity = {};
    SEVERITY_ALL.forEach(function (name) {
      var given = state.colors && state.colors.severity ? state.colors.severity[name] : "";
      severity[name] = hex(given, SEVERITY_COLORS[name]);
    });
    var styles = normalizeStyles(state.styles);
    var page = normalizePage(state.page);
    var nodes = [];
    (state.sections || []).forEach(function (section) {
      if (!section || section.id === "COVER") return;
      var params = {};
      var title = section.params && section.params.title ? String(section.params.title) : "";
      if (title) params.title = title;
      if (section.kind === "block" || section.rendererKey === "CUSTOM_BLOCK") {
        var block = normalizeBlock(section.block || {});
        if (!params.title) params.title = block.title || defaultBlockTitle(block.type, state.locale);
        params.blockType = block.type;
        params.spec = JSON.stringify(block);
      } else if (section.kind === "toc" || section.id === "TABLE_OF_CONTENTS") {
        params.content = tocMarkdown(state);
      } else if (kindOf(section.id) === "markdown" || section.kind === "markdown") {
        params.content = section.params && section.params.content ? String(section.params.content) : "";
      }
      nodes.push({
        id: String(section.id),
        enabled: !!(section.enabled || section.required),
        order: nodes.length + 1,
        required: !!section.required,
        rendererKey: section.kind === "block" ? "CUSTOM_BLOCK" : (section.rendererKey || section.id),
        params: params
      });
    });
    var fields = state.fields || {};
    var policy = defaultPolicy(state.contentPolicy);
    var author = String(fields.author || "").trim() || String(fields.companyName || "").trim();
    return {
      schemaVersion: "1",
      id: state.profileId || uid(),
      name: String(state.name || "Custom template").trim() || "Custom template",
      locale: normalizeLocale(state.locale),
      builtIn: false,
      basedOnId: state.templateId ? "builder:" + state.templateId : null,
      coverRenderer: coverOn ? style : "NONE",
      findingRenderer: state.findingLayout === "TABULAR" ? "TABULAR" : "ELEVATED_CARD",
      sections: { nodes: nodes },
      branding: {
        companyLogoPath: null,
        clientLogoPath: null,
        author: author,
        reviewer: str(fields.reviewer),
        approver: str(fields.approver),
        classification: str(fields.classification),
        environment: str(fields.environment),
        documentTitle: str(fields.documentTitle),
        team: str(fields.team),
        component: str(fields.component),
        requester: str(fields.requester),
        owner: str(fields.owner),
        assessmentPeriod: str(fields.assessmentPeriod),
        method: str(fields.method)
      },
      content: {
        includeEvidence: policy.includeEvidence,
        includeHttpRequest: policy.includeHttpRequest,
        includeHttpResponse: policy.includeHttpResponse,
        maxRequestBytes: policy.maxRequestBytes,
        maxResponseBytes: policy.maxResponseBytes,
        findingFields: policy.findingFields,
        cweMode: policy.cweMode,
        cweAllowlist: policy.cweAllowlist,
        includeTocBookmarks: policy.includeTocBookmarks
      },
      pdfTheme: {
        primaryHex: primary,
        secondaryHex: secondary,
        textHex: styles.body.color,
        headingHex: styles.heading.color,
        tableHeaderHex: mixWhite(primary, 0.85),
        severityHex: severity,
        fontStack: pdfFontName(styles.body.fontFamily),
        baseFontSize: Math.max(8, Math.min(16, styles.body.fontSize)),
        pageBox: {
          pageSize: "A4",
          marginTop: mmToPt(page.marginTop),
          marginBottom: mmToPt(page.marginBottom),
          marginLeft: mmToPt(page.marginLeft),
          marginRight: mmToPt(page.marginRight),
          showHeader: page.showHeader,
          showFooter: page.showFooter
        }
      },
      htmlTheme: {
        primaryHex: primary,
        secondaryHex: secondary,
        backgroundHex: "#FFFFFF",
        cardBackgroundHex: "#F7F7F7",
        textHex: styles.body.color,
        borderHex: "#DDDDDD",
        severityHex: severity,
        fontStack: fontStack(styles.body.fontFamily),
        dark: false
      },
      builder: {
        spec: SPEC,
        templateId: state.templateId || null,
        companyName: str(fields.companyName),
        logoDataUrl: sanitizeLogo(state.logoDataUrl),
        logoBox: normalizeLogoBox(state.logoBox),
        coverRequired: !!(cover && cover.required),
        styles: styles,
        page: page,
        severityScale: normalizeScale(state.severityScale),
        sectionOrder: (state.sections || []).map(function (section) { return section.id; }),
        selectedId: state.selectedId || null,
        fields: fields,
        note: "Ignored by ICARUS except where a field is also copied above. Custom blocks are section nodes with rendererKey CUSTOM_BLOCK. Import this file from ICARUS → Reporting → Import. schemaVersion 1 stays a ReportProfile. Reattach logos inside Burp; companyLogoPath is a disk path, not this preview image."
      }
    };
  }

  function fromReportProfile(data) {
    if (!data || typeof data !== "object" || !data.sections || !Array.isArray(data.sections.nodes)) {
      throw new Error("sections");
    }
    if (data.schemaVersion != null && String(data.schemaVersion) !== "1" && String(data.schemaVersion) !== "2") {
      throw new Error("schema");
    }
    var builder = data.builder && typeof data.builder === "object" ? data.builder : {};
    var locale = normalizeLocale(data.locale);
    var branding = data.branding || {};
    var fields = defaultFields(locale, {
      companyName: builder.companyName || "",
      documentTitle: branding.documentTitle || "",
      author: branding.author || "",
      reviewer: branding.reviewer || "",
      approver: branding.approver || "",
      classification: branding.classification || "",
      environment: branding.environment || "",
      assessmentPeriod: branding.assessmentPeriod || "",
      method: branding.method || "",
      team: branding.team || "",
      component: branding.component || "",
      requester: branding.requester || "",
      owner: branding.owner || ""
    });
    if (builder.fields && typeof builder.fields === "object") {
      Object.keys(fields).forEach(function (key) {
        if (builder.fields[key] != null) fields[key] = String(builder.fields[key]);
      });
    }
    if (!fields.companyName && builder.companyName) fields.companyName = String(builder.companyName);
    var theme = data.htmlTheme || data.pdfTheme || {};
    var pdf = data.pdfTheme || {};
    var severity = Object.assign({}, SEVERITY_COLORS);
    var sevSrc = theme.severityHex || pdf.severityHex || {};
    SEVERITY_ALL.forEach(function (name) {
      if (sevSrc[name]) severity[name] = hex(sevSrc[name], SEVERITY_COLORS[name]);
    });
    var coverStyle = data.coverRenderer === "HEADER_BAND" ? "HEADER_BAND" : "GRADIENT_HERO";
    var nodes = data.sections.nodes.slice().sort(function (a, b) {
      return (Number(a.order) || 0) - (Number(b.order) || 0);
    });
    var sections = [];
    nodes.forEach(function (node) {
      if (!node || !node.id || node.id === "COVER") return;
      var id = String(node.id);
      if (sections.some(function (section) { return section.id === id; })) return;
      var params = node.params && typeof node.params === "object" ? node.params : {};
      var rendererKey = typeof node.rendererKey === "string" && node.rendererKey ? node.rendererKey : id;
      var isBlock = rendererKey === "CUSTOM_BLOCK" || id.indexOf("block:") === 0;
      var block = isBlock ? blockFromParams(params) : null;
      sections.push({
        id: id,
        kind: isBlock ? "block" : kindOf(id),
        enabled: node.enabled !== false || !!node.required,
        required: !!node.required,
        rendererKey: isBlock ? "CUSTOM_BLOCK" : rendererKey,
        coverStyle: coverStyle,
        block: block,
        params: {
          title: typeof params.title === "string" && params.title ? params.title : (isBlock ? (block.title || defaultBlockTitle(block.type, locale)) : titleFor(id, locale)),
          content: isBlock ? "" : (typeof params.content === "string" ? params.content : "")
        }
      });
    });
    var cover = {
      id: "COVER",
      kind: "cover",
      enabled: data.coverRenderer !== "NONE" || !!builder.coverRequired,
      required: !!builder.coverRequired,
      rendererKey: "COVER",
      coverStyle: coverStyle,
      params: { title: titleFor("COVER", locale), content: "" }
    };
    if (Array.isArray(builder.sectionOrder) && builder.sectionOrder.length) {
      var byId = {};
      sections.forEach(function (section) { byId[section.id] = section; });
      byId.COVER = cover;
      var ordered = [];
      builder.sectionOrder.forEach(function (id) {
        if (byId[id]) {
          ordered.push(byId[id]);
          delete byId[id];
        }
      });
      Object.keys(byId).forEach(function (id) { ordered.push(byId[id]); });
      sections = ordered;
    } else {
      sections = [cover].concat(sections);
    }
    var hasRisk = sections.some(function (section) { return isRiskId(section.id); });
    var explicitOrder = Array.isArray(builder.sectionOrder) && builder.sectionOrder.length > 0;
    if (!explicitOrder) CATALOG.forEach(function (item) {
      if (item.id === "RISK_RATING" && hasRisk) return;
      if (sections.some(function (section) { return section.id === item.id; })) return;
      sections.push({
        id: item.id,
        kind: item.kind,
        enabled: false,
        required: false,
        rendererKey: item.id,
        coverStyle: coverStyle,
        params: {
          title: titleFor(item.id, locale),
          content: item.id === "RISK_RATING" ? legendFor(normalizeScale(builder.severityScale), locale) : defaultBody(item.id, locale)
        }
      });
    });
    var selected = builder.selectedId;
    if (!sections.some(function (section) { return section.id === selected; })) {
      selected = "EXECUTIVE_SUMMARY";
    }
    var scale = normalizeScale(builder.severityScale);
    if (!builder.severityScale) {
      scale = sections.some(function (section) { return section.id === "CVSS4_MATRIX"; }) ? "cvss" : scale;
    }
    return {
      templateId: builder.templateId || null,
      profileId: typeof data.id === "string" && data.id ? data.id : uid(),
      name: typeof data.name === "string" && data.name.trim() ? data.name.trim() : "Custom template",
      locale: locale,
      severityScale: scale,
      findingLayout: data.findingRenderer === "TABULAR" ? "TABULAR" : "ELEVATED_CARD",
      colors: {
        primary: hex(theme.primaryHex || pdf.primaryHex, "#FF6633"),
        secondary: hex(theme.secondaryHex || pdf.secondaryHex, "#6E6E6E"),
        severity: severity
      },
      contentPolicy: defaultPolicy(data.content || {}),
      fields: fields,
      sections: sections,
      styles: builder.styles ? normalizeStyles(builder.styles) : stylesFromTheme(theme, pdf),
      page: builder.page ? normalizePage(builder.page) : pageFromPdf(pdf.pageBox),
      logoBox: normalizeLogoBox(builder.logoBox),
      logoDataUrl: sanitizeLogo(builder.logoDataUrl),
      selectedId: selected
    };
  }

  function stylesFromTheme(theme, pdf) {
    var styles = defaultStyles();
    styles.body.color = hex(theme.textHex || pdf.textHex, styles.body.color);
    styles.heading.color = hex(theme.headingHex || pdf.headingHex, styles.heading.color);
    styles.coverTitle.color = styles.heading.color;
    var size = Number(pdf.baseFontSize);
    if (size >= 8 && size <= 16) styles.body.fontSize = Math.round(size);
    var font = String(pdf.fontStack || "");
    if (/times/i.test(font)) styles.body.fontFamily = "georgia";
    else if (/courier/i.test(font)) styles.body.fontFamily = "courier";
    else styles.body.fontFamily = "helvetica";
    STYLE_ROLES.forEach(function (role) {
      if (role !== "body") styles[role].fontFamily = styles.body.fontFamily;
      styles[role] = normalizeStyle(styles[role]);
    });
    styles.body = normalizeStyle(styles.body);
    return styles;
  }

  function pageFromPdf(box) {
    var page = defaultPage();
    if (!box || typeof box !== "object") return page;
    function mm(pt, fallback) {
      var n = Number(pt);
      if (!(n > 0)) return fallback;
      var value = Math.round(n * 25.4 / 72);
      return value >= 8 && value <= 40 ? value : fallback;
    }
    page.marginTop = mm(box.marginTop, page.marginTop);
    page.marginBottom = mm(box.marginBottom, page.marginBottom);
    page.marginLeft = mm(box.marginLeft, page.marginLeft);
    page.marginRight = mm(box.marginRight, page.marginRight);
    if (typeof box.showHeader === "boolean") page.showHeader = box.showHeader;
    if (typeof box.showFooter === "boolean") page.showFooter = box.showFooter;
    return page;
  }

  function buildVars(state) {
    var fields = state.fields || {};
    var findings = sampleFindings(state.locale);
    var types = findings.map(function (finding) { return finding.type; });
    var unique = types.filter(function (type, index) { return types.indexOf(type) === index; });
    return {
      author: safeValue(fields.author),
      reviewer: safeValue(fields.reviewer),
      approver: safeValue(fields.approver),
      classification: safeValue(fields.classification),
      environment: safeValue(fields.environment),
      assessment_period: safeValue(fields.assessmentPeriod),
      method: safeValue(fields.method),
      team: safeValue(fields.team),
      component: safeValue(fields.component),
      requester: safeValue(fields.requester),
      owner: safeValue(fields.owner),
      project: safeValue(fields.project),
      version: safeValue(fields.version),
      date: safeValue(fields.date),
      report_title: safeValue(fields.documentTitle),
      finding_count: String(findings.length),
      finding_types: unique.join(", ")
    };
  }

  function interpolate(text, vars) {
    return String(text == null ? "" : text).replace(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g, function (match, key) {
      var lower = key.toLowerCase();
      if (Object.prototype.hasOwnProperty.call(vars, lower)) return vars[lower];
      return match;
    });
  }

  function renderReport(state, opts) {
    opts = opts || {};
    var docLevel = opts.level === 1 ? 1 : 2;
    var sectionLevel = Math.min(6, docLevel + 1);
    var text = reportText(state.locale);
    var primary = hex(state.colors && state.colors.primary, "#FF6633");
    var parts = [];
    var countsDone = false;
    (state.sections || []).forEach(function (section) {
      if (!section || !(section.enabled || section.required)) return;
      if (!countsDone && (section.kind === "table" || section.kind === "detail" || section.id === "VULNERABILITY_SUMMARY" || section.id === "FINDINGS")) {
        parts.push(countsHtml(state, text));
        countsDone = true;
      }
      if (section.kind === "cover" || section.id === "COVER") {
        parts.push(coverHtml(state, docLevel, text));
      } else if (section.kind === "block" || section.rendererKey === "CUSTOM_BLOCK") {
        parts.push(blockHtml(state, section, sectionLevel));
      } else if (section.kind === "table" || section.id === "VULNERABILITY_SUMMARY") {
        parts.push(tableHtml(state, section, sectionLevel, text));
      } else if (section.kind === "detail" || section.id === "FINDINGS") {
        parts.push(detailHtml(state, section, sectionLevel, text));
      } else if (section.kind === "toc" || section.id === "TABLE_OF_CONTENTS") {
        parts.push(tocHtml(state, section, sectionLevel));
      } else {
        var body = section.params && section.params.content ? String(section.params.content) : "";
        if (!body.trim()) return;
        var heading = tag(sectionLevel);
        parts.push(
          '<section class="block"><' + heading + ' class="section-title">' + esc(sectionTitle(state, section)) + "</" + heading + ">" +
          '<div class="md">' + renderMarkdown(interpolate(body, buildVars(state)), sectionLevel) + "</div></section>"
        );
      }
    });
    parts.push('<p class="report-foot">' + esc(text.footer) + "</p>");
    return '<div class="report" lang="' + esc(normalizeLocale(state.locale)) + '" style="--report-accent:' + primary + '">' + parts.join("") + "</div>";
  }

  function toHtmlDocument(state) {
    var title = (state.fields && state.fields.documentTitle) || state.name || "Report";
    return "<!DOCTYPE html>\n<html lang=\"" + esc(normalizeLocale(state.locale)) + "\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>" +
      esc(title) + "</title>\n<style>\nbody{margin:0;background:#fff;color:#1a1a1a}\n" + reportCss() + "\n.report{max-width:820px;margin:0 auto;padding:1.5rem 1.25rem 3rem}\n</style>\n</head>\n<body>\n" +
      renderReport(state, { level: 1 }) + "\n</body>\n</html>\n";
  }

  function toMarkdown(state) {
    var fields = state.fields || {};
    var text = reportText(state.locale);
    var vars = buildVars(state);
    var lines = [];
    lines.push("# " + (fields.documentTitle || state.name || "Report"));
    lines.push("");
    if (fields.companyName) lines.push("*" + text.prepared + " " + fields.companyName + "*");
    if (fields.project) lines.push("**" + (pt(state.locale) ? "Projeto" : "Project") + ":** " + fields.project);
    if (fields.classification) lines.push("**" + (pt(state.locale) ? "Classificação" : "Classification") + ":** " + fields.classification);
    var logo = sanitizeLogo(state.logoDataUrl);
    if (logo) {
      lines.push("");
      lines.push("![" + (fields.companyName || "logo") + "](" + logo + ")");
    }
    lines.push("");
    (state.sections || []).forEach(function (section) {
      if (!section || section.id === "COVER" || !(section.enabled || section.required)) return;
      if ((section.kind === "table" || section.id === "VULNERABILITY_SUMMARY")) {
        lines.push("## " + sectionTitle(state, section));
        lines.push("");
        lines.push("| " + text.number + " | " + text.finding + " | " + text.severity + " |");
        lines.push("| --- | --- | --- |");
        sampleFindings(state.locale).forEach(function (finding, index) {
          lines.push("| " + (index + 1) + " | " + mdCell(finding.title) + " | " + mdCell(text[finding.severity] || finding.severity) + " |");
        });
        lines.push("");
        return;
      }
      if (section.kind === "detail" || section.id === "FINDINGS") {
        lines.push("## " + sectionTitle(state, section));
        lines.push("");
        sampleFindings(state.locale).forEach(function (finding, index) {
          lines.push("### " + (index + 1) + ". " + finding.title);
          lines.push("");
          lines.push("**" + text.severity + ":** " + (text[finding.severity] || finding.severity));
          lines.push("");
          FIELD_ORDER.forEach(function (field) {
            if (!fieldOn(state, field)) return;
            var value = finding[field.toLowerCase()] || finding[field] || "";
            if (field === "DESCRIPTION") value = finding.description;
            if (field === "EVIDENCE") value = finding.evidence;
            if (!value) return;
            lines.push("**" + (text[field] || field) + ":** " + value);
            lines.push("");
          });
        });
        return;
      }
      var body = section.params && section.params.content ? String(section.params.content).trim() : "";
      if (section.kind === "block" || section.rendererKey === "CUSTOM_BLOCK") {
        lines.push("## " + sectionTitle(state, section));
        lines.push("");
        lines.push(blockMarkdown(section));
        lines.push("");
        return;
      }
      if (section.kind === "toc" || section.id === "TABLE_OF_CONTENTS") {
        lines.push("## " + sectionTitle(state, section));
        lines.push("");
        lines.push(tocMarkdown(state));
        lines.push("");
        return;
      }
      if (!body) return;
      lines.push("## " + sectionTitle(state, section));
      lines.push("");
      lines.push(interpolate(body, vars));
      lines.push("");
    });
    lines.push("---");
    lines.push("");
    lines.push(text.footer);
    lines.push("");
    return lines.join("\n");
  }

  function styleCss(style) {
    var s = normalizeStyle(style);
    return "font-family:" + fontStack(s.fontFamily) +
      ";font-size:" + s.fontSize + "pt;font-weight:" + (s.weight === "bold" ? "700" : "400") +
      ";font-style:" + (s.italic ? "italic" : "normal") +
      ";text-decoration:" + (s.underline ? "underline" : "none") +
      ";color:" + s.color + ";text-align:" + s.align +
      ";line-height:" + s.lineHeight +
      ";margin-top:" + s.spaceBefore + "mm;margin-bottom:" + s.spaceAfter + "mm";
  }

  function severityCounts(state) {
    var counts = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
    sampleFindings(state && state.locale).forEach(function (finding) {
      if (counts[finding.severity] != null) counts[finding.severity] += 1;
    });
    return counts;
  }

  function tocHtml(state, section, level) {
    var heading = tag(level);
    var items = [];
    (state.sections || []).forEach(function (item) {
      if (!item || !(item.enabled || item.required)) return;
      if (item.id === "COVER" || item.id === section.id) return;
      items.push("<li>" + esc(sectionTitle(state, item)) + "</li>");
    });
    return '<section class="block toc" data-section="' + esc(section.id) + '"><' + heading +
      ' class="section-title" data-role="heading" data-section="' + esc(section.id) + '">' +
      esc(sectionTitle(state, section)) + "</" + heading + '><ol class="toc-list" data-role="body" data-section="' +
      esc(section.id) + '">' + items.join("") + "</ol></section>";
  }

  function blockHtml(state, section, level) {
    var heading = tag(level);
    var inner = "";
    var api = root && root.IcarusBlocks;
    if (api && typeof api.renderInner === "function") inner = api.renderInner(section, state);
    else inner = '<p class="block-note">' + esc(sectionTitle(state, section)) + "</p>";
    return '<section class="block custom-block" data-role="block" data-section="' + esc(section.id) + '"><' + heading +
      ' class="section-title" data-role="heading" data-section="' + esc(section.id) + '">' +
      esc(sectionTitle(state, section)) + "</" + heading + ">" + inner + "</section>";
  }

  function blockMarkdown(section) {
    var block = section.block || blockFromParams(section.params || {});
    if (BLOCK_TYPES.indexOf(block.type) === -1) {
      return "_Unsupported block (" + String(block.type || "unknown").replace(/[^a-z0-9_-]/gi, "") + ") omitted._";
    }
    if (block.invalid) return "_This block could not be read._";
    if (block.type === "callout") return "> " + String(block.text || "").replace(/\n/g, "\n> ");
    if (block.type === "chart") {
      return "_Chart (" + block.chart + (block.binding === "findingsBySeverity" ? ", findings by severity" : ", static values") + ")._";
    }
    if (block.type === "image") {
      return block.dataUrl ? "![" + mdCell(block.alt || block.title || "image") + "](" + block.dataUrl + ")" : "_Image_";
    }
    if (block.type === "drawing") return "_Diagram._";
    var head = "| " + block.columns.map(function (col) { return mdCell(col.label); }).join(" | ") + " |";
    var rule = "| " + block.columns.map(function () { return "---"; }).join(" | ") + " |";
    var rows = (block.rows || []).map(function (row) {
      return "| " + block.columns.map(function (_, index) { return mdCell(row[index]); }).join(" | ") + " |";
    });
    return [head, rule].concat(rows).join("\n");
  }

  function coverHtml(state, level, text) {
    var fields = state.fields || {};
    var cover = findSection(state, "COVER");
    var primary = hex(state.colors.primary, "#FF6633");
    var secondary = hex(state.colors.secondary, "#6E6E6E");
    var heading = tag(level);
    var title = esc(fields.documentTitle || state.name || "Report");
    var company = str(fields.companyName);
    var logo = logoImg(state, company || text.yourLogo);
    var project = fields.project ? '<p class="project">' + esc(fields.project) + "</p>" : "";
    var byline = company ? '<p class="byline">' + esc(text.prepared) + " " + esc(company) + "</p>" : "";
    var klass = fields.classification ? '<p class="classif"><span>' + esc(fields.classification) + "</span></p>" : "";
    if (cover && cover.coverStyle === "HEADER_BAND") {
      return '<header class="cover cover-band"><div class="band" style="background:' + primary + '">' +
        logo + "<" + heading + ' class="doc-title">' + title + "</" + heading + ">" + project + byline +
        "</div>" + klass + "</header>";
    }
    var mark = logo || '<span class="logo-fallback">' + esc(company || text.yourLogo) + "</span>";
    return '<header class="cover cover-hero" style="background:linear-gradient(135deg,' + primary + " 0%," + secondary + ' 100%)">' +
      '<div class="logo-card">' + mark + "</div><" + heading + ' class="doc-title">' + title + "</" + heading + ">" +
      project + byline + klass + "</header>";
  }

  function countsHtml(state, text) {
    var counts = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
    sampleFindings(state.locale).forEach(function (finding) {
      if (counts[finding.severity] != null) counts[finding.severity] += 1;
    });
    var items = SEVERITY_ORDER.map(function (name) {
      var color = hex(state.colors.severity && state.colors.severity[name], SEVERITY_COLORS[name]);
      return '<li><span class="count-pill" style="background:' + color + ";color:" + inkOn(color) + '">' + counts[name] +
        '</span><span class="count-label">' + esc(text[name] || name) + "</span></li>";
    }).join("");
    return '<ul class="counts" aria-label="' + esc(text.severity) + '">' + items + "</ul>";
  }

  function tableHtml(state, section, level, text) {
    var heading = tag(level);
    var rows = sampleFindings(state.locale).map(function (finding, index) {
      var color = hex(state.colors.severity && state.colors.severity[finding.severity], "#666666");
      return "<tr><td>" + (index + 1) + "</td><td>" + esc(finding.title) + '</td><td><span class="sev" style="background:' +
        color + ";color:" + inkOn(color) + '">' + esc(text[finding.severity] || finding.severity) + "</span></td></tr>";
    }).join("");
    return '<section class="block"><' + heading + ' class="section-title">' + esc(sectionTitle(state, section)) +
      "</" + heading + '><p class="sample-flag">' + esc(text.sample) + "</p><div class=\"table-wrap\"><table><thead><tr><th>" +
      esc(text.number) + "</th><th>" + esc(text.finding) + "</th><th>" + esc(text.severity) + "</th></tr></thead><tbody>" +
      rows + "</tbody></table></div></section>";
  }

  function detailHtml(state, section, level, text) {
    var heading = tag(level);
    var cards = state.findingLayout === "TABULAR";
    var blocks = sampleFindings(state.locale).map(function (finding, index) {
      return cards ? tabularFinding(state, finding, index, text) : cardFinding(state, finding, index, text);
    }).join("");
    return '<section class="block"><' + heading + ' class="section-title">' + esc(sectionTitle(state, section)) +
      "</" + heading + '><p class="sample-flag">' + esc(text.sample) + "</p>" + blocks + "</section>";
  }

  function cardFinding(state, finding, index, text) {
    var color = hex(state.colors.severity && state.colors.severity[finding.severity], "#666666");
    var meta = '<p class="meta">' + esc(text.cwe) + ": " + esc(finding.cwe) + " · " + esc(text.path) + ": " + esc(finding.path) + "</p>";
    return '<article class="finding-card" style="border-left-color:' + color + '"><div class="finding-head"><h' +
      childLevel(3) + ">" + (index + 1) + ". " + esc(finding.title) + "</h" + childLevel(3) + '><span class="sev" style="background:' +
      color + ";color:" + inkOn(color) + '">' + esc(text[finding.severity] || finding.severity) + "</span></div>" +
      meta + fieldBlocks(state, finding, text) + transportBlocks(state, finding, text) + "</article>";
  }

  function tabularFinding(state, finding, index, text) {
    var color = hex(state.colors.severity && state.colors.severity[finding.severity], "#666666");
    var rows = "<tr><th>" + esc(text.finding) + "</th><td>" + (index + 1) + ". " + esc(finding.title) + "</td></tr>" +
      "<tr><th>" + esc(text.severity) + '</th><td><span class="sev" style="background:' + color + ";color:" + inkOn(color) + '">' +
      esc(text[finding.severity] || finding.severity) + "</span></td></tr>" +
      "<tr><th>" + esc(text.cwe) + "</th><td>" + esc(finding.cwe) + "</td></tr>" +
      "<tr><th>" + esc(text.path) + "</th><td>" + esc(finding.path) + "</td></tr>";
    FIELD_ORDER.forEach(function (field) {
      var value = fieldValue(finding, field);
      if (!value || !fieldOn(state, field)) return;
      if (field === "EVIDENCE") return;
      rows += "<tr><th>" + esc(text[field] || field) + "</th><td>" + esc(value) + "</td></tr>";
    });
    if (fieldOn(state, "EVIDENCE") && state.contentPolicy.includeEvidence) {
      rows += "<tr><th>" + esc(text.EVIDENCE) + '</th><td><div class="evidence-slot">' + esc(finding.evidence) + "</div></td></tr>";
    }
    if (state.contentPolicy.includeHttpRequest && finding.request) {
      rows += "<tr><th>" + esc(text.request) + "</th><td><pre>" + esc(finding.request) + "</pre></td></tr>";
    }
    if (state.contentPolicy.includeHttpResponse && finding.response) {
      rows += "<tr><th>" + esc(text.response) + "</th><td><pre>" + esc(finding.response) + "</pre></td></tr>";
    }
    return '<div class="table-wrap"><table class="finding-table">' + rows + "</table></div>";
  }

  function fieldBlocks(state, finding, text) {
    return FIELD_ORDER.map(function (field) {
      var value = fieldValue(finding, field);
      if (!value || !fieldOn(state, field)) return "";
      if (field === "EVIDENCE") {
        if (!state.contentPolicy.includeEvidence) return "";
        return '<h4>' + esc(text.EVIDENCE) + '</h4><div class="evidence-slot">' + esc(value) + "</div>";
      }
      return "<h4>" + esc(text[field] || field) + "</h4><p>" + esc(value) + "</p>";
    }).join("");
  }

  function transportBlocks(state, finding, text) {
    var html = "";
    if (state.contentPolicy.includeHttpRequest && finding.request) {
      html += "<h4>" + esc(text.request) + "</h4><pre>" + esc(finding.request) + "</pre>";
    }
    if (state.contentPolicy.includeHttpResponse && finding.response) {
      html += "<h4>" + esc(text.response) + "</h4><pre>" + esc(finding.response) + "</pre>";
    }
    return html;
  }

  function fieldValue(finding, field) {
    if (field === "DESCRIPTION") return finding.description || "";
    if (field === "EVIDENCE") return finding.evidence || "";
    return finding[field.toLowerCase()] || "";
  }

  function fieldOn(state, field) {
    var list = state.contentPolicy && state.contentPolicy.findingFields;
    if (!Array.isArray(list)) return true;
    return list.indexOf(field) !== -1;
  }

  function childLevel(n) { return n; }

  function sampleFindings(locale) {
    if (pt(locale)) {
      return [
        {
          type: "injeção de SQL",
          title: "Injeção de SQL no parâmetro de busca",
          severity: "HIGH",
          cwe: "CWE-89",
          path: "/busca",
          description: "O parâmetro q é concatenado na consulta. Uma aspa simples altera a resposta da aplicação.",
          why: "Um atacante autenticado ou anônimo pode ler dados além do resultado da busca.",
          when: "Durante o teste autenticado do fluxo de busca.",
          where: "GET /busca?q=",
          how: "A entrada não passa por consulta parametrizada antes de chegar ao banco.",
          impact: "Exposição de registros de outras contas no ambiente de homologação.",
          remediation: "Usar consultas parametrizadas e rejeitar erro detalhado de banco para o cliente.",
          evidence: "Captura de tela da resposta com erro de sintaxe SQL.",
          request: "GET /busca?q=teste'+OR+1%3D1-- HTTP/1.1\nHost: app.exemplo\nAccept: text/html",
          response: "HTTP/1.1 500 Internal Server Error\nContent-Type: text/html\n\nSQL syntax error near 'OR'"
        },
        {
          type: "cabeçalhos de segurança ausentes",
          title: "Cabeçalhos de segurança HTTP ausentes",
          severity: "LOW",
          cwe: "CWE-693",
          path: "/",
          description: "A resposta não envia Content-Security-Policy nem X-Content-Type-Options.",
          why: "O navegador fica sem uma instrução explícita contra conteúdo ativo inesperado.",
          when: "Na revisão passiva das respostas da página inicial.",
          where: "Todas as respostas HTML observadas.",
          how: "O servidor não adiciona os cabeçalhos na camada de borda.",
          impact: "O impacto isolado é baixo; o efeito cresce se surgir um XSS.",
          remediation: "Definir os cabeçalhos no proxy ou na aplicação, com uma CSP compatível com o front-end.",
          evidence: "Cabeçalhos da resposta da página inicial.",
          request: "",
          response: ""
        },
        {
          type: "erros verbosos",
          title: "Mensagens de erro verbosas",
          severity: "INFO",
          cwe: "CWE-209",
          path: "/api/pedido",
          description: "Um identificador inválido devolve o nome da classe e o caminho interno do código.",
          why: "O detalhe ajuda a mapear a pilha e as versões usadas.",
          when: "Ao enviar um identificador fora do formato esperado.",
          where: "POST /api/pedido",
          how: "A exceção chega ao cliente sem um tratador genérico.",
          impact: "Não há acesso extra por si só. A informação reduz o esforço de outros testes.",
          remediation: "Responder com uma mensagem genérica e registrar o detalhe só no servidor.",
          evidence: "Corpo da resposta 500 com stack trace.",
          request: "",
          response: ""
        }
      ];
    }
    return [
      {
        type: "SQL injection",
        title: "SQL injection in the search parameter",
        severity: "HIGH",
        cwe: "CWE-89",
        path: "/search",
        description: "The q parameter is concatenated into the query. A single quote changes the application response.",
        why: "An attacker can read rows beyond the search they were given.",
        when: "During authenticated testing of the search flow.",
        where: "GET /search?q=",
        how: "Input reaches the database without a parameterized query.",
        impact: "Records belonging to other accounts were returned in staging.",
        remediation: "Use parameterized queries and stop returning database errors to the client.",
        evidence: "Screenshot of the response showing a SQL syntax error.",
        request: "GET /search?q=test'+OR+1%3D1-- HTTP/1.1\nHost: app.example\nAccept: text/html",
        response: "HTTP/1.1 500 Internal Server Error\nContent-Type: text/html\n\nSQL syntax error near 'OR'"
      },
      {
        type: "missing security headers",
        title: "Missing HTTP security headers",
        severity: "LOW",
        cwe: "CWE-693",
        path: "/",
        description: "Responses do not send Content-Security-Policy or X-Content-Type-Options.",
        why: "The browser is given no explicit instruction against unexpected active content.",
        when: "While reviewing responses from the home page.",
        where: "Every HTML response observed.",
        how: "The edge server does not add the headers.",
        impact: "Low on its own. The effect grows if a cross-site scripting bug appears.",
        remediation: "Set the headers on the proxy or the application, with a CSP the front end can live with.",
        evidence: "Response headers from the home page.",
        request: "",
        response: ""
      },
      {
        type: "verbose errors",
        title: "Verbose error messages",
        severity: "INFO",
        cwe: "CWE-209",
        path: "/api/order",
        description: "An invalid identifier returns the class name and an internal source path.",
        why: "The detail makes it easier to map the stack and the versions in use.",
        when: "After sending an identifier in the wrong format.",
        where: "POST /api/order",
        how: "The exception reaches the client without a generic handler.",
        impact: "No extra access by itself. The information shortens other tests.",
        remediation: "Return a generic message and keep the detail in server logs.",
        evidence: "Body of the 500 response with a stack trace.",
        request: "",
        response: ""
      }
    ];
  }

  function renderMarkdown(src, sectionLevel) {
    var lines = String(src || "").replace(/\r\n/g, "\n").split("\n");
    var out = [];
    var i = 0;
    while (i < lines.length) {
      var line = lines[i];
      if (!line.trim()) { i++; continue; }
      if (line.trim().indexOf("```") === 0) {
        var buf = [];
        i++;
        while (i < lines.length && lines[i].trim().indexOf("```") !== 0) {
          buf.push(lines[i]);
          i++;
        }
        if (i < lines.length) i++;
        out.push("<pre><code>" + esc(buf.join("\n")) + "</code></pre>");
        continue;
      }
      var heading = /^(#{1,3})\s+(.*)$/.exec(line);
      if (heading) {
        var h = Math.min(6, sectionLevel + heading[1].length);
        out.push("<" + tag(h) + ">" + inline(heading[2]) + "</" + tag(h) + ">");
        i++;
        continue;
      }
      if (line.indexOf("|") !== -1 && i + 1 < lines.length && isTableSep(lines[i + 1])) {
        var headers = splitRow(line);
        i += 2;
        var rows = [];
        while (i < lines.length && lines[i].indexOf("|") !== -1 && lines[i].trim()) {
          rows.push(splitRow(lines[i]));
          i++;
        }
        var html = "<div class=\"table-wrap\"><table><thead><tr>" + headers.map(function (cell) {
          return "<th>" + inline(cell) + "</th>";
        }).join("") + "</tr></thead><tbody>";
        rows.forEach(function (row) {
          html += "<tr>" + headers.map(function (_, index) {
            return "<td>" + inline(row[index] || "") + "</td>";
          }).join("") + "</tr>";
        });
        html += "</tbody></table></div>";
        out.push(html);
        continue;
      }
      if (/^\s*[-*]\s+/.test(line)) {
        var items = [];
        while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
          items.push(lines[i].replace(/^\s*[-*]\s+/, ""));
          i++;
        }
        out.push("<ul>" + items.map(function (item) { return "<li>" + inline(item) + "</li>"; }).join("") + "</ul>");
        continue;
      }
      if (/^\s*\d+\.\s+/.test(line)) {
        var steps = [];
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          steps.push(lines[i].replace(/^\s*\d+\.\s+/, ""));
          i++;
        }
        out.push("<ol>" + steps.map(function (item) { return "<li>" + inline(item) + "</li>"; }).join("") + "</ol>");
        continue;
      }
      if (/^\s*>\s?/.test(line)) {
        var quote = [];
        while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
          quote.push(lines[i].replace(/^\s*>\s?/, ""));
          i++;
        }
        out.push("<blockquote><p>" + inline(quote.join(" ")) + "</p></blockquote>");
        continue;
      }
      var para = [line];
      i++;
      while (i < lines.length && lines[i].trim() && !isBlockStart(lines, i)) {
        para.push(lines[i]);
        i++;
      }
      out.push("<p>" + inline(para.join(" ")) + "</p>");
    }
    return out.join("");
  }

  function isBlockStart(lines, index) {
    var line = lines[index];
    if (/^(#{1,3})\s+/.test(line)) return true;
    if (line.trim().indexOf("```") === 0) return true;
    if (/^\s*[-*]\s+/.test(line)) return true;
    if (/^\s*\d+\.\s+/.test(line)) return true;
    if (/^\s*>\s?/.test(line)) return true;
    if (line.indexOf("|") !== -1 && index + 1 < lines.length && isTableSep(lines[index + 1])) return true;
    return false;
  }

  function isTableSep(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
  }

  function splitRow(line) {
    var s = String(line || "").trim();
    if (s.charAt(0) === "|") s = s.slice(1);
    if (s.charAt(s.length - 1) === "|") s = s.slice(0, -1);
    return s.split("|").map(function (cell) { return cell.trim(); });
  }

  function inline(raw) {
    var s = String(raw || "");
    var re = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\)|\*[^*\n]+\*)/g;
    var out = "";
    var last = 0;
    var match;
    while ((match = re.exec(s))) {
      out += esc(s.slice(last, match.index));
      var token = match[1];
      if (token.indexOf("**") === 0) out += "<strong>" + esc(token.slice(2, -2)) + "</strong>";
      else if (token.charAt(0) === "`") out += "<code>" + esc(token.slice(1, -1)) + "</code>";
      else if (token.charAt(0) === "[") {
        var link = /^\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)$/.exec(token);
        if (link && isSafeHttp(link[2])) {
          out += '<a href="' + esc(link[2]) + '" rel="noopener noreferrer">' + esc(link[1]) + "</a>";
        } else out += esc(token);
      } else if (token.charAt(0) === "*") out += "<em>" + esc(token.slice(1, -1)) + "</em>";
      else out += esc(token);
      last = match.index + token.length;
    }
    out += esc(s.slice(last));
    return out;
  }

  function reportCss() {
    return [
      ".report{color:#1c1915;font-family:'Segoe UI',system-ui,sans-serif;line-height:1.55;font-size:15.5px}",
      ".report *{box-sizing:border-box}",
      ".report .doc-title{font-family:Georgia,'Iowan Old Style','Palatino Linotype',Palatino,serif;font-weight:700;line-height:1.15;margin:0.2rem 0 0.35rem;font-size:2rem}",
      ".report .cover-hero{color:#fff;padding:2.2rem 1.4rem 1.7rem;border-radius:14px;text-align:center}",
      ".report .cover-hero .doc-title,.report .cover-hero .project,.report .cover-hero .byline{color:#fff}",
      ".report .logo-card{background:#fff;color:#1c1915;display:inline-flex;align-items:center;justify-content:center;min-width:10rem;min-height:4.2rem;padding:0.7rem 1rem;border-radius:12px;margin-bottom:0.9rem}",
      ".report .logo{max-height:64px;max-width:220px;height:auto}",
      ".report .logo-fallback{font-weight:700;letter-spacing:0.01em}",
      ".report .cover-band .band{color:#fff;padding:1.4rem 1.3rem 1.2rem;border-radius:12px}",
      ".report .cover-band .doc-title,.report .cover-band .project,.report .cover-band .byline{color:#fff}",
      ".report .project{margin:0.15rem 0;font-size:1.05rem}",
      ".report .byline{margin:0.2rem 0 0;font-size:0.95rem}",
      ".report .classif{margin:0.8rem 0 0;text-align:center}",
      ".report .classif span{display:inline-block;letter-spacing:0.08em;text-transform:uppercase;font-size:0.72rem;font-weight:700}",
      ".report .cover-hero .classif span{background:rgba(255,255,255,0.18);padding:0.25rem 0.7rem;border-radius:999px}",
      ".report .counts{display:flex;gap:0.5rem;list-style:none;padding:0;margin:1rem 0 0.4rem;flex-wrap:wrap}",
      ".report .counts li{flex:1 1 5.2rem;background:#f6f5f3;border:1px solid #e4e0d8;border-radius:10px;text-align:center;padding:0.65rem 0.3rem}",
      ".report .count-pill,.report .sev{display:inline-block;min-width:1.7rem;padding:0.08rem 0.5rem;border-radius:999px;font-weight:700;font-size:0.78rem;line-height:1.4}",
      ".report .count-label{display:block;font-size:0.75rem;margin-top:0.28rem;color:#3f3a34}",
      ".report .section-title{margin:1.45rem 0 0.45rem;padding-bottom:0.28rem;border-bottom:2px solid var(--report-accent,#c2410c);font-size:1.35rem;line-height:1.25}",
      ".report h3,.report h4,.report h5{font-size:1.02rem;margin:0.9rem 0 0.3rem;line-height:1.3}",
      ".report p{margin:0.45rem 0}",
      ".report ul,.report ol{margin:0.4rem 0 0.7rem;padding-left:1.2rem}",
      ".report table{width:100%;border-collapse:collapse;margin:0.6rem 0 1rem;font-size:0.92rem}",
      ".report th,.report td{border:1px solid #e0dbd3;padding:0.42rem 0.55rem;text-align:left;vertical-align:top}",
      ".report th{background:#f4f1ea;font-weight:650}",
      ".report .table-wrap{overflow-x:auto}",
      ".report .finding-card{border:1px solid #e6e1d8;border-left-width:6px;border-radius:10px;padding:0.85rem 1rem 0.4rem;margin:0.8rem 0;background:#fbfaf7;break-inside:avoid}",
      ".report .finding-head{display:flex;justify-content:space-between;gap:0.75rem;align-items:flex-start}",
      ".report .finding-head h3{margin:0}",
      ".report .meta{color:#5c564c;font-size:0.88rem}",
      ".report pre{background:#f3f0ea;padding:0.7rem 0.8rem;overflow:auto;font-size:0.8rem;border-radius:8px;white-space:pre-wrap}",
      ".report code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:0.88em}",
      ".report .evidence-slot{border:1px dashed #b9b2a6;padding:0.85rem;text-align:center;color:#5c564c;border-radius:8px;background:#fff}",
      ".report blockquote{margin:0.7rem 0;padding:0.1rem 0 0.1rem 0.85rem;border-left:3px solid var(--report-accent,#c2410c)}",
      ".report a{color:#9a3412}",
      ".report .sample-flag{display:inline-block;margin:0 0 0.35rem;padding:0.05rem 0.45rem;border-radius:999px;background:#f4e3d4;color:#7c2d12;font-size:0.72rem;font-weight:700;letter-spacing:0.04em;text-transform:uppercase}",
      ".report .report-foot{margin-top:1.8rem;padding-top:0.7rem;border-top:1px solid #e0dbd3;color:#5c564c;font-size:0.8rem}",
      ".report .custom-block svg{width:100%;height:auto;display:block}",
      ".report .callout{border:1px solid #d7e2f5;border-left-width:4px;border-radius:8px;padding:0.7rem 0.85rem;background:#f5f8fd;margin:0.4rem 0 0.8rem}",
      ".report .callout.warning{border-color:#f0d7a6;background:#fff8ee}",
      ".report .callout.note{border-color:#e4e0d8;background:#faf9f6}",
      ".report .block-note{color:#5c564c;font-size:0.85rem}",
      ".report .toc-list{margin:0.3rem 0 0.8rem;padding-left:1.2rem}",
      ".report .diagram{width:100%;background:#fbfbfc;border:1px solid #e6e8ee;border-radius:8px}",
      "@page{size:A4;margin:14mm}",
      "@media print{.report .cover-hero,.report .cover-band .band,.report .sev,.report .count-pill,.report th{print-color-adjust:exact;-webkit-print-color-adjust:exact}}"
    ].join("\n");
  }

  function mdCell(value) {
    return String(value == null ? "" : value).replace(/\|/g, "/").replace(/\n/g, " ");
  }

  function sectionTitle(state, section) {
    var title = section.params && section.params.title ? String(section.params.title).trim() : "";
    return title || titleFor(section.id, state.locale);
  }

  function findSection(state, id) {
    return (state.sections || []).filter(function (section) { return section.id === id; })[0] || null;
  }

  function logoImg(state, alt) {
    var src = sanitizeLogo(state.logoDataUrl);
    if (!src) return "";
    return '<img class="logo" alt="' + esc(alt || "Logo") + '" src="' + src + '">';
  }

  function sanitizeLogo(url) {
    if (typeof url !== "string") return null;
    if (url.length > 700000) return null;
    if (!/^data:image\/(png|jpeg|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(url)) return null;
    return url.replace(/\s+/g, "");
  }

  function isPublicUrl(url) {
    if (typeof url !== "string") return false;
    var value = url.trim();
    if (!/^https:\/\/[^\s]+$/i.test(value)) return false;
    if (/YOUR_HANDLE|CHANGE_ME|PLACEHOLDER|example\.com/i.test(value)) return false;
    return true;
  }

  function isSafeHttp(url) {
    return /^https?:\/\/[^\s"'<>]+$/i.test(url) && !/^javascript:/i.test(url);
  }

  function hex(value, fallback) {
    var v = String(value || "").trim();
    if (/^#[0-9a-fA-F]{6}$/.test(v)) return v.toUpperCase();
    return fallback;
  }

  function mixWhite(color, amount) {
    var base = hex(color, "#FF6633");
    var n = parseInt(base.slice(1), 16);
    var ch = [((n >> 16) & 255), ((n >> 8) & 255), (n & 255)].map(function (c) {
      return Math.round(c + (255 - c) * amount);
    });
    return "#" + ch.map(function (c) { return c.toString(16).padStart(2, "0"); }).join("").toUpperCase();
  }

  function relLum(color) {
    var n = parseInt(hex(color, "#000000").slice(1), 16);
    function lin(c) {
      c = c / 255;
      return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    var r = lin((n >> 16) & 255);
    var g = lin((n >> 8) & 255);
    var b = lin(n & 255);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  function inkOn(color) {
    var L = relLum(color);
    var white = 1.05 / (L + 0.05);
    var black = (L + 0.05) / 0.05;
    return white >= black ? "#ffffff" : "#1a1a1a";
  }

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function safeValue(value) {
    return String(value == null ? "" : value).replace(/\s+/g, " ").replace(/\|/g, "/").slice(0, 500);
  }

  function str(value) { return String(value == null ? "" : value).trim(); }

  function tag(level) {
    var n = Number(level);
    if (!(n >= 1 && n <= 6)) n = 2;
    return "h" + n;
  }

  function uid() {
    if (globalThis.crypto && typeof crypto.randomUUID === "function") return "user:" + crypto.randomUUID();
    return "user:" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }

  function today() {
    var d = new Date();
    var m = String(d.getMonth() + 1).padStart(2, "0");
    var day = String(d.getDate()).padStart(2, "0");
    return d.getFullYear() + "-" + m + "-" + day;
  }

  function normalizeLocale(locale) {
    var value = String(locale || "en").toLowerCase();
    if (value.indexOf("pt") === 0) return "pt-BR";
    return "en";
  }

  function normalizeScale(scale) {
    if (scale === "simple" || scale === "four" || scale === "cvss") return scale;
    return "cvss";
  }

  function isPosInt(n) { return typeof n === "number" && n > 0 && n === Math.floor(n); }

  function clone(value) { return JSON.parse(JSON.stringify(value)); }

  function selfCheck() {
    var errors = [];
    function assert(cond, msg) { if (!cond) errors.push(msg); }
    var state = makeTemplate({
      id: "web-app",
      name: "Web",
      locale: "en",
      preset: "icarus",
      coverStyle: "GRADIENT_HERO",
      findingLayout: "ELEVATED_CARD",
      severityScale: "cvss",
      enabled: CATALOG.map(function (item) { return item.id; })
    });
    state.sections.forEach(function (section) {
      if (section.id === "EXECUTIVE_SUMMARY") section.params.content = "Hello <script>alert(1)</script> [x](javascript:alert(1))";
    });
    var html = renderReport(state, { level: 1 });
    assert(html.indexOf("<script>") === -1, "script tag escaped");
    assert(html.indexOf("&lt;script&gt;") !== -1, "script escaped text");
    assert(html.indexOf('href="javascript:') === -1, "javascript url dropped");
    assert(html.indexOf("<table") !== -1, "legend table rendered");
    assert(isPublicUrl("https://www.buymeacoffee.com/YOUR_HANDLE") === false, "placeholder hidden");
    assert(isPublicUrl("https://www.buymeacoffee.com/icarus") === true, "real donation url");
    assert(isPublicUrl("https://github.com/sponsors/YOUR_HANDLE") === false, "sponsors placeholder");
    var profile = toReportProfile(state);
    assert(profile.schemaVersion === "1", "schema");
    assert(profile.coverRenderer === "GRADIENT_HERO", "cover");
    assert(profile.builtIn === false, "not builtin");
    assert(profile.sections.nodes.some(function (node) { return node.id === "FINDINGS"; }), "findings node");
    assert(profile.sections.nodes.every(function (node) { return node.id !== "COVER"; }), "cover not a node");
    assert(profile.branding.companyLogoPath === null, "logo path empty");
    var back = fromReportProfile(profile);
    assert(back.sections.map(function (section) { return section.id; }).join() === state.sections.map(function (section) { return section.id; }).join(), "order");
    assert(back.fields.author === state.fields.author, "author");
    assert(back.colors.primary === state.colors.primary, "color");
    var cover = state.sections.filter(function (section) { return section.id === "COVER"; })[0];
    var moved = state.sections.filter(function (section) { return section.id !== "COVER"; });
    moved.splice(2, 0, cover);
    state.sections = moved;
    var restored = fromReportProfile(toReportProfile(state));
    assert(restored.sections[2].id === "COVER", "cover position");
    state.sections.forEach(function (section) { if (section.id === "COVER") section.enabled = false; });
    assert(toReportProfile(state).coverRenderer === "NONE", "cover off");
    assert(inkOn("#B38F00") === "#1a1a1a", "gold ink");
    assert(inkOn("#CC2E2E") === "#ffffff", "red ink");
    var block = makeBlock({ type: "chart", chart: "bar", binding: "findingsBySeverity", title: "Findings by severity" });
    var withBlock = makeTemplate({ name: "Blocks", locale: "en", enabled: ["COVER", "EXECUTIVE_SUMMARY"] });
    withBlock.sections.splice(1, 0, block);
    withBlock.sections.forEach(function (section) { if (section.id === "COVER") section.required = true; });
    withBlock.styles.heading.align = "center";
    withBlock.styles.body.fontSize = 12;
    var exported = toReportProfile(withBlock);
    var blockNode = exported.sections.nodes.filter(function (node) { return node.rendererKey === "CUSTOM_BLOCK"; })[0];
    assert(blockNode && blockNode.params.blockType === "chart", "block type exported");
    assert(blockNode.params.spec.indexOf("findingsBySeverity") !== -1, "binding exported");
    assert(exported.sections.nodes.every(function (node) { return node.id !== "COVER"; }), "cover still not a node");
    assert(exported.pdfTheme.fontStack === "Helvetica", "pdf font");
    assert(exported.pdfTheme.baseFontSize === 12, "body size mapped");
    assert(exported.pdfTheme.textHex === withBlock.styles.body.color, "text color mapped");
    var imported = fromReportProfile(exported);
    var backBlock = imported.sections.filter(function (section) { return section.kind === "block"; })[0];
    assert(backBlock && backBlock.block.binding === "findingsBySeverity", "block roundtrip");
    assert(imported.sections.filter(function (section) { return section.id === "COVER"; })[0].required === true, "cover required");
    assert(imported.styles.heading.align === "center", "align roundtrip");
    var classic = fromReportProfile({
      schemaVersion: "1",
      name: "Legacy",
      locale: "en",
      coverRenderer: "GRADIENT_HERO",
      findingRenderer: "ELEVATED_CARD",
      sections: { nodes: [
        { id: "EXECUTIVE_SUMMARY", enabled: true, order: 1, required: true, rendererKey: "EXECUTIVE_SUMMARY", params: { title: "Summary", content: "Still here" } },
        { id: "block:mystery", enabled: true, order: 2, required: false, rendererKey: "CUSTOM_BLOCK", params: { title: "Mystery", blockType: "hologram", spec: "{\"type\":\"hologram\"}" } },
        { id: "block:broken", enabled: true, order: 3, required: false, rendererKey: "CUSTOM_BLOCK", params: { title: "Broken", blockType: "chart", spec: "{not json" } }
      ] }
    });
    assert(classic.sections.some(function (section) { return section.id === "EXECUTIVE_SUMMARY" && section.required && section.params.content === "Still here"; }), "legacy section kept");
    var mystery = classic.sections.filter(function (section) { return section.id === "block:mystery"; })[0];
    assert(mystery && mystery.block.type === "hologram" && mystery.block.unsupported, "unknown block kept");
    var broken = classic.sections.filter(function (section) { return section.id === "block:broken"; })[0];
    assert(broken && broken.block.invalid, "bad spec marked");
    assert(blockMarkdown(mystery).indexOf("Unsupported block") !== -1, "unknown block degrades in markdown");
    return errors;
  }

  return {
    SPEC: SPEC,
    CATALOG: CATALOG,
    BLOCK_TYPES: BLOCK_TYPES,
    STYLE_ROLES: STYLE_ROLES,
    PRESETS: PRESETS,
    SEVERITY_ALL: SEVERITY_ALL,
    SEVERITY_COLORS: SEVERITY_COLORS,
    FINDING_FIELDS: FINDING_FIELDS,
    FIELD_ORDER: FIELD_ORDER,
    titleFor: titleFor,
    legendFor: legendFor,
    makeTemplate: makeTemplate,
    makeBlock: makeBlock,
    normalizeBlock: normalizeBlock,
    normalizeStyles: normalizeStyles,
    normalizePage: normalizePage,
    normalizeLogoBox: normalizeLogoBox,
    styleCss: styleCss,
    fontStack: fontStack,
    severityCounts: severityCounts,
    toReportProfile: toReportProfile,
    fromReportProfile: fromReportProfile,
    renderReport: renderReport,
    renderMarkdown: renderMarkdown,
    fillText: function (state, text) { return interpolate(text, buildVars(state)); },
    toHtmlDocument: toHtmlDocument,
    toMarkdown: toMarkdown,
    reportCss: reportCss,
    sanitizeLogo: sanitizeLogo,
    isPublicUrl: isPublicUrl,
    hex: hex,
    clone: clone,
    today: today,
    selfCheck: selfCheck,
    sampleFindings: sampleFindings
  };
});
