/**
 * ICARUS report template studio.
 * Three panes: pages, an A4 canvas, and a property inspector.
 * Part of ICARUS, MIT License. Nothing is uploaded.
 */
(function () {
  var P = window.IcarusProfile;
  var Blocks = window.IcarusBlocks;
  var AI = window.IcarusAI;
  var I18N = window.IcarusI18n;
  var CFG = window.ICARUS_SITE_CONFIG || {};
  var DRAFT_KEY = "icarus.templateBuilder.draft.v2";
  var LANG_KEY = "icarus.templateBuilder.lang";

  var uiLang = "en";
  var state = null;
  var tab = "style";
  var zoom = 0.85;
  var tool = "select";
  var past = [];
  var future = [];
  var saveTimer = 0;
  var commitTimer = 0;
  var dragId = "";

  var GROUPS = [
    { key: "groupStructure", ids: ["COVER", "CONFIDENTIALITY", "TABLE_OF_CONTENTS", "SIGNATURE"] },
    { key: "groupNarrative", ids: ["EXECUTIVE_SUMMARY", "ASSESSMENT_SCOPE", "METHODOLOGY", "REMEDIATION", "APPENDIX"] },
    { key: "groupFindings", ids: ["RISK_RATING", "VULNERABILITY_SUMMARY", "FINDINGS"] }
  ];
  var BLOCKS = [
    { type: "chart", key: "blockChart" },
    { type: "table", key: "blockTable" },
    { type: "drawing", key: "blockDrawing" },
    { type: "image", key: "blockImage" },
    { type: "callout", key: "blockCallout" }
  ];
  var FONTS = [
    ["helvetica", "Helvetica"],
    ["georgia", "Georgia"],
    ["palatino", "Palatino"],
    ["segoe", "Segoe UI"],
    ["courier", "Courier"]
  ];
  var ROLES = {
    coverTitle: "roleCoverTitle",
    coverMeta: "roleCoverMeta",
    heading: "roleHeading",
    body: "roleBody",
    table: "roleTable",
    findingTitle: "roleFinding",
    logo: "logoPlace",
    block: "groupBlocks",
    cover: "roleCover"
  };

  function t(key, vars) { return I18N.t(uiLang, key, vars); }
  function $(id) { return document.getElementById(id); }
  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }
  function findSection(id) {
    return (state.sections || []).filter(function (section) { return section.id === id; })[0] || null;
  }
  function sectionTitle(section) {
    var title = section.params && section.params.title ? String(section.params.title).trim() : "";
    return title || P.titleFor(section.id, state.locale);
  }
  function visible(section) { return !!(section && (section.enabled || section.required)); }
  function coverSection() { return findSection("COVER"); }
  function coverOn() { var cover = coverSection(); return !!(cover && visible(cover)); }

  function storageGet(key) { try { return localStorage.getItem(key); } catch (err) { return null; } }
  function storageSet(key, value) { try { localStorage.setItem(key, value); return true; } catch (err) { return false; } }

  function slim() {
    return {
      templateId: state.templateId,
      profileId: state.profileId,
      name: state.name,
      locale: state.locale,
      severityScale: state.severityScale,
      findingLayout: state.findingLayout,
      colors: state.colors,
      contentPolicy: state.contentPolicy,
      fields: state.fields,
      sections: state.sections,
      styles: state.styles,
      page: state.page,
      logoBox: state.logoBox,
      logoDataUrl: state.logoDataUrl,
      selection: state.selection
    };
  }

  function applySlim(data) {
    Object.keys(slim()).forEach(function (key) {
      if (data[key] !== undefined) state[key] = data[key];
    });
    state.styles = P.normalizeStyles(state.styles);
    state.page = P.normalizePage(state.page);
    state.logoBox = P.normalizeLogoBox(state.logoBox);
    if (!state.selection) state.selection = { role: "coverTitle", id: "COVER" };
  }

  function commit() {
    var snap = JSON.stringify(slim());
    if (past[past.length - 1] === snap) return;
    past.push(snap);
    if (past.length > 80) past.shift();
    future = [];
    undoButtons();
  }

  function undo() {
    if (past.length < 2) return;
    future.push(past.pop());
    applySlim(JSON.parse(past[past.length - 1]));
    persist();
    renderAll();
  }

  function redo() {
    if (!future.length) return;
    var snap = future.pop();
    past.push(snap);
    applySlim(JSON.parse(snap));
    persist();
    renderAll();
  }

  function undoButtons() {
    $("undo").disabled = past.length < 2;
    $("redo").disabled = !future.length;
  }

  function edit(fn) {
    fn();
    commit();
    persist();
    renderAll();
  }

  function touch(fn) {
    fn();
    renderCanvas();
    clearTimeout(commitTimer);
    commitTimer = setTimeout(function () { commit(); persist(); }, 350);
  }

  function persist() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      var ok = storageSet(DRAFT_KEY, JSON.stringify(slim()));
      say(ok ? "saved" : "draftFull");
    }, 250);
  }

  function say(key) {
    $("status").textContent = key ? t(key) : "";
  }

  function icon(name) {
    var paths = {
      cover: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
      lock: '<rect x="6" y="11" width="12" height="8" rx="1.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
      text: '<path d="M5 6h14M12 6v12M9 18h6"/>',
      list: '<path d="M8 7h11M8 12h11M8 17h11"/><circle cx="5" cy="7" r="1"/><circle cx="5" cy="12" r="1"/><circle cx="5" cy="17" r="1"/>',
      table: '<rect x="4" y="5" width="16" height="14" rx="1.5"/><path d="M4 10h16M4 15h16M10 5v14"/>',
      cards: '<rect x="4" y="5" width="16" height="6" rx="1"/><rect x="4" y="13" width="16" height="6" rx="1"/>',
      chart: '<path d="M4 19V5M4 19h16"/><path d="M8 15v-4M12 15V8M16 15v-2"/>',
      draw: '<path d="M4 16l4-1 8-8 2 2-8 8-1 4-5-5z"/>',
      image: '<rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="10" r="1.4"/><path d="M7 16l3.5-3.5L14 16l2-2 2 2"/>',
      note: '<path d="M6 4h8l4 4v12H6z"/><path d="M14 4v4h4"/>',
      sign: '<path d="M5 16c2-4 3-4 4 0s2 4 3 0 2-6 4-6 3 2 3 2"/>'
    };
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">' + (paths[name] || paths.text) + "</svg>";
  }

  function tileIcon(id) {
    if (id === "COVER") return "cover";
    if (id === "CONFIDENTIALITY" || id === "SIGNATURE") return "sign";
    if (id === "TABLE_OF_CONTENTS") return "list";
    if (id === "VULNERABILITY_SUMMARY" || id === "RISK_RATING") return "table";
    if (id === "FINDINGS") return "cards";
    return "text";
  }

  function boot() {
    uiLang = initialLang();
    var draft = storageGet(DRAFT_KEY);
    if (draft) {
      try { state = blank(); applySlim(JSON.parse(draft)); } catch (err) { state = null; }
    }
    if (!state) state = cloneTemplate(window.ICARUS_TEMPLATES[0]);
    past = [JSON.stringify(slim())];
    future = [];
    bind();
    renderChrome();
    renderAll();
    if (draft) say("restored");
  }

  function blank() {
    return P.makeTemplate({ locale: "en" });
  }

  function cloneTemplate(tpl) {
    var copy = P.clone(tpl);
    copy.profileId = "user:" + Math.random().toString(36).slice(2, 12);
    copy.selection = { role: "coverTitle", id: "COVER" };
    return copy;
  }

  function initialLang() {
    var saved = storageGet(LANG_KEY);
    if (saved === "pt-BR" || saved === "en") return saved;
    return (navigator.language || "").toLowerCase().indexOf("pt") === 0 ? "pt-BR" : "en";
  }

  function bind() {
    document.addEventListener("click", onClick);
    document.addEventListener("input", onInput);
    document.addEventListener("change", onChange);
    document.addEventListener("keydown", onKey);
    $("page-list").addEventListener("dragstart", onDragStart);
    $("page-list").addEventListener("dragover", onDragOver);
    $("page-list").addEventListener("drop", onDrop);
    $("page-list").addEventListener("dragend", function () { dragId = ""; });
    $("stack").addEventListener("pointerdown", onPointerDown);
    $("artboard").addEventListener("dragover", function (e) { if (e.dataTransfer && e.dataTransfer.files) e.preventDefault(); });
    $("artboard").addEventListener("drop", onFileDrop);
    window.addEventListener("beforeprint", function () { $("stack").style.zoom = "1"; });
    window.addEventListener("afterprint", applyZoom);
  }

  function onKey(e) {
    var meta = e.metaKey || e.ctrlKey;
    if (!meta) return;
    var key = e.key.toLowerCase();
    if (key === "z" && !e.shiftKey) { e.preventDefault(); undo(); }
    else if (key === "z" || key === "y") { e.preventDefault(); redo(); }
  }

  function onClick(e) {
    var lang = e.target.closest("[data-set-lang]");
    if (lang) { setLang(lang.getAttribute("data-set-lang")); return; }
    if (e.target.id === "undo") { undo(); return; }
    if (e.target.id === "redo") { redo(); return; }
    if (e.target.id === "zoom-in") { setZoom(zoom + 0.1); return; }
    if (e.target.id === "zoom-out") { setZoom(zoom - 0.1); return; }
    if (e.target.id === "zoom-fit") { fitZoom(); return; }
    if (e.target.id === "zoom-label") { setZoom(1); return; }
    if (e.target.id === "export-open") { toggleMenu(); return; }
    var exp = e.target.closest("[data-export]");
    if (exp) { $("export-menu").hidden = true; doExport(exp.getAttribute("data-export")); return; }
    if (!e.target.closest(".export-wrap")) $("export-menu").hidden = true;
    if (e.target.id === "ai-open") { openAi(); return; }
    if (e.target.id === "ai-import-open") { $("ai-errors").innerHTML = ""; $("ai-import-dialog").showModal(); return; }
    if (e.target.id === "ai-copy") { copyPrompt(); return; }
    if (e.target.id === "ai-download") { download("icarus-ai-prompt.txt", $("ai-prompt").value, "text/plain"); return; }
    if (e.target.id === "ai-apply") { applyAi(); return; }
    var mobile = e.target.closest("[data-mobile]");
    if (mobile && mobile.closest(".mobile-tabs")) { setMobile(mobile.getAttribute("data-mobile")); return; }
    var tabBtn = e.target.closest("[data-tab]");
    if (tabBtn) { tab = tabBtn.getAttribute("data-tab"); renderInspector(); return; }
    var add = e.target.closest("[data-add]");
    if (add) { addFromPalette(add.getAttribute("data-add"), add.getAttribute("data-kind")); return; }
    var lock = e.target.closest("[data-lock]");
    if (lock) { toggleRequired(lock.getAttribute("data-lock")); return; }
    var remove = e.target.closest("[data-remove]");
    if (remove) { removeSection(remove.getAttribute("data-remove")); return; }
    var pageName = e.target.closest("[data-select]");
    if (pageName) {
      state.selection = { role: "page", id: pageName.getAttribute("data-select") };
      tab = "page";
      renderPages();
      renderInspector();
      paintSelection();
      return;
    }
    var align = e.target.closest("[data-align]");
    if (align) { setAlign(align.getAttribute("data-align")); return; }
    var styleBtn = e.target.closest("[data-style]");
    if (styleBtn) { toggleStyle(styleBtn.getAttribute("data-style")); return; }
    var draw = e.target.closest("[data-tool]");
    if (draw) { tool = draw.getAttribute("data-tool"); renderInspector(); return; }
    var rowAdd = e.target.closest("[data-add-row]");
    if (rowAdd) { addTableRow(); return; }
    var preset = e.target.closest("[data-preset]");
    if (preset) { applyPreset(preset.getAttribute("data-preset")); return; }
    if (e.target.id === "remove-logo") { edit(function () { state.logoDataUrl = null; }); return; }
    if (e.target.id === "apply-svg") { applySvg(); return; }
    var sheetHit = e.target.closest("#stack [data-role]");
    if (sheetHit) selectCanvas(sheetHit);
  }

  function onInput(e) {
    var el = e.target;
    if (el.id === "template-name") { touch(function () { state.name = el.value; }); return; }
    if (el.id === "ai-answer" || el.id === "ai-prompt") return;
    if (!el.closest("#inspector") && el.id !== "template-name") return;
    var key = el.getAttribute("data-field");
    if (key) { touch(function () { state.fields[key] = el.value; }); return; }
    if (el.dataset.bind === "title") {
      touch(function () { var section = selectedSection(); if (section) section.params.title = el.value; });
      return;
    }
    if (el.dataset.bind === "body") {
      touch(function () { var section = selectedSection(); if (section) section.params.content = el.value; });
      return;
    }
    if (el.dataset.bind === "style") { touch(function () { writeStyle(el); }); return; }
    if (el.dataset.bind === "logo") { touch(function () { writeLogo(el); }); return; }
    if (el.dataset.bind === "page") { touch(function () { writePage(el); }); return; }
    if (el.dataset.bind === "block") { touch(function () { writeBlock(el); }); return; }
    if (el.dataset.bind === "color") { touch(function () { writeThemeColor(el); }); return; }
  }

  function onChange(e) {
    var el = e.target;
    if (el.id === "starter") { pickStarter(el.value); el.value = ""; return; }
    if (el.id === "import-file") { importFile(el.files && el.files[0]); el.value = ""; return; }
    if (el.id === "logo-file" || el.dataset.bind === "image-file") { readLogo(el.files && el.files[0], el.dataset.bind === "image-file"); el.value = ""; return; }
    if (el.dataset.bind === "select" || (el.dataset.bind === "style" && el.tagName === "SELECT")) {
      edit(function () { if (el.dataset.bind === "style") writeStyle(el); else writeSelect(el); });
      return;
    }
    if (el.dataset.bind === "check") { edit(function () { writeCheck(el); }); return; }
    if (el.id === "template-name" || el.closest("#inspector")) { commit(); persist(); }
  }

  function renderChrome() {
    document.documentElement.lang = uiLang === "pt-BR" ? "pt-BR" : "en";
    document.title = t("docTitle");
    var meta = $("meta-desc");
    if (meta) meta.setAttribute("content", t("metaDesc"));
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      if (el.id === "confirm-ok") return;
      el.textContent = t(el.getAttribute("data-i18n"));
    });
    document.querySelectorAll("[data-i18n-title]").forEach(function (el) {
      var label = t(el.getAttribute("data-i18n-title"));
      el.title = label;
      el.setAttribute("aria-label", label);
    });
    document.querySelectorAll("[data-set-lang]").forEach(function (el) {
      el.setAttribute("aria-pressed", String(el.getAttribute("data-set-lang") === uiLang));
    });
    var select = $("starter");
    var current = select.value;
    select.innerHTML = '<option value="">' + esc(t("starters")) + "</option>" +
      (window.ICARUS_TEMPLATES || []).map(function (tpl) {
        return '<option value="' + esc(tpl.templateId) + '">' + esc(tpl.name) + "</option>";
      }).join("");
    select.value = current;
    $("template-name").value = state ? state.name : "";
    var repo = CFG.repoUrl || "https://github.com/IcarusSec/ICARUS";
    $("repo-link").href = repo;
    $("docs-link").href = CFG.docsUrl || repo;
    var bapp = P.isPublicUrl(CFG.bappUrl) ? CFG.bappUrl : (CFG.bappStoreHome || "https://portswigger.net/bappstore");
    $("bapp-link").href = bapp;
    $("bapp-link").textContent = P.isPublicUrl(CFG.bappUrl) ? t("bapp") : t("bappHome");
    renderSupport();
    undoButtons();
    applyZoom();
  }

  function renderSupport() {
    var box = $("support");
    var donations = CFG.donations || {};
    var items = [
      ["buyMeACoffee", "Buy Me a Coffee"],
      ["kofi", "Ko-fi"],
      ["githubSponsors", "GitHub Sponsors"]
    ].filter(function (item) { return P.isPublicUrl(donations[item[0]]); });
    box.hidden = !items.length;
    box.innerHTML = items.length ? '<span>' + esc(t("support")) + "</span>" + items.map(function (item) {
      return '<a href="' + esc(donations[item[0]]) + '" rel="noopener noreferrer">' + esc(item[1]) + "</a>";
    }).join("") : "";
  }

  function renderAll() {
    renderChrome();
    renderPages();
    renderPalette();
    renderCanvas();
    renderInspector();
  }

  function renderPages() {
    var list = $("page-list");
    list.innerHTML = state.sections.map(function (section) {
      var active = state.selection && state.selection.id === section.id ? " active" : "";
      var off = visible(section) ? "" : " off";
      return '<li class="page-row' + active + off + '" draggable="true" data-id="' + esc(section.id) + '">' +
        '<span class="grip" aria-hidden="true">⠿</span>' +
        '<button type="button" class="page-name" data-select="' + esc(section.id) + '">' + esc(sectionTitle(section)) + "</button>" +
        '<button type="button" class="lock" data-lock="' + esc(section.id) + '" aria-pressed="' + (section.required ? "true" : "false") + '" title="' + esc(t("requiredHelp")) + '">' +
        (section.required ? "🔒" : "🔓") + "</button>" +
        (section.required ? '<span class="mini"></span>' : '<button type="button" data-remove="' + esc(section.id) + '" title="' + esc(t("removePage")) + '">×</button>') +
        "</li>";
    }).join("");
  }

  function renderPalette() {
    var html = GROUPS.map(function (group) {
      return '<p class="group-label">' + esc(t(group.key)) + '</p><div class="tiles">' +
        group.ids.map(function (id) {
          return '<button type="button" class="tile" data-add="' + id + '" data-kind="section">' + icon(tileIcon(id)) + "<span>" + esc(P.titleFor(id, uiLang === "pt-BR" ? "pt-BR" : "en")) + "</span></button>";
        }).join("") + "</div>";
    }).join("");
    html += '<p class="group-label">' + esc(t("groupBlocks")) + '</p><div class="tiles">' + BLOCKS.map(function (block) {
      var name = block.type === "chart" ? "chart" : block.type === "table" ? "table" : block.type === "drawing" ? "draw" : block.type === "image" ? "image" : "note";
      return '<button type="button" class="tile" data-add="' + block.type + '" data-kind="block">' + icon(name) + "<span>" + esc(t(block.key)) + "</span></button>";
    }).join("") + "</div>";
    $("palette").innerHTML = html;
  }

  function renderCanvas() {
    var cover = coverOn() ? coverSheet() : "";
    var flow = flowItems();
    var packed = paginate(flow);
    var contentCount = flow.length ? packed.pages.length : 0;
    var total = (cover ? 1 : 0) + contentCount;
    var pages = flow.length ? packed.pages.map(function (items, index) {
      return contentSheet(items, (cover ? 2 : 1) + index, total || 1, packed);
    }).join("") : (cover ? "" : contentSheet([], 1, 1, packed));
    $("stack").innerHTML = cover + pages;
    numberPages();
    paintSelection();
    applyZoom();
  }

  function coverSheet() {
    var fields = state.fields || {};
    var primary = P.hex(state.colors.primary, "#FF6633");
    var secondary = P.hex(state.colors.secondary, "#6E6E6E");
    var cover = coverSection();
    var band = cover && cover.coverStyle === "HEADER_BAND"
      ? "background:" + primary
      : "background:linear-gradient(135deg," + primary + "," + secondary + ")";
    var logo = logoBoxHtml();
    var titleStyle = P.styleCss(state.styles.coverTitle);
    var metaStyle = P.styleCss(state.styles.coverMeta);
    return '<article class="sheet" data-page="cover">' +
      '<div class="cover-band" style="' + band + '" data-role="cover" data-section="COVER"></div>' +
      logo +
      '<div class="sheet-body" style="top:72mm;left:18mm;right:18mm;bottom:18mm">' +
      '<h1 class="doc-h" data-role="coverTitle" data-section="COVER" style="' + titleStyle + 'border:0">' + esc(fields.documentTitle || state.name) + "</h1>" +
      '<p data-role="coverMeta" data-section="COVER" style="' + metaStyle + '">' + esc(fields.project || "") + "</p>" +
      '<p data-role="coverMeta" data-section="COVER" style="' + metaStyle + '">' + esc(fields.companyName || "") + "</p>" +
      '<p data-role="coverMeta" data-section="COVER" style="' + metaStyle + '">' + esc(fields.classification || "") + "</p>" +
      '<p data-role="coverMeta" data-section="COVER" style="' + metaStyle + '">' + esc(fields.author || "") + " · " + esc(fields.date || "") + "</p>" +
      "</div></article>";
  }

  function logoBoxHtml() {
    var box = state.logoBox.cover;
    var src = P.sanitizeLogo(state.logoDataUrl);
    var inner = src
      ? '<img alt="" src="' + src + '">'
      : '<span class="logo-ph">' + esc(t("logo")) + "</span>";
    return '<div class="logo-abs" data-role="logo" data-section="COVER" style="left:' + box.x + "mm;top:" + box.y + "mm;width:" + box.w + "mm;height:" + box.h + 'mm">' +
      inner + '<span class="handle" data-resize="logo"></span></div>';
  }

  function contentSheet(items, number, total, packed) {
    var page = state.page;
    var top = page.marginTop + (page.showHeader ? 12 : 0);
    var bottom = page.marginBottom + (page.showFooter ? 10 : 0);
    var head = page.showHeader ? headerHtml() : "";
    var foot = page.showFooter ? '<footer class="sheet-foot" style="left:' + page.marginLeft + "mm;right:" + page.marginRight + 'mm" data-page-number="' + number + '" data-page-total="' + total + '"></footer>' : "";
    return '<article class="sheet" style="--accent:' + esc(P.hex(state.colors.primary, "#2F6BFF")) + '">' +
      head +
      '<div class="sheet-body" style="top:' + top + "mm;bottom:" + bottom + "mm;left:" + page.marginLeft + "mm;right:" + page.marginRight + 'mm">' +
      items.map(function (item) { return item.html; }).join("") +
      "</div>" + foot + "</article>";
  }

  function headerHtml() {
    var page = state.page;
    var box = state.logoBox.header;
    var src = box.show ? P.sanitizeLogo(state.logoDataUrl) : "";
    var justify = box.align === "center" ? "center" : box.align === "right" ? "flex-end" : "flex-start";
    var logo = src ? '<img alt="" src="' + src + '" style="width:' + box.w + "mm;height:" + box.h + 'mm;object-fit:contain">' : "";
    return '<header class="sheet-head" style="top:' + Math.max(4, page.marginTop - 12) + "mm;left:" + page.marginLeft + "mm;right:" + page.marginRight + "mm;justify-content:" + justify + '">' +
      logo + "<span>" + esc(state.fields.documentTitle || state.name) + "</span><span>" + esc(state.fields.classification || "") + "</span></header>";
  }

  function numberPages() {
    document.querySelectorAll("[data-page-number]").forEach(function (el) {
      var label = t("pageOf", { n: el.getAttribute("data-page-number"), total: el.getAttribute("data-page-total") });
      el.innerHTML = "<span>" + esc(state.fields.companyName || "") + "</span><span>" + esc(label) + "</span>";
    });
  }

  function flowItems() {
    var items = [];
    state.sections.forEach(function (section) {
      if (!visible(section) || section.id === "COVER" || section.kind === "cover") return;
      items.push({
        html: '<h2 class="doc-h" data-role="heading" data-section="' + esc(section.id) + '" style="' + P.styleCss(state.styles.heading) + '">' + esc(sectionTitle(section)) + "</h2>"
      });
      if (section.kind === "block") {
        items.push({ html: '<div class="doc-block" data-role="block" data-section="' + esc(section.id) + '">' + Blocks.renderInner(section, state) + "</div>" });
        return;
      }
      if (section.kind === "toc" || section.id === "TABLE_OF_CONTENTS") {
        var lis = state.sections.filter(visible).filter(function (item) {
          return item.id !== "COVER" && item.id !== section.id;
        }).map(function (item) { return "<li>" + esc(sectionTitle(item)) + "</li>"; }).join("");
        items.push({ html: '<ol class="toc-list" data-role="body" data-section="' + esc(section.id) + '" style="' + P.styleCss(state.styles.body) + '">' + lis + "</ol>" });
        return;
      }
      if (section.kind === "table" || section.id === "VULNERABILITY_SUMMARY") {
        items.push({ html: findingsTable(section) });
        return;
      }
      if (section.kind === "detail" || section.id === "FINDINGS") {
        items.push({ html: '<p class="sample-flag" data-section="' + esc(section.id) + '">' + esc(t("sampleBanner")) + "</p>" });
        P.sampleFindings(state.locale).forEach(function (finding, index) {
          items.push({ html: findingCard(finding, index) });
        });
        return;
      }
      var body = section.params && section.params.content ? String(section.params.content) : "";
      var chunks = body.split(/\n{2,}/).map(function (part) { return part.trim(); }).filter(Boolean);
      if (!chunks.length) {
        items.push({ html: '<p class="doc-p" data-role="body" data-section="' + esc(section.id) + '" style="' + P.styleCss(state.styles.body) + '">' + esc(t("emptyBody")) + "</p>" });
        return;
      }
      chunks.forEach(function (chunk) {
        items.push({
          html: '<div class="doc-p" data-role="body" data-section="' + esc(section.id) + '" style="' + P.styleCss(state.styles.body) + '">' +
            P.renderMarkdown(P.fillText(state, chunk), 2) + "</div>"
        });
      });
    });
    return items;
  }

  function findingsTable(section) {
    var findings = P.sampleFindings(state.locale);
    var rows = findings.map(function (finding, index) {
      var color = P.hex(state.colors.severity && state.colors.severity[finding.severity], "#666666");
      return "<tr><td>" + (index + 1) + "</td><td>" + esc(finding.title) + '</td><td><span class="sev" style="background:' +
        color + ";color:" + ink(color) + '">' + esc(finding.severity) + "</span></td></tr>";
    }).join("");
    return '<div class="table-wrap" data-role="table" data-section="' + esc(section.id) + '" style="' + P.styleCss(state.styles.table) + '"><table><thead><tr><th>#</th><th>' +
      esc(t("finding") === "finding" ? "Finding" : t("finding")) + "</th><th>" + esc(uiLang === "pt-BR" ? "Severidade" : "Severity") +
      "</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
  }

  function findingCard(finding, index) {
    var color = P.hex(state.colors.severity && state.colors.severity[finding.severity], "#666666");
    return '<article class="finding-card" style="border-left-color:' + color + '"><h3 data-role="findingTitle" data-section="FINDINGS" style="' +
      P.styleCss(state.styles.findingTitle) + '">' + (index + 1) + ". " + esc(finding.title) +
      ' <span class="sev" style="background:' + color + ";color:" + ink(color) + '">' + esc(finding.severity) + "</span></h3>" +
      '<p class="meta">' + esc(finding.cwe) + " · " + esc(finding.path) + "</p>" +
      '<p data-role="body" data-section="FINDINGS" style="' + P.styleCss(state.styles.body) + '">' + esc(finding.description) + "</p></article>";
  }

  function ink(color) {
    var n = parseInt(String(color).slice(1), 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return (r * 0.3 + g * 0.59 + b * 0.11) > 150 ? "#1a1a1a" : "#ffffff";
  }

  function paginate(items) {
    var page = state.page;
    var header = page.showHeader ? 12 : 0;
    var footer = page.showFooter ? 10 : 0;
    var bodyMm = 297 - page.marginTop - page.marginBottom - header - footer;
    var widthMm = 210 - page.marginLeft - page.marginRight;
    var measure = document.createElement("div");
    measure.className = "measure";
    measure.style.width = widthMm + "mm";
    document.body.appendChild(measure);
    items.forEach(function (item) {
      var el = document.createElement("div");
      el.innerHTML = item.html;
      measure.appendChild(el);
      item.h = el.getBoundingClientRect().height || 0;
    });
    var probe = document.createElement("div");
    probe.style.height = bodyMm + "mm";
    measure.appendChild(probe);
    var limit = probe.getBoundingClientRect().height || 1;
    measure.remove();
    var pages = [];
    var cur = [];
    var used = 0;
    items.forEach(function (item) {
      if (cur.length && used + item.h > limit + 1) {
        pages.push(cur);
        cur = [];
        used = 0;
      }
      cur.push(item);
      used += item.h;
    });
    if (cur.length || !pages.length) pages.push(cur);
    return { pages: pages };
  }

  function paintSelection() {
    document.querySelectorAll(".is-selected").forEach(function (el) { el.classList.remove("is-selected"); });
    var sel = state.selection;
    if (!sel || !sel.role) return;
    var role = sel.role === "page" ? "heading" : sel.role;
    document.querySelectorAll('[data-role="' + role + '"]').forEach(function (el) {
      if (!sel.id || el.getAttribute("data-section") === sel.id) el.classList.add("is-selected");
    });
  }

  function selectCanvas(el) {
    var role = el.getAttribute("data-role");
    var id = el.getAttribute("data-section") || "COVER";
    state.selection = { role: role, id: id };
    tab = role === "block" ? "page" : "style";
    renderPages();
    renderInspector();
    paintSelection();
  }

  function selectedSection() {
    var id = state.selection && state.selection.id;
    return id ? findSection(id) : null;
  }

  function currentStyle() {
    var role = state.selection && state.selection.role;
    if (!state.styles[role]) return null;
    return state.styles[role];
  }

  function renderInspector() {
    document.querySelectorAll("[data-tab]").forEach(function (btn) {
      btn.setAttribute("aria-selected", String(btn.getAttribute("data-tab") === tab));
      btn.textContent = t(btn.getAttribute("data-tab") === "style" ? "styleTab" : btn.getAttribute("data-tab") === "page" ? "pageTab" : "documentTab");
    });
    var root = $("inspector");
    if (tab === "document") root.innerHTML = documentPanel();
    else if (tab === "page") root.innerHTML = pagePanel();
    else root.innerHTML = stylePanel();
  }

  function stylePanel() {
    var role = state.selection && state.selection.role;
    if (role === "logo") return logoPanel();
    var style = currentStyle();
    if (!style) return '<p class="hint">' + esc(t("emptyStyle")) + "</p>" + logoPanel();
    var roleName = t(ROLES[role] || "textOptions");
    return '<h3>' + esc(t("textOptions")) + '</h3><p class="mini">' + esc(roleName) + "</p>" +
      '<div class="field"><label>' + esc(t("font")) + "</label>" + selectHtml("fontFamily", FONTS, style.fontFamily, "style") + "</div>" +
      '<div class="grid-2"><div class="field"><label>' + esc(t("size")) + '</label><input data-bind="style" data-key="fontSize" type="number" min="8" max="48" value="' + style.fontSize + '"></div>' +
      '<div class="field"><label>' + esc(t("lineHeight")) + '</label><input data-bind="style" data-key="lineHeight" type="number" min="1" max="2.2" step="0.05" value="' + style.lineHeight + '"></div></div>' +
      '<div class="field"><label>' + esc(t("color")) + '</label><div class="color-row"><input data-bind="style" data-key="color" data-kind="color" type="color" value="' + style.color + '"><input data-bind="style" data-key="color" type="text" value="' + esc(style.color) + '" maxlength="7"></div></div>' +
      '<div class="field"><span class="row-label">' + esc(t("weight")) + '</span><div class="seg">' +
      pressed("style", "bold", "B", style.weight === "bold", t("bold")) +
      pressed("style", "italic", "I", style.italic, t("italic")) +
      pressed("style", "underline", "U", style.underline, t("underline")) +
      "</div></div>" +
      '<div class="field"><span class="row-label">' + esc(t("align")) + '</span><div class="seg">' +
      ["left", "center", "right", "justify"].map(function (align) {
        var mark = { left: "⬅", center: "↔", right: "➡", justify: "☰" }[align];
        var label = t(align === "left" ? "alignLeft" : align === "center" ? "alignCenter" : align === "right" ? "alignRight" : "alignJustify");
        return '<button type="button" data-align="' + align + '" aria-pressed="' + (style.align === align ? "true" : "false") + '" title="' + esc(label) + '">' + mark + "</button>";
      }).join("") + "</div></div>" +
      '<div class="field"><span class="row-label">' + esc(t("spacing")) + '</span><div class="grid-2">' +
      '<label class="mini">' + esc(t("before")) + '<input data-bind="style" data-key="spaceBefore" type="number" min="0" max="40" value="' + style.spaceBefore + '"></label>' +
      '<label class="mini">' + esc(t("after")) + '<input data-bind="style" data-key="spaceAfter" type="number" min="0" max="40" value="' + style.spaceAfter + '"></label></div></div>';
  }

  function pressed(group, key, face, on, label) {
    return '<button type="button" data-' + group + '="' + key + '" aria-pressed="' + (on ? "true" : "false") + '" title="' + esc(label) + '"><b>' + face + "</b></button>";
  }

  function logoPanel() {
    var cover = state.logoBox.cover;
    var header = state.logoBox.header;
    var src = P.sanitizeLogo(state.logoDataUrl);
    return '<h3>' + esc(t("logoPlace")) + "</h3>" +
      '<div class="drop" id="logo-drop">' + (src ? '<img alt="" src="' + src + '" style="max-width:100%;max-height:64px">' : esc(t("dropLogo"))) + "</div>" +
      '<label class="text-btn file-btn" style="margin-top:8px">' + esc(t("logo")) + '<input id="logo-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp"></label>' +
      (src ? ' <button type="button" id="remove-logo" class="text-btn">' + esc(t("removeLogo")) + "</button>" : "") +
      '<p class="row-label" style="margin-top:12px">' + esc(t("coverLogo")) + "</p>" +
      '<div class="grid-4">' + numBox("logo", "x", cover.x) + numBox("logo", "y", cover.y) + numBox("logo", "w", cover.w) + numBox("logo", "h", cover.h) + "</div>" +
      '<p class="row-label">' + esc(t("headerLogo")) + "</p>" +
      '<label class="check"><input data-bind="check" data-key="headerShow" type="checkbox"' + (header.show ? " checked" : "") + "> " + esc(t("showInHeader")) + "</label>" +
      '<div class="seg">' + ["left", "center", "right"].map(function (align) {
        return '<button type="button" data-align="header:' + align + '" aria-pressed="' + (header.align === align ? "true" : "false") + '">' + esc(t(align === "left" ? "alignLeft" : align === "center" ? "alignCenter" : "alignRight")) + "</button>";
      }).join("") + "</div>" +
      '<div class="grid-2" style="margin-top:8px">' + numBox("logo", "hw", header.w, t("width")) + numBox("logo", "hh", header.h, t("height")) + "</div>";
  }

  function numBox(bind, key, value, label) {
    return '<label class="mini">' + esc(label || key.toUpperCase()) + '<input data-bind="' + bind + '" data-key="' + key + '" type="number" step="1" value="' + value + '"></label>';
  }

  function pagePanel() {
    var section = selectedSection();
    if (!section) return '<p class="hint">' + esc(t("emptyStyle")) + "</p>";
    var html = '<h3>' + esc(sectionTitle(section)) + "</h3>";
    if (section.id !== "COVER") {
      html += '<div class="field"><label>' + esc(t("sectionTitle")) + '</label><input data-bind="title" type="text" value="' + esc(section.params.title || "") + '"></div>';
    }
    html += '<label class="check"><input data-bind="check" data-key="required" type="checkbox"' + (section.required ? " checked" : "") + "> " + esc(t("required")) + "</label>";
    html += '<p class="mini">' + esc(t("requiredHelp")) + "</p>";
    html += '<label class="check"><input data-bind="check" data-key="enabled" type="checkbox"' + (visible(section) ? " checked" : "") + (section.required ? " disabled" : "") + "> " + esc(t("include")) + "</label>";
    if (section.id === "COVER") {
      html += '<div class="field"><label>' + esc(t("coverStyle")) + "</label>" + selectHtml("coverStyle", [["GRADIENT_HERO", t("styleHero")], ["HEADER_BAND", t("styleBand")]], section.coverStyle, "select") + "</div>";
    }
    if (section.kind === "markdown" || section.kind === "toc" || (section.params && section.kind !== "block" && section.kind !== "table" && section.kind !== "detail" && section.kind !== "cover")) {
      if (section.kind !== "table" && section.kind !== "detail" && section.kind !== "cover") {
        html += '<div class="field"><label>' + esc(t("sectionBody")) + '</label><textarea data-bind="body">' + esc(section.params.content || "") + "</textarea></div>";
      }
    }
    if (section.kind === "block") html += blockPanel(section);
    if (!section.required) html += '<button type="button" class="text-btn danger" data-remove="' + esc(section.id) + '">' + esc(t("removePage")) + "</button>";
    else html += '<p class="mini">' + esc(t("lockedRemove")) + "</p>";
    html += pageSetup();
    return html;
  }

  function pageSetup() {
    var page = state.page;
    return '<h3 style="margin-top:16px">' + esc(t("pageSetup")) + "</h3>" +
      '<p class="row-label">' + esc(t("margins")) + "</p><div class=\"grid-4\">" +
      numBox("page", "marginTop", page.marginTop, t("top")) +
      numBox("page", "marginRight", page.marginRight, t("right")) +
      numBox("page", "marginBottom", page.marginBottom, t("bottom")) +
      numBox("page", "marginLeft", page.marginLeft, t("left")) + "</div>" +
      '<label class="check"><input data-bind="check" data-key="showHeader" type="checkbox"' + (page.showHeader ? " checked" : "") + "> " + esc(t("showHeader")) + "</label>" +
      '<label class="check"><input data-bind="check" data-key="showFooter" type="checkbox"' + (page.showFooter ? " checked" : "") + "> " + esc(t("showFooter")) + "</label>" +
      '<label class="check"><input data-bind="check" data-key="showPageNumbers" type="checkbox"' + (page.showPageNumbers ? " checked" : "") + "> " + esc(t("pageNumbers")) + "</label>";
  }

  function blockPanel(section) {
    var block = section.block || { type: "callout" };
    var html = "";
    if (block.type === "chart") {
      html += '<div class="field"><label>' + esc(t("chartType")) + "</label>" + selectHtml("chart", [["bar", "Bar"], ["pie", "Pie"], ["line", "Line"]], block.chart, "select") + "</div>";
      html += '<div class="field"><label>' + esc(t("binding")) + "</label>" + selectHtml("binding", [["findingsBySeverity", t("findingsBySeverity")], ["static", t("staticValues")]], block.binding, "select") + "</div>";
      if (block.binding === "static") {
        html += block.categories.map(function (item, index) {
          return '<div class="grid-2"><input data-bind="block" data-key="cat-label" data-index="' + index + '" type="text" value="' + esc(item.label) + '"><input data-bind="block" data-key="cat-value" data-index="' + index + '" type="number" min="0" value="' + item.value + '"></div>';
        }).join("");
      } else html += '<p class="mini">' + esc(t("sampleBanner")) + "</p>";
    } else if (block.type === "table") {
      html += '<div class="field"><label>' + esc(t("tableMode")) + "</label>" + selectHtml("mode", [["findings", t("fromFindings")], ["static", t("customRows")]], block.mode, "select") + "</div>";
      if (block.mode === "static") {
        html += '<button type="button" class="text-btn" data-add-row>' + esc(t("addRow")) + "</button>";
        html += (block.rows || []).map(function (row, index) {
          return '<input data-bind="block" data-key="row" data-index="' + index + '" type="text" value="' + esc(row.join(" | ")) + '">';
        }).join("");
      }
    } else if (block.type === "callout") {
      html += '<div class="field"><label>' + esc(t("tone")) + "</label>" + selectHtml("tone", [["info", t("toneInfo")], ["warning", t("toneWarning")], ["note", t("toneNote")]], block.tone, "select") + "</div>";
      html += '<div class="field"><label>' + esc(t("calloutText")) + '</label><textarea data-bind="block" data-key="text">' + esc(block.text || "") + "</textarea></div>";
    } else if (block.type === "image") {
      html += '<label class="text-btn file-btn">' + esc(t("blockImage")) + '<input data-bind="image-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp"></label>';
      html += '<div class="field"><label>' + esc(t("width")) + '</label><input data-bind="block" data-key="widthMm" type="number" min="20" max="170" value="' + (block.widthMm || 120) + '"></div>';
      html += '<div class="field"><label>' + esc(t("caption")) + '</label><input data-bind="block" data-key="caption" type="text" value="' + esc(block.caption || "") + '"></div>';
    } else if (block.type === "drawing") {
      html += '<div class="seg">' + [
        ["select", "↖"], ["rect", "▭"], ["ellipse", "◯"], ["arrow", "→"], ["pen", "✎"], ["text", "T"]
      ].map(function (item) {
        return '<button type="button" data-tool="' + item[0] + '" aria-pressed="' + (tool === item[0] ? "true" : "false") + '">' + item[1] + "</button>";
      }).join("") + "</div>";
      html += '<div class="field"><label>' + esc(t("svgPaste")) + '</label><textarea id="svg-source" placeholder="' + esc(t("svgHelp")) + '">' + esc(block.svg || "") + "</textarea></div>";
      html += '<button type="button" id="apply-svg" class="text-btn">' + esc(t("applySvg")) + "</button>";
    } else {
      html += '<p class="hint">' + esc(uiLang === "pt-BR" ? "Bloco não suportado. Ele permanece no arquivo e o ICARUS o omite." : "Unsupported block. It stays in the file and ICARUS leaves it out.") + "</p>";
    }
    return html;
  }

  function documentPanel() {
    var fields = state.fields;
    function field(key, labelKey) {
      return '<div class="field"><label>' + esc(t(labelKey)) + '</label><input data-field="' + key + '" type="text" value="' + esc(fields[key] || "") + '"></div>';
    }
    var presets = Object.keys(P.PRESETS).map(function (name) {
      var color = P.PRESETS[name].primary;
      return '<button type="button" class="swatch" data-preset="' + name + '" style="background:' + color + '" title="' + name + '"></button>';
    }).join("");
    return '<h3>' + esc(t("documentTab")) + "</h3>" +
      '<div class="grid-2">' + field("companyName", "company") + field("documentTitle", "documentTitle") + "</div>" +
      '<div class="grid-2">' + field("author", "author") + field("classification", "classification") + "</div>" +
      '<div class="grid-2">' + field("project", "project") + field("component", "component") + "</div>" +
      '<div class="grid-2">' + field("environment", "environment") + field("assessmentPeriod", "period") + "</div>" +
      field("method", "method") +
      '<div class="field"><label>' + esc(t("primary")) + '</label><div class="color-row"><input data-bind="color" data-key="primary" type="color" value="' + state.colors.primary + '"><input data-bind="color" data-key="primary" type="text" value="' + esc(state.colors.primary) + '"></div></div>' +
      '<div class="field"><label>' + esc(t("secondary")) + '</label><div class="color-row"><input data-bind="color" data-key="secondary" type="color" value="' + state.colors.secondary + '"><input data-bind="color" data-key="secondary" type="text" value="' + esc(state.colors.secondary) + '"></div></div>' +
      '<div class="swatch-row">' + presets + "</div>" +
      '<div class="field"><label>' + esc(t("severityScale")) + "</label>" + selectHtml("severityScale", [["cvss", t("scaleCvss")], ["simple", t("scaleSimple")], ["four", t("scaleFour")]], state.severityScale, "select") + "</div>" +
      '<div class="field"><label>' + esc(t("layoutLabel")) + "</label>" + selectHtml("findingLayout", [["ELEVATED_CARD", t("layoutCards")], ["TABULAR", t("layoutTable")]], state.findingLayout, "select") + "</div>" +
      '<p class="mini">' + esc(t("placeholders")) + ": {{author}} {{project}} {{finding_count}} {{finding_types}}</p>";
  }

  function selectHtml(key, options, value, bind) {
    return '<select data-bind="' + bind + '" data-key="' + key + '">' + options.map(function (option) {
      return '<option value="' + esc(option[0]) + '"' + (option[0] === value ? " selected" : "") + ">" + esc(option[1]) + "</option>";
    }).join("") + "</select>";
  }

  function writeStyle(el) {
    var style = currentStyle();
    if (!style) return;
    var key = el.dataset.key;
    if (key === "fontFamily") style.fontFamily = el.value;
    else if (key === "fontSize" || key === "spaceBefore" || key === "spaceAfter" || key === "lineHeight") {
      var n = Number(el.value);
      if (isFinite(n)) style[key] = n;
    } else if (key === "color" && (el.type === "color" || /^#[0-9a-fA-F]{6}$/.test(el.value))) {
      style.color = P.hex(el.value, style.color);
      if (el.type === "color") {
        var text = el.parentNode.querySelector('input[type="text"]');
        if (text) text.value = style.color;
      }
    } else return;
    var role = state.selection.role;
    state.styles[role] = P.normalizeStyle(style);
  }

  function writeLogo(el) {
    var key = el.dataset.key;
    var n = Number(el.value);
    if (key === "hw") state.logoBox.header.w = n;
    else if (key === "hh") state.logoBox.header.h = n;
    else state.logoBox.cover[key] = n;
    state.logoBox = P.normalizeLogoBox(state.logoBox);
  }

  function writePage(el) {
    var key = el.dataset.key;
    if (key.indexOf("margin") === 0) state.page[key] = Number(el.value);
    state.page = P.normalizePage(state.page);
  }

  function writeBlock(el) {
    var section = selectedSection();
    if (!section || !section.block) return;
    var block = section.block;
    var key = el.dataset.key;
    var index = Number(el.dataset.index);
    if (key === "text") block.text = el.value;
    else if (key === "caption") block.caption = el.value;
    else if (key === "widthMm") block.widthMm = Number(el.value);
    else if (key === "cat-label" && block.categories[index]) block.categories[index].label = el.value;
    else if (key === "cat-value" && block.categories[index]) block.categories[index].value = Number(el.value);
    else if (key === "row" && block.rows[index]) block.rows[index] = el.value.split("|").map(function (cell) { return cell.trim(); });
    section.block = P.normalizeBlock(block);
  }

  function writeSelect(el) {
    var key = el.dataset.key;
    if (key === "fontFamily") { writeStyle(el); return; }
    if (key === "severityScale") { state.severityScale = el.value; return; }
    if (key === "findingLayout") { state.findingLayout = el.value; return; }
    if (key === "coverStyle") { var cover = coverSection(); if (cover) cover.coverStyle = el.value; return; }
    var section = selectedSection();
    if (!section || !section.block) return;
    section.block[key] = el.value;
    section.block = P.normalizeBlock(section.block);
  }

  function writeCheck(el) {
    var key = el.dataset.key;
    if (key === "headerShow") { state.logoBox.header.show = el.checked; return; }
    if (key === "showHeader" || key === "showFooter" || key === "showPageNumbers") { state.page[key] = el.checked; return; }
    var section = selectedSection();
    if (!section) return;
    if (key === "required") {
      section.required = el.checked;
      if (section.required) section.enabled = true;
    }
    if (key === "enabled" && !section.required) section.enabled = el.checked;
  }

  function writeThemeColor(el) {
    var hex = P.hex(el.value, state.colors[el.dataset.key]);
    state.colors[el.dataset.key] = hex;
  }

  function setAlign(value) {
    if (value.indexOf("header:") === 0) {
      edit(function () { state.logoBox.header.align = value.slice(7); });
      return;
    }
    var style = currentStyle();
    if (!style) return;
    edit(function () { style.align = value; state.styles = P.normalizeStyles(state.styles); });
  }

  function toggleStyle(key) {
    var style = currentStyle();
    if (!style) return;
    edit(function () {
      if (key === "bold") style.weight = style.weight === "bold" ? "regular" : "bold";
      if (key === "italic") style.italic = !style.italic;
      if (key === "underline") style.underline = !style.underline;
      state.styles = P.normalizeStyles(state.styles);
    });
  }

  function addFromPalette(id, kind) {
    edit(function () {
      if (kind === "block") {
        var block = P.makeBlock({ type: id, title: t(BLOCKS.filter(function (item) { return item.type === id; })[0].key) });
        if (id === "callout") block.block.text = uiLang === "pt-BR" ? "Nota para o leitor." : "Note for the reader.";
        if (id === "drawing") {
          block.block.shapes = [
            { type: "rect", x: 16, y: 24, w: 140, h: 64, fill: "#E8EEF8", stroke: "#2563EB" },
            { type: "arrow", x1: 156, y1: 56, x2: 230, y2: 56, stroke: "#1A1A1A" },
            { type: "ellipse", x: 240, y: 28, w: 90, h: 56, fill: "#F8FAFC", stroke: "#1A1A1A" }
          ];
        }
        var at = state.sections.findIndex(function (section) { return section.id === (state.selection && state.selection.id); });
        if (at < 0) state.sections.push(block);
        else state.sections.splice(at + 1, 0, block);
        state.selection = { role: "block", id: block.id };
        tab = "page";
        return;
      }
      var existing = findSection(id);
      if (existing) {
        existing.enabled = true;
        state.selection = { role: "page", id: id };
      } else {
        var created = P.makeTemplate({ locale: state.locale, severityScale: state.severityScale, enabled: [id], coverStyle: (coverSection() || {}).coverStyle }).sections.filter(function (section) { return section.id === id; })[0];
        created.enabled = true;
        state.sections.push(created);
        state.selection = { role: "page", id: id };
      }
      tab = "page";
    });
  }

  function toggleRequired(id) {
    var section = findSection(id);
    if (!section) return;
    edit(function () {
      section.required = !section.required;
      if (section.required) section.enabled = true;
      state.selection = { role: "page", id: id };
      tab = "page";
    });
  }

  function removeSection(id) {
    var section = findSection(id);
    if (!section || section.required) { say("lockedRemove"); return; }
    edit(function () {
      state.sections = state.sections.filter(function (item) { return item.id !== id; });
      state.selection = { role: "coverTitle", id: "COVER" };
    });
  }

  function addTableRow() {
    var section = selectedSection();
    if (!section || !section.block) return;
    edit(function () {
      var width = section.block.columns.length || 1;
      section.block.rows.push(new Array(width).fill(""));
    });
  }

  function applyPreset(name) {
    var preset = P.PRESETS[name];
    if (!preset) return;
    edit(function () {
      state.colors.primary = preset.primary;
      state.colors.secondary = preset.secondary;
    });
  }

  function applySvg() {
    var section = selectedSection();
    var source = $("svg-source");
    if (!section || !section.block || !source) return;
    edit(function () {
      section.block.svg = source.value;
      section.block = P.normalizeBlock(section.block);
    });
  }

  function onDragStart(e) {
    var row = e.target.closest("[data-id]");
    if (!row) return;
    dragId = row.getAttribute("data-id");
    row.classList.add("dragging");
    e.dataTransfer.setData("text/plain", dragId);
    e.dataTransfer.effectAllowed = "move";
  }

  function onDragOver(e) {
    if (!dragId) return;
    e.preventDefault();
    var row = e.target.closest("[data-id]");
    if (!row || row.getAttribute("data-id") === dragId) return;
    var list = $("page-list");
    var rows = Array.prototype.slice.call(list.children);
    var from = rows.indexOf(list.querySelector('[data-id="' + dragId + '"]'));
    var to = rows.indexOf(row);
    if (from < 0 || to < 0) return;
    if (from < to) row.after(rows[from]);
    else row.before(rows[from]);
  }

  function onDrop(e) {
    if (!dragId) return;
    e.preventDefault();
    var order = Array.prototype.map.call($("page-list").children, function (row) { return row.getAttribute("data-id"); });
    edit(function () {
      state.sections.sort(function (a, b) { return order.indexOf(a.id) - order.indexOf(b.id); });
    });
    dragId = "";
  }

  function onPointerDown(e) {
    var resize = e.target.closest("[data-resize]");
    var logo = e.target.closest('[data-role="logo"]');
    if (logo && (resize || e.target.closest(".logo-abs"))) {
      e.preventDefault();
      dragLogo(e, !!resize);
      return;
    }
    var svg = e.target.closest("svg.diagram");
    if (!svg || tool === "select") return;
    drawShape(e, svg);
  }

  function sheetScale(el) {
    var sheet = el.closest(".sheet") || el;
    var rect = sheet.getBoundingClientRect();
    return rect.width / 210;
  }

  function dragLogo(e, resize) {
    var startX = e.clientX;
    var startY = e.clientY;
    var box = { x: state.logoBox.cover.x, y: state.logoBox.cover.y, w: state.logoBox.cover.w, h: state.logoBox.cover.h };
    var scale = sheetScale(e.target);
    function move(ev) {
      var dx = (ev.clientX - startX) / scale;
      var dy = (ev.clientY - startY) / scale;
      if (resize) {
        state.logoBox.cover.w = box.w + dx;
        state.logoBox.cover.h = box.h + dy;
      } else {
        state.logoBox.cover.x = box.x + dx;
        state.logoBox.cover.y = box.y + dy;
      }
      state.logoBox = P.normalizeLogoBox(state.logoBox);
      renderCanvas();
    }
    function up() {
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerup", up);
      state.selection = { role: "logo", id: "COVER" };
      commit();
      persist();
      renderInspector();
    }
    document.addEventListener("pointermove", move);
    document.addEventListener("pointerup", up);
  }

  function drawShape(e, svg) {
    var sectionId = svg.closest("[data-section]").getAttribute("data-section");
    var section = findSection(sectionId);
    if (!section || !section.block) return;
    e.preventDefault();
    var start = svgPoint(svg, e);
    var shape;
    if (tool === "text") {
      var value = window.prompt(t("drawText"), "Label");
      if (!value) return;
      edit(function () {
        section.block.shapes.push({ type: "text", x: start.x, y: start.y, text: value, fill: "#1A1A1A", size: 16 });
        section.block = P.normalizeBlock(section.block);
      });
      return;
    }
    if (tool === "pen") shape = { type: "freehand", points: [[round(start.x), round(start.y)]], stroke: "#1A1A1A" };
    else if (tool === "arrow") shape = { type: "arrow", x1: start.x, y1: start.y, x2: start.x, y2: start.y, stroke: "#1A1A1A" };
    else if (tool === "ellipse") shape = { type: "ellipse", x: start.x, y: start.y, w: 1, h: 1, fill: "#E8EEF8", stroke: "#1A1A1A" };
    else shape = { type: "rect", x: start.x, y: start.y, w: 1, h: 1, fill: "#E8EEF8", stroke: "#2563EB" };
    section.block.shapes.push(shape);
    function move(ev) {
      var point = svgPoint(svg, ev);
      if (shape.type === "freehand") shape.points.push([round(point.x), round(point.y)]);
      else if (shape.type === "arrow") { shape.x2 = point.x; shape.y2 = point.y; }
      else { shape.w = Math.max(2, point.x - start.x); shape.h = Math.max(2, point.y - start.y); }
      renderCanvas();
    }
    function up() {
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerup", up);
      section.block = P.normalizeBlock(section.block);
      commit();
      persist();
      renderAll();
    }
    document.addEventListener("pointermove", move);
    document.addEventListener("pointerup", up);
  }

  function svgPoint(svg, event) {
    var point = svg.createSVGPoint();
    point.x = event.clientX;
    point.y = event.clientY;
    var ctm = svg.getScreenCTM();
    if (!ctm) return { x: 0, y: 0 };
    var local = point.matrixTransform(ctm.inverse());
    return { x: Math.max(0, local.x), y: Math.max(0, local.y) };
  }

  function round(n) { return Math.round(n * 10) / 10; }

  function readLogo(file, intoBlock) {
    if (!file) return;
    if (!/^image\/(png|jpeg|gif|webp)$/.test(file.type)) { say("logoBad"); return; }
    if (file.size > 450 * 1024) { say("logoBig"); return; }
    var reader = new FileReader();
    reader.onload = function () {
      var url = P.sanitizeLogo(String(reader.result || ""));
      if (!url) { say("logoBad"); return; }
      edit(function () {
        if (intoBlock) {
          var section = selectedSection();
          if (section && section.block && section.block.type === "image") {
            section.block.dataUrl = url;
            section.block = P.normalizeBlock(section.block);
          }
        } else state.logoDataUrl = url;
      });
    };
    reader.readAsDataURL(file);
  }

  function onFileDrop(e) {
    var file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (!file) return;
    e.preventDefault();
    readLogo(file, false);
  }

  function setLang(lang) {
    uiLang = lang === "pt-BR" ? "pt-BR" : "en";
    storageSet(LANG_KEY, uiLang);
    renderAll();
  }

  function setZoom(value) {
    zoom = Math.max(0.4, Math.min(1.5, Math.round(value * 100) / 100));
    applyZoom();
  }

  function applyZoom() {
    var stack = $("stack");
    if (!stack) return;
    stack.style.zoom = String(zoom);
    $("zoom-label").textContent = Math.round(zoom * 100) + "%";
  }

  function fitZoom() {
    var probe = document.createElement("div");
    probe.style.width = "210mm";
    probe.style.position = "absolute";
    probe.style.visibility = "hidden";
    document.body.appendChild(probe);
    var px = probe.getBoundingClientRect().width || 794;
    probe.remove();
    var available = $("artboard").clientWidth - 48;
    setZoom(available / px);
  }

  function setMobile(which) {
    $("workspace").setAttribute("data-mobile", which);
    document.querySelectorAll(".mobile-tabs [data-mobile]").forEach(function (btn) {
      btn.setAttribute("aria-pressed", String(btn.getAttribute("data-mobile") === which));
    });
  }

  function toggleMenu() {
    $("export-menu").hidden = !$("export-menu").hidden;
  }

  function doExport(kind) {
    var profile = P.toReportProfile(state);
    var safe = (state.name || "template").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "template";
    if (kind === "json") download(safe + ".json", JSON.stringify(profile, null, 2), "application/json");
    else if (kind === "md") download(safe + ".md", P.toMarkdown(state), "text/markdown");
    else if (kind === "html") download(safe + ".html", htmlDocument(), "text/html");
    else printPages();
  }

  function htmlDocument() {
    var clone = $("stack").cloneNode(true);
    clone.querySelectorAll(".is-selected").forEach(function (el) { el.classList.remove("is-selected"); });
    clone.querySelectorAll(".handle").forEach(function (el) { el.remove(); });
    clone.style.zoom = "1";
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" + esc(state.fields.documentTitle || state.name) +
      "</title><style>" + exportCss() + "</style></head><body>" + clone.outerHTML + "</body></html>";
  }

  function exportCss() {
    return "body{margin:0;background:#fff} .stack{display:block} .sheet{width:210mm;height:297mm;page-break-after:always;position:relative;overflow:hidden} " +
      ".sheet-head,.sheet-foot{position:absolute;display:flex;justify-content:space-between;color:#667085;font:9pt Helvetica,Arial,sans-serif} " +
      ".sheet-body{position:absolute;overflow:hidden} .cover-band{position:absolute;left:0;right:0;top:0;height:56mm} " +
      ".logo-abs{position:absolute} .logo-abs img,.sheet-head img{width:100%;height:100%;object-fit:contain} " +
      "table{width:100%;border-collapse:collapse} th,td{border:1px solid #e6e8ee;padding:4px 6px;vertical-align:top} " +
      ".finding-card{border:1px solid #e6e8ee;border-left-width:4px;border-radius:8px;padding:8px 10px;margin:8px 0} " +
      ".callout{border:1px solid #d7e2f5;border-left-width:4px;padding:8px;border-radius:8px} .sev{border-radius:99px;padding:0 6px;font-weight:700} " +
      ".chart,.diagram{width:100%;height:auto} @page{size:A4;margin:0}";
  }

  function printPages() {
    $("stack").style.zoom = "1";
    window.print();
  }

  function download(name, text, type) {
    var blob = new Blob([text], { type: type + ";charset=utf-8" });
    var url = URL.createObjectURL(blob);
    var link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  function openAi() {
    $("ai-prompt").value = AI.buildPrompt(P.toReportProfile(state));
    $("ai-dialog").showModal();
  }

  function copyPrompt() {
    var text = $("ai-prompt").value;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { say("promptCopied"); }, function () { fallbackCopy(text); });
    } else fallbackCopy(text);
  }

  function fallbackCopy(text) {
    var area = $("ai-prompt");
    area.focus();
    area.select();
    try { document.execCommand("copy"); say("promptCopied"); } catch (err) { say(""); }
  }

  function applyAi() {
    var result = AI.parseResponse($("ai-answer").value);
    var list = $("ai-errors");
    if (!result.ok) {
      list.innerHTML = result.errors.map(function (line) { return "<li>" + esc(line) + "</li>"; }).join("");
      return;
    }
    list.innerHTML = "";
    $("ai-import-dialog").close();
    edit(function () {
      state = result.state;
      state.selection = { role: "coverTitle", id: "COVER" };
    });
  }

  function importFile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var result = AI.parseResponse(String(reader.result || ""));
      if (!result.ok) { say("importBad"); $("status").textContent = result.errors[0] || t("importBad"); return; }
      confirmBox(t("confirmImportTitle"), t("confirmImportBody", { name: result.state.name }), t("importAction")).then(function (ok) {
        if (!ok) return;
        edit(function () {
          state = result.state;
          state.selection = { role: "coverTitle", id: "COVER" };
        });
        say("importOk");
      });
    };
    reader.readAsText(file);
  }

  function pickStarter(id) {
    var tpl = (window.ICARUS_TEMPLATES || []).filter(function (item) { return item.templateId === id; })[0];
    if (!tpl) return;
    confirmBox(t("confirmTitle"), t("confirmBody", { name: tpl.name }), t("replace")).then(function (ok) {
      if (!ok) return;
      edit(function () { state = cloneTemplate(tpl); });
    });
  }

  function confirmBox(title, body, ok) {
    $("confirm-title").textContent = title;
    $("confirm-body").textContent = body;
    $("confirm-ok").textContent = ok;
    var dialog = $("confirm-dialog");
    dialog.showModal();
    return new Promise(function (resolve) {
      dialog.addEventListener("close", function once() {
        dialog.removeEventListener("close", once);
        resolve(dialog.returnValue === "ok");
      });
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
