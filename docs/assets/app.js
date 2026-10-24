/**
 * ICARUS report template builder UI.
 * Part of ICARUS, MIT License. No network calls except links the visitor chooses to open.
 */
(function () {
  var P = window.IcarusProfile;
  var I18N = window.IcarusI18n;
  var CFG = window.ICARUS_SITE_CONFIG || {};
  var DRAFT_KEY = "icarus.templateBuilder.draft.v1";
  var LANG_KEY = "icarus.templateBuilder.lang";
  var TOKENS = ["project", "component", "environment", "author", "reviewer", "date", "classification", "team", "method", "assessment_period", "requester", "finding_count", "finding_types"];
  var FIELD_INPUTS = {
    company: "companyName",
    "document-title": "documentTitle",
    author: "author",
    reviewer: "reviewer",
    approver: "approver",
    classification: "classification",
    environment: "environment",
    period: "assessmentPeriod",
    method: "method",
    team: "team",
    component: "component",
    requester: "requester",
    owner: "owner",
    project: "project",
    version: "version",
    date: "date"
  };

  var uiLang = "en";
  var state = null;
  var dirty = false;
  var statusKey = "";
  var saveTimer = 0;
  var dragId = "";

  function t(key, vars) { return I18N.t(uiLang, key, vars); }

  function $(id) { return document.getElementById(id); }

  function h(tag, attrs) {
    var node = document.createElement(tag);
    var extra = Array.prototype.slice.call(arguments, 2);
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value == null || value === false) return;
      if (key === "class") node.className = value;
      else if (value === true) node.setAttribute(key, "");
      else node.setAttribute(key, String(value));
    });
    extra.forEach(function (child) {
      if (child == null || child === false) return;
      node.append(child.nodeType ? child : document.createTextNode(String(child)));
    });
    return node;
  }

  function storageGet(key) {
    try { return localStorage.getItem(key); } catch (err) { return null; }
  }

  function storageSet(key, value) {
    try { localStorage.setItem(key, value); return true; } catch (err) { return false; }
  }

  function storageRemove(key) {
    try { localStorage.removeItem(key); } catch (err) { /* private mode */ }
  }

  function initialLang() {
    var saved = storageGet(LANG_KEY);
    if (saved === "pt-BR" || saved === "en") return saved;
    var nav = (navigator.language || "en").toLowerCase();
    return nav.indexOf("pt") === 0 ? "pt-BR" : "en";
  }

  function templateById(id) {
    var list = window.ICARUS_TEMPLATES || [];
    for (var i = 0; i < list.length; i++) if (list[i].templateId === id) return list[i];
    return list[0];
  }

  function freshFrom(id) {
    var next = P.clone(templateById(id));
    next.profileId = "user:" + (window.crypto && crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36));
    next.fields.date = P.today();
    next.selectedId = "EXECUTIVE_SUMMARY";
    return next;
  }

  function applyI18n() {
    document.documentElement.lang = uiLang;
    document.title = t("docTitle");
    var meta = $("meta-desc");
    if (meta) meta.setAttribute("content", t("metaDesc"));
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      el.textContent = t(el.getAttribute("data-i18n"));
    });
    document.querySelectorAll("[data-i18n-aria]").forEach(function (el) {
      el.setAttribute("aria-label", t(el.getAttribute("data-i18n-aria")));
    });
    document.querySelectorAll("[data-set-lang]").forEach(function (btn) {
      btn.setAttribute("aria-pressed", btn.getAttribute("data-set-lang") === uiLang ? "true" : "false");
    });
    var bapp = $("bapp-link");
    if (bapp) bapp.textContent = P.isPublicUrl(CFG.bappUrl) ? t("bapp") : t("bappHome");
  }

  function renderStatus() {
    var el = $("status");
    el.replaceChildren();
    if (!statusKey) return;
    el.append(document.createTextNode(t(statusKey) + " "));
    if (statusKey === "restored") {
      var button = h("button", { type: "button", class: "btn btn-small" }, t("discard"));
      button.addEventListener("click", function () { discardDraft(); });
      el.append(button);
    }
  }

  function setStatus(key) {
    statusKey = key || "";
    renderStatus();
  }

  function selectedSection() {
    var found = null;
    (state.sections || []).forEach(function (section) {
      if (section.id === state.selectedId) found = section;
    });
    return found || state.sections[0];
  }

  function sectionOn(section) { return !!(section && (section.enabled || section.required)); }

  function renderStarters() {
    var root = $("starters");
    root.replaceChildren.apply(root, (window.ICARUS_TEMPLATES || []).map(function (tpl) {
      var button = h("button", {
        type: "button",
        class: "starter" + (state.templateId === tpl.templateId ? " is-active" : ""),
        "aria-pressed": state.templateId === tpl.templateId ? "true" : "false"
      });
      button.append(h("span", { class: "starter-name" }, tpl.name));
      button.append(h("span", { class: "starter-desc" }, t("starter." + tpl.templateId)));
      button.addEventListener("click", function () { chooseStarter(tpl.templateId); });
      return button;
    }));
  }

  function renderSectionList() {
    var list = $("section-list");
    var focus = document.activeElement && document.activeElement.getAttribute ? document.activeElement.getAttribute("data-focus-key") : "";
    list.replaceChildren.apply(list, state.sections.map(function (section, index) {
      var title = (section.params && section.params.title) || P.titleFor(section.id, state.locale);
      var typeName = P.titleFor(section.id, uiLang === "pt-BR" ? "pt-BR" : "en");
      var showKicker = title.trim().toLowerCase() !== typeName.trim().toLowerCase();
      var row = h("li", {
        class: "section-row" + (state.selectedId === section.id ? " is-selected" : "") + (sectionOn(section) ? "" : " is-off"),
        "data-id": section.id
      });
      var handle = h("button", {
        type: "button",
        class: "handle",
        draggable: "true",
        "data-drag": "1",
        "data-focus-key": "drag-" + section.id,
        "aria-label": t("reorder") + " " + title
      }, "⋮⋮");
      var box = h("input", {
        type: "checkbox",
        "data-action": "toggle",
        "data-focus-key": "toggle-" + section.id,
        "aria-label": t("include") + " " + title
      });
      box.checked = sectionOn(section);
      if (section.required) {
        box.disabled = true;
        box.title = t("requiredLock");
      }
      var check = h("label", { class: "check" }, box);
      var name = h("button", {
        type: "button",
        class: "section-name",
        "data-action": "select",
        "aria-label": t("selectSection") + " " + title
      });
      if (showKicker) name.append(h("span", { class: "section-kicker" }, typeName));
      name.append(h("span", { class: "section-label", "data-label-for": section.id }, title));
      var up = h("button", {
        type: "button",
        class: "icon-btn",
        "data-action": "up",
        "data-focus-key": "up-" + section.id,
        "aria-label": t("moveUp") + " " + title
      }, "↑");
      var down = h("button", {
        type: "button",
        class: "icon-btn",
        "data-action": "down",
        "data-focus-key": "down-" + section.id,
        "aria-label": t("moveDown") + " " + title
      }, "↓");
      if (index === 0) up.disabled = true;
      if (index === state.sections.length - 1) down.disabled = true;
      row.append(handle, check, name, up, down);
      return row;
    }));
    if (focus) {
      var next = list.querySelector('[data-focus-key="' + cssEscape(focus) + '"]');
      if (next && !next.disabled) next.focus();
    }
  }

  function renderSectionEditor() {
    var root = $("section-editor");
    var section = selectedSection();
    if (!section) { root.replaceChildren(); return; }
    state.selectedId = section.id;
    var parts = [];
    if (section.kind === "cover") {
      parts.push(h("p", { class: "hint" }, t("coverNote")));
      parts.push(radioPair("coverStyle", t("coverStyle"), [
        ["GRADIENT_HERO", t("styleHero")],
        ["HEADER_BAND", t("styleBand")]
      ], section.coverStyle || "GRADIENT_HERO", function (value) {
        section.coverStyle = value;
        touch();
      }));
    } else if (section.kind === "table") {
      parts.push(h("p", { class: "hint" }, t("tableNote")));
      parts.push(titleField(section));
    } else if (section.kind === "detail") {
      parts.push(h("p", { class: "hint" }, t("detailNote")));
      parts.push(titleField(section));
      parts.push(radioPair("findingLayout", t("layoutLabel"), [
        ["ELEVATED_CARD", t("layoutCards")],
        ["TABULAR", t("layoutTable")]
      ], state.findingLayout, function (value) {
        state.findingLayout = value;
        touch();
      }));
    } else {
      if (sectionOn(section) && !(section.params.content || "").trim()) {
        parts.push(h("p", { class: "warn" }, t("emptyBody")));
      }
      parts.push(titleField(section));
      parts.push(bodyField(section));
      parts.push(chips());
      if (section.id === "RISK_RATING" || section.id === "CVSS4_MATRIX") {
        var reset = h("button", { type: "button", class: "btn" }, t("resetLegend"));
        reset.addEventListener("click", function () {
          section.params.content = P.legendFor(state.severityScale, state.locale);
          dirty = true;
          renderSectionEditor();
          renderPreview();
          saveSoon();
        });
        parts.push(reset);
      }
    }
    root.replaceChildren.apply(root, parts);
  }

  function titleField(section) {
    var wrap = h("div", { class: "field" });
    wrap.append(h("label", { for: "section-title" }, t("sectionTitle")));
    var input = h("input", { id: "section-title", type: "text", maxlength: "160" });
    input.value = section.params.title || "";
    input.addEventListener("input", function () {
      section.params.title = input.value;
      var label = document.querySelector('[data-label-for="' + cssEscape(section.id) + '"]');
      if (label) label.textContent = input.value || P.titleFor(section.id, state.locale);
      touch();
    });
    wrap.append(input);
    return wrap;
  }

  function bodyField(section) {
    var wrap = h("div", { class: "field" });
    wrap.append(h("label", { for: "section-body" }, t("sectionBody")));
    var area = h("textarea", { id: "section-body", lang: state.locale });
    area.value = section.params.content || "";
    area.addEventListener("input", function () {
      section.params.content = area.value;
      var warn = $("section-editor").querySelector(".warn");
      var empty = sectionOn(section) && !area.value.trim();
      if (empty && !warn) $("section-editor").insertBefore(h("p", { class: "warn" }, t("emptyBody")), $("section-editor").firstChild);
      if (!empty && warn) warn.remove();
      touch();
    });
    wrap.append(area);
    return wrap;
  }

  function chips() {
    var wrap = h("div", {});
    wrap.append(h("p", { class: "hint" }, t("placeholders")));
    var row = h("div", { class: "chips" });
    TOKENS.forEach(function (token) {
      var button = h("button", { type: "button", class: "chip" }, "{{" + token + "}}");
      button.addEventListener("click", function () { insertToken(token); });
      row.append(button);
    });
    wrap.append(row);
    return wrap;
  }

  function radioPair(name, legend, options, current, onChange) {
    var set = h("fieldset", {});
    set.append(h("legend", {}, legend));
    options.forEach(function (option) {
      var label = h("label", { class: "choice" });
      var input = h("input", { type: "radio", name: name, value: option[0] });
      if (current === option[0]) input.checked = true;
      input.addEventListener("change", function () { if (input.checked) onChange(option[0]); });
      var text = h("span", {});
      text.append(h("strong", {}, option[1]));
      label.append(input, text);
      set.append(label);
    });
    return set;
  }

  function insertToken(token) {
    var area = $("section-body");
    if (!area) return;
    var insert = "{{" + token + "}}";
    var start = area.selectionStart || 0;
    var end = area.selectionEnd || 0;
    area.value = area.value.slice(0, start) + insert + area.value.slice(end);
    var pos = start + insert.length;
    area.focus();
    area.setSelectionRange(pos, pos);
    area.dispatchEvent(new Event("input", { bubbles: true }));
  }

  function renderPreview() {
    $("preview").innerHTML = P.renderReport(state, { level: 2 });
  }

  function renderSupport() {
    var box = $("support");
    var links = $("support-links");
    var donations = CFG.donations || {};
    var items = [
      ["buyMeACoffee", "Buy Me a Coffee"],
      ["kofi", "Ko-fi"],
      ["githubSponsors", "GitHub Sponsors"]
    ].filter(function (item) { return P.isPublicUrl(donations[item[0]]); });
    links.replaceChildren();
    if (!items.length) {
      box.hidden = true;
      return;
    }
    items.forEach(function (item, index) {
      if (index) links.append(document.createTextNode(" · "));
      var anchor = h("a", { href: donations[item[0]].trim(), target: "_blank", rel: "noopener noreferrer" }, item[1]);
      links.append(anchor);
    });
    box.hidden = false;
  }

  function wireExternalLinks() {
    var repo = $("repo-link");
    var docs = $("docs-link");
    var bapp = $("bapp-link");
    if (P.isPublicUrl(CFG.repoUrl)) repo.href = CFG.repoUrl.trim();
    if (P.isPublicUrl(CFG.docsUrl)) docs.href = CFG.docsUrl.trim();
    var listing = P.isPublicUrl(CFG.bappUrl) ? CFG.bappUrl.trim() : (CFG.bappStoreHome || "https://portswigger.net/bappstore");
    bapp.href = listing;
    [repo, docs, bapp].forEach(function (anchor) {
      anchor.target = "_blank";
      anchor.rel = "noopener noreferrer";
    });
  }

  function fillForm() {
    $("template-name").value = state.name || "";
    Object.keys(FIELD_INPUTS).forEach(function (id) {
      var input = $(id);
      if (input) input.value = state.fields[FIELD_INPUTS[id]] || "";
    });
    $("content-lang").value = state.locale === "pt-BR" ? "pt-BR" : "en";
    setColorPair("primary", state.colors.primary);
    setColorPair("secondary", state.colors.secondary);
    P.SEVERITY_ALL.forEach(function (name) {
      var color = state.colors.severity[name] || P.SEVERITY_COLORS[name];
      var picker = $("sev-" + name);
      var hex = $("sev-hex-" + name);
      if (picker) picker.value = color;
      if (hex) hex.value = color;
    });
    document.querySelectorAll('input[name="scale"]').forEach(function (input) {
      input.checked = input.value === state.severityScale;
    });
    $("pol-evidence").checked = !!state.contentPolicy.includeEvidence;
    $("pol-request").checked = !!state.contentPolicy.includeHttpRequest;
    $("pol-response").checked = !!state.contentPolicy.includeHttpResponse;
    $("pol-toc").checked = !!state.contentPolicy.includeTocBookmarks;
    document.querySelectorAll("[data-field]").forEach(function (input) {
      input.checked = state.contentPolicy.findingFields.indexOf(input.getAttribute("data-field")) !== -1;
    });
    fillLogo();
  }

  function setColorPair(id, value) {
    var color = P.hex(value, "#000000");
    $(id).value = color;
    $(id + "-hex").value = color;
  }

  function fillLogo() {
    var img = $("logo-preview");
    var remove = $("remove-logo");
    var src = P.sanitizeLogo(state.logoDataUrl);
    if (!src) {
      img.hidden = true;
      img.removeAttribute("src");
      remove.hidden = true;
      return;
    }
    img.src = src;
    img.alt = state.fields.companyName || "Logo";
    img.hidden = false;
    remove.hidden = false;
  }

  function touch() {
    dirty = true;
    renderPreview();
    saveSoon();
  }

  function saveSoon() {
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(saveDraft, 350);
  }

  function saveDraft() {
    var payload = JSON.stringify({ version: 1, uiLang: uiLang, profile: state });
    if (!storageSet(DRAFT_KEY, payload)) setStatus("draftFull");
    storageSet(LANG_KEY, uiLang);
  }

  function readDraft() {
    var raw = storageGet(DRAFT_KEY);
    if (!raw) return null;
    try {
      var data = JSON.parse(raw);
      if (!data || data.version !== 1 || !data.profile) return null;
      return data;
    } catch (err) {
      return null;
    }
  }

  function confirmDialog(title, text, ok, cancel) {
    var dialog = $("confirm");
    $("confirm-title").textContent = title;
    $("confirm-text").textContent = text;
    $("confirm-ok").textContent = ok;
    $("confirm-cancel").textContent = cancel;
    return new Promise(function (resolve) {
      function done() {
        dialog.removeEventListener("close", done);
        resolve(dialog.returnValue === "ok");
      }
      dialog.addEventListener("close", done);
      dialog.showModal();
    });
  }

  function chooseStarter(id) {
    if (state.templateId === id && !dirty) return;
    confirmDialog(t("confirmTitle"), t("confirmBody", { name: templateById(id).name }), t("replace"), t("cancel")).then(function (ok) {
      if (!ok) return;
      state = freshFrom(id);
      dirty = false;
      setStatus("");
      refresh();
      saveDraft();
    });
  }

  function discardDraft() {
    confirmDialog(t("discardTitle"), t("discardBody"), t("discardAction"), t("cancel")).then(function (ok) {
      if (!ok) return;
      storageRemove(DRAFT_KEY);
      state = freshFrom("web-app");
      dirty = false;
      setStatus("");
      refresh();
    });
  }

  function refresh() {
    applyI18n();
    fillForm();
    renderStarters();
    renderSectionList();
    renderSectionEditor();
    renderPreview();
    renderSupport();
    renderStatus();
  }

  function moveSection(id, delta) {
    var index = indexOf(id);
    var next = index + delta;
    if (index < 0 || next < 0 || next >= state.sections.length) return;
    var item = state.sections.splice(index, 1)[0];
    state.sections.splice(next, 0, item);
    dirty = true;
    renderSectionList();
    renderPreview();
    saveSoon();
  }

  function reorder(fromId, toId) {
    if (!fromId || fromId === toId) return;
    var from = indexOf(fromId);
    var to = indexOf(toId);
    if (from < 0 || to < 0) return;
    var item = state.sections.splice(from, 1)[0];
    state.sections.splice(to, 0, item);
    dirty = true;
    renderSectionList();
    renderPreview();
    saveSoon();
  }

  function indexOf(id) {
    for (var i = 0; i < state.sections.length; i++) if (state.sections[i].id === id) return i;
    return -1;
  }

  function cssEscape(value) {
    if (window.CSS && CSS.escape) return CSS.escape(value);
    return String(value).replace(/[^a-zA-Z0-9_-]/g, "");
  }

  function onScale(scale) {
    var risk = null;
    state.sections.forEach(function (section) {
      if (section.id === "RISK_RATING" || section.id === "CVSS4_MATRIX") risk = section;
    });
    if (risk) {
      var previous = P.legendFor(state.severityScale, state.locale).trim();
      var next = P.legendFor(scale, state.locale);
      if (!risk.params.content.trim() || risk.params.content.trim() === previous) risk.params.content = next;
      else if (scale !== state.severityScale) setStatus("legendKept");
    }
    state.severityScale = scale;
    dirty = true;
    if (risk && state.selectedId === risk.id) renderSectionEditor();
    renderPreview();
    saveSoon();
  }

  function onLocale(locale) {
    var risk = null;
    state.sections.forEach(function (section) {
      if (section.id === "RISK_RATING" || section.id === "CVSS4_MATRIX") risk = section;
    });
    if (risk) {
      var previous = P.legendFor(state.severityScale, state.locale).trim();
      if (!risk.params.content.trim() || risk.params.content.trim() === previous) {
        risk.params.content = P.legendFor(state.severityScale, locale);
      }
    }
    state.locale = locale;
    dirty = true;
    renderSectionEditor();
    renderPreview();
    saveSoon();
  }

  function readLogo(file) {
    if (!file) return;
    var types = ["image/png", "image/jpeg", "image/gif", "image/webp"];
    if (types.indexOf(file.type) === -1) { setStatus("logoBad"); return; }
    if (file.size > 450 * 1024) { setStatus("logoBig"); return; }
    var reader = new FileReader();
    reader.onload = function () {
      var url = P.sanitizeLogo(String(reader.result || ""));
      if (!url) { setStatus("logoBad"); return; }
      state.logoDataUrl = url;
      fillLogo();
      setStatus("");
      touch();
    };
    reader.readAsDataURL(file);
  }

  function slug(name) {
    var value = String(name || "icarus-report-template").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    return value || "icarus-report-template";
  }

  function download(filename, mime, text) {
    var blob = new Blob([text], { type: mime });
    var url = URL.createObjectURL(blob);
    var link = h("a", { href: url, download: filename });
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
  }

  function exportJson() {
    var profile = P.toReportProfile(state);
    download(slug(state.name) + ".json", "application/json;charset=utf-8", JSON.stringify(profile, null, 2) + "\n");
  }

  function exportHtml() {
    download(slug(state.name) + ".html", "text/html;charset=utf-8", P.toHtmlDocument(state));
  }

  function exportMarkdown() {
    download(slug(state.name) + ".md", "text/markdown;charset=utf-8", P.toMarkdown(state));
  }

  function importProfile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var parsed;
      try {
        parsed = JSON.parse(String(reader.result || ""));
        parsed = P.fromReportProfile(parsed);
      } catch (err) {
        setStatus("importBad");
        return;
      }
      confirmDialog(t("confirmImportTitle"), t("confirmImportBody", { name: parsed.name }), t("importAction"), t("cancel")).then(function (ok) {
        if (!ok) return;
        state = parsed;
        dirty = false;
        setStatus("importOk");
        refresh();
        saveDraft();
      });
    };
    reader.readAsText(file);
  }

  function commitHex(raw, fallback, apply) {
    var next = P.hex(raw, "");
    if (!next) return fallback;
    apply(next);
    return next;
  }

  function bind() {
    $("editor").addEventListener("submit", function (event) { event.preventDefault(); });
    document.querySelectorAll("[data-set-lang]").forEach(function (button) {
      button.addEventListener("click", function () {
        uiLang = button.getAttribute("data-set-lang");
        storageSet(LANG_KEY, uiLang);
        refresh();
        saveDraft();
      });
    });
    $("template-name").addEventListener("input", function (event) {
      state.name = event.target.value;
      dirty = true;
      saveSoon();
    });
    $("editor").addEventListener("input", function (event) {
      var id = event.target.id;
      if (FIELD_INPUTS[id]) {
        state.fields[FIELD_INPUTS[id]] = event.target.value;
        if (id === "company") fillLogo();
        touch();
      }
    });
    $("content-lang").addEventListener("change", function (event) { onLocale(event.target.value); });
    document.querySelectorAll('input[name="scale"]').forEach(function (input) {
      input.addEventListener("change", function () { if (input.checked) onScale(input.value); });
    });
    $("primary").addEventListener("input", function (event) {
      state.colors.primary = P.hex(event.target.value, state.colors.primary);
      $("primary-hex").value = state.colors.primary;
      touch();
    });
    $("secondary").addEventListener("input", function (event) {
      state.colors.secondary = P.hex(event.target.value, state.colors.secondary);
      $("secondary-hex").value = state.colors.secondary;
      touch();
    });
    $("primary-hex").addEventListener("change", function (event) {
      var next = commitHex(event.target.value, state.colors.primary, function (value) { state.colors.primary = value; });
      setColorPair("primary", next || state.colors.primary);
      touch();
    });
    $("secondary-hex").addEventListener("change", function (event) {
      var next = commitHex(event.target.value, state.colors.secondary, function (value) { state.colors.secondary = value; });
      setColorPair("secondary", next || state.colors.secondary);
      touch();
    });
    document.querySelectorAll("[data-preset]").forEach(function (button) {
      button.addEventListener("click", function () {
        var preset = P.PRESETS[button.getAttribute("data-preset")];
        if (!preset) return;
        state.colors.primary = preset.primary;
        state.colors.secondary = preset.secondary;
        setColorPair("primary", preset.primary);
        setColorPair("secondary", preset.secondary);
        touch();
      });
    });
    document.querySelectorAll("[data-sev]").forEach(function (input) {
      input.addEventListener("input", function () {
        var name = input.getAttribute("data-sev");
        state.colors.severity[name] = P.hex(input.value, state.colors.severity[name]);
        $("sev-hex-" + name).value = state.colors.severity[name];
        touch();
      });
    });
    document.querySelectorAll("[data-sev-hex]").forEach(function (input) {
      input.addEventListener("change", function () {
        var name = input.getAttribute("data-sev-hex");
        var next = P.hex(input.value, "");
        if (!next) input.value = state.colors.severity[name];
        else {
          state.colors.severity[name] = next;
          $("sev-" + name).value = next;
          input.value = next;
          touch();
        }
      });
    });
    document.querySelectorAll("[data-policy]").forEach(function (input) {
      input.addEventListener("change", function () {
        state.contentPolicy[input.getAttribute("data-policy")] = input.checked;
        touch();
      });
    });
    document.querySelectorAll("[data-field]").forEach(function (input) {
      input.addEventListener("change", function () {
        var name = input.getAttribute("data-field");
        var fields = state.contentPolicy.findingFields.filter(function (item) { return item !== name; });
        if (input.checked) fields.push(name);
        state.contentPolicy.findingFields = P.FINDING_FIELDS.filter(function (item) { return fields.indexOf(item) !== -1; });
        touch();
      });
    });
    $("logo-file").addEventListener("change", function (event) {
      readLogo(event.target.files && event.target.files[0]);
      event.target.value = "";
    });
    $("remove-logo").addEventListener("click", function () {
      state.logoDataUrl = null;
      fillLogo();
      touch();
    });
    var list = $("section-list");
    list.addEventListener("click", function (event) {
      var button = event.target.closest("button");
      if (!button) return;
      var row = button.closest("[data-id]");
      if (!row) return;
      var id = row.getAttribute("data-id");
      var action = button.getAttribute("data-action");
      if (action === "select") {
        state.selectedId = id;
        renderSectionList();
        renderSectionEditor();
      } else if (action === "up") moveSection(id, -1);
      else if (action === "down") moveSection(id, 1);
    });
    list.addEventListener("change", function (event) {
      if (event.target.getAttribute("data-action") !== "toggle") return;
      var row = event.target.closest("[data-id]");
      var section = state.sections[indexOf(row.getAttribute("data-id"))];
      if (!section || section.required) return;
      section.enabled = event.target.checked;
      row.classList.toggle("is-off", !section.enabled);
      dirty = true;
      if (state.selectedId === section.id) renderSectionEditor();
      renderPreview();
      saveSoon();
    });
    list.addEventListener("dragstart", function (event) {
      var handle = event.target.closest("[data-drag]");
      if (!handle) return;
      dragId = handle.closest("[data-id]").getAttribute("data-id");
      event.dataTransfer.effectAllowed = "move";
      event.dataTransfer.setData("text/plain", dragId);
    });
    list.addEventListener("dragover", function (event) {
      var row = event.target.closest("[data-id]");
      if (!row || !dragId) return;
      event.preventDefault();
      list.querySelectorAll(".drop-target").forEach(function (node) { node.classList.remove("drop-target"); });
      row.classList.add("drop-target");
    });
    list.addEventListener("drop", function (event) {
      var row = event.target.closest("[data-id]");
      if (!row || !dragId) return;
      event.preventDefault();
      list.querySelectorAll(".drop-target").forEach(function (node) { node.classList.remove("drop-target"); });
      reorder(dragId, row.getAttribute("data-id"));
      dragId = "";
    });
    list.addEventListener("dragend", function () {
      dragId = "";
      list.querySelectorAll(".drop-target").forEach(function (node) { node.classList.remove("drop-target"); });
    });
    $("btn-json").addEventListener("click", exportJson);
    $("btn-html").addEventListener("click", exportHtml);
    $("btn-md").addEventListener("click", exportMarkdown);
    $("btn-print").addEventListener("click", function () { window.print(); });
    $("btn-import").addEventListener("click", function () { $("import-file").click(); });
    $("import-file").addEventListener("change", function (event) {
      importProfile(event.target.files && event.target.files[0]);
      event.target.value = "";
    });
    $("open-preview").addEventListener("click", function () {
      document.body.classList.add("show-preview");
      $("close-preview").focus();
    });
    $("close-preview").addEventListener("click", function () {
      document.body.classList.remove("show-preview");
      $("open-preview").focus();
    });
  }

  function boot() {
    uiLang = initialLang();
    var draft = readDraft();
    if (draft && draft.profile && Array.isArray(draft.profile.sections) && draft.profile.colors && draft.profile.fields && draft.profile.contentPolicy) {
      state = draft.profile;
      if (draft.uiLang === "en" || draft.uiLang === "pt-BR") uiLang = draft.uiLang;
      statusKey = "restored";
    } else {
      state = freshFrom("web-app");
      dirty = false;
    }
    $("report-style").textContent = P.reportCss();
    wireExternalLinks();
    bind();
    refresh();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
