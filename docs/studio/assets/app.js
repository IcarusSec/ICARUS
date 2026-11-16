/**
 * Shell around the page editor: language, share link, import, and export.
 * Part of ICARUS, MIT License. No tracking and no upload.
 */
(function () {
  var cfg = window.ICARUS_SITE_CONFIG || {};
  var LANG_KEY = "icarus.studio.lang";
  var studio = null;
  var lang = initialLang();

  function t(key) { return window.IcarusI18n.t(lang, key); }
  function esc(text) { return window.IcarusCanvas.esc(text); }

  function initialLang() {
    var saved = null;
    try { saved = localStorage.getItem(LANG_KEY); } catch (err) { saved = null; }
    if (saved === "en" || saved === "pt-BR") return saved;
    return /^pt\b/i.test(navigator.language || "") ? "pt-BR" : "en";
  }

  function isPublicUrl(url) {
    if (!url || !/^https:\/\//i.test(url)) return false;
    return !/YOUR_HANDLE|CHANGE_ME|PLACEHOLDER|example\.com/i.test(url);
  }

  function setStatus(text) {
    document.getElementById("status").textContent = text || "";
  }

  function b64url(buf) {
    var bytes = new Uint8Array(buf);
    var bin = "";
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  }
  function fromB64url(text) {
    var pad = text.length % 4 === 0 ? "" : "====".slice(text.length % 4);
    var bin = atob(text.replace(/-/g, "+").replace(/_/g, "/") + pad);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  async function gzipJson(obj) {
    if (!window.CompressionStream) return "";
    var stream = new Blob([JSON.stringify(obj)]).stream().pipeThrough(new CompressionStream("gzip"));
    var buf = await new Response(stream).arrayBuffer();
    return b64url(buf);
  }
  async function ungzip(payload) {
    if (payload.charAt(0) === "{") return payload;
    if (!window.DecompressionStream) throw new Error("gzip");
    var stream = new Blob([fromB64url(payload)]).stream().pipeThrough(new DecompressionStream("gzip"));
    return await new Response(stream).text();
  }

  /** Loads a shared design, then drops it from the address bar so a reload keeps later edits. */
  window.IcarusAppHash = function (payload, loadDoc, status) {
    ungzip(decodeURIComponent(payload)).then(function (json) {
      var result = window.IcarusCanvas.validateProfile(JSON.parse(json));
      if (!result.ok) { status(result.errors[0]); return; }
      loadDoc(result.doc);
      status(result.warnings[0] || t("shareLoaded"));
    }).catch(function () {
      status(t("shareFail"));
    }).then(function () {
      history.replaceState(null, "", location.pathname + location.search);
    });
  };

  function applyChrome() {
    document.documentElement.lang = lang;
    document.querySelectorAll("[data-i18n]").forEach(function (node) {
      node.textContent = t(node.getAttribute("data-i18n"));
    });
    document.querySelectorAll("[data-i18n-label]").forEach(function (node) {
      var text = t(node.getAttribute("data-i18n-label"));
      node.setAttribute("aria-label", text);
      node.setAttribute("title", text);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach(function (node) {
      node.setAttribute("placeholder", t(node.getAttribute("data-i18n-placeholder")));
    });
    document.querySelectorAll("[data-set-lang]").forEach(function (item) {
      item.setAttribute("aria-pressed", item.getAttribute("data-set-lang") === lang ? "true" : "false");
    });
  }

  function download(name, text, type) {
    var blob = new Blob([text], { type: type });
    var url = URL.createObjectURL(blob);
    var link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  function copyText(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { window.prompt(t("copyManual"), text); });
    } else {
      window.prompt(t("copyManual"), text);
    }
  }

  var FONT_FILES = [
    ["ICARUS Sans", 400, "Inter-Regular.ttf"],
    ["ICARUS Sans", 700, "Inter-Bold.ttf"],
    ["ICARUS Serif", 400, "SourceSerif4-Regular.ttf"],
    ["ICARUS Serif", 700, "SourceSerif4-Bold.ttf"],
    ["ICARUS Mono", 400, "JetBrainsMono-Regular.ttf"]
  ];

  /** Embeds the fonts when the page is served over http. From a file:// page the export falls back to system fonts. */
  async function fontFaces() {
    var faces = await Promise.all(FONT_FILES.map(async function (font) {
      try {
        var response = await fetch("assets/fonts/" + font[2]);
        if (!response.ok) return "";
        var bytes = new Uint8Array(await response.arrayBuffer());
        var bin = "";
        for (var i = 0; i < bytes.length; i += 8192) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
        return "@font-face{font-family:\"" + font[0] + "\";font-weight:" + font[1] + ";src:url(data:font/ttf;base64," + btoa(bin) + ") format(\"truetype\")}";
      } catch (err) {
        return "";
      }
    }));
    return faces.join("");
  }

  async function htmlExport() {
    var name = studio.profile().name;
    var pages = studio.allPages().map(function (item) {
      return '<section class="sheet" style="background:' + item.background + '">' + item.html + "</section>";
    }).join("");
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + esc(name) + "</title><style>" +
      (await fontFaces()) +
      "@page{size:A4;margin:0} html,body{margin:0;background:#e5e7eb} body{display:flex;flex-direction:column;align-items:center;gap:8mm;padding:8mm 0}" +
      ".sheet{width:210mm;height:297mm;position:relative;overflow:hidden;background:#fff;box-shadow:0 4px 18px rgba(0,0,0,.12);page-break-after:always;break-after:page}" +
      ".el{position:absolute;transform-origin:center center;white-space:pre-wrap;overflow:hidden;box-sizing:border-box}" +
      ".el svg{width:100%;height:100%;display:block} .el img,.tint,.slot-empty{position:absolute;inset:0} .el img{width:100%;height:100%}" +
      ".tint{mix-blend-mode:multiply} .slot-empty{display:flex;align-items:center;justify-content:center;border:1px dashed #c5cad3;color:#667085;font:9pt sans-serif;background:#f8fafc}" +
      ".el table th,.el table td{border:1px solid #e5e7eb;padding:1.2mm 1.6mm;text-align:left;vertical-align:top}" +
      "@media print{html,body{background:#fff;padding:0;gap:0;display:block}.sheet{box-shadow:none}}" +
      "</style></head><body>" + pages + "</body></html>";
  }

  /** The editor shows one page. Printing builds every page, then removes them again. */
  function buildPrintPages() {
    removePrintPages();
    var holder = document.createElement("div");
    holder.id = "print-root";
    holder.innerHTML = studio.allPages().map(function (item) {
      return '<article class="sheet" style="background:' + item.background + '">' + item.html + "</article>";
    }).join("");
    document.body.appendChild(holder);
  }
  function removePrintPages() {
    var old = document.getElementById("print-root");
    if (old) old.remove();
  }

  function importText(text) {
    var parsed = window.IcarusAI.extractJson(text);
    if (!parsed.ok) return { ok: false, errors: [parsed.error], warnings: [] };
    return studio.loadProfile(parsed.value);
  }

  function listErrors(list, items) {
    list.innerHTML = "";
    (items || []).forEach(function (item) {
      var li = document.createElement("li");
      li.textContent = item;
      list.appendChild(li);
    });
  }

  function wire() {
    document.querySelectorAll("[data-set-lang]").forEach(function (button) {
      button.addEventListener("click", function () {
        lang = button.getAttribute("data-set-lang");
        try { localStorage.setItem(LANG_KEY, lang); } catch (err) { /* private mode */ }
        applyChrome();
        studio.setLang(lang);
      });
    });
    // Only the tab buttons: <main id="workspace"> also carries data-mobile, and a click anywhere in it must not refit the page.
    document.querySelectorAll("button[data-mobile]").forEach(function (button) {
      button.addEventListener("click", function () {
        document.getElementById("workspace").setAttribute("data-mobile", button.getAttribute("data-mobile"));
        document.querySelectorAll("button[data-mobile]").forEach(function (item) {
          item.setAttribute("aria-pressed", item === button ? "true" : "false");
        });
        if (button.getAttribute("data-mobile") === "canvas") studio.fit();
      });
    });
    var menu = document.getElementById("export-menu");
    var opener = document.getElementById("export-open");
    function closeExport() { menu.hidden = true; opener.setAttribute("aria-expanded", "false"); }
    opener.addEventListener("click", function () {
      menu.hidden = !menu.hidden;
      opener.setAttribute("aria-expanded", menu.hidden ? "false" : "true");
      if (!menu.hidden) menu.querySelector("button").focus();
    });
    document.addEventListener("click", function (event) {
      if (!event.target.closest(".export-wrap")) closeExport();
    });
    menu.addEventListener("keydown", function (event) {
      if (event.key === "Escape") { closeExport(); opener.focus(); }
    });
    menu.addEventListener("click", function (event) {
      var item = event.target.closest("[data-export]");
      if (!item) return;
      var kind = item.getAttribute("data-export");
      closeExport();
      var profile = studio.profile();
      if (kind === "json") {
        download(slug(profile.name) + ".json", JSON.stringify(profile, null, 2), "application/json");
        setStatus(t("exportedJson"));
      }
      if (kind === "html") htmlExport().then(function (html) { download(slug(profile.name) + ".html", html, "text/html"); });
      if (kind === "print") window.print();
    });
    window.addEventListener("beforeprint", buildPrintPages);
    window.addEventListener("afterprint", removePrintPages);

    var importInput = document.getElementById("import-file");
    importInput.addEventListener("change", function () {
      var file = importInput.files && importInput.files[0];
      importInput.value = "";
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        var result = importText(String(reader.result));
        if (!result.ok) { setStatus(result.errors[0]); return; }
        if (!result.warnings.length) setStatus(t("imported"));
      };
      reader.onerror = function () { setStatus(t("importFail")); };
      reader.readAsText(file);
    });
    document.getElementById("share").addEventListener("click", function () {
      gzipJson(studio.profile()).then(function (hash) {
        if (!hash) { setStatus(t("shareUnsupported")); return; }
        if (hash.length > 60000) { setStatus(t("shareBig")); return; }
        var url = location.origin + location.pathname + "#t=" + hash;
        copyText(url, function () { setStatus(t("shareCopied")); });
      });
    });
    document.getElementById("ai-open").addEventListener("click", function () {
      document.getElementById("ai-prompt").value = window.IcarusAI.buildPrompt(studio.profile());
      document.getElementById("ai-dialog").showModal();
    });
    document.getElementById("ai-copy").addEventListener("click", function () {
      copyText(document.getElementById("ai-prompt").value, function () { setStatus(t("promptCopied")); });
    });
    document.getElementById("ai-download").addEventListener("click", function () {
      download("icarus-ai-prompt.txt", document.getElementById("ai-prompt").value, "text/plain");
    });
    document.getElementById("ai-import-open").addEventListener("click", function () {
      listErrors(document.getElementById("ai-errors"), []);
      document.getElementById("ai-import-dialog").showModal();
    });
    document.getElementById("ai-apply").addEventListener("click", function () {
      var list = document.getElementById("ai-errors");
      var result = importText(document.getElementById("ai-answer").value);
      if (!result.ok) { listErrors(list, result.errors); return; }
      listErrors(list, result.warnings);
      if (!result.warnings.length) {
        document.getElementById("ai-import-dialog").close();
        document.getElementById("ai-answer").value = "";
        setStatus(t("imported"));
      }
    });
    document.querySelectorAll("dialog").forEach(function (dialog) {
      dialog.addEventListener("click", function (event) {
        if (event.target === dialog) dialog.close();
      });
    });
    var repo = document.getElementById("repo-link");
    var docs = document.getElementById("docs-link");
    var bapp = document.getElementById("bapp-link");
    if (cfg.repoUrl) repo.href = cfg.repoUrl;
    if (cfg.docsUrl) docs.href = cfg.docsUrl;
    bapp.href = isPublicUrl(cfg.bappUrl) ? cfg.bappUrl : (cfg.bappStoreHome || bapp.href);
    var support = document.getElementById("support");
    var donations = cfg.donations || {};
    var links = [["Buy Me a Coffee", donations.buyMeACoffee], ["Ko-fi", donations.kofi], ["GitHub Sponsors", donations.githubSponsors]]
      .filter(function (pair) { return isPublicUrl(pair[1]); });
    if (links.length) {
      support.hidden = false;
      support.innerHTML = links.map(function (pair) { return '<a href="' + esc(pair[1]) + '" rel="noopener">' + esc(pair[0]) + "</a>"; }).join("");
    }
  }

  function slug(name) {
    return String(name || "report").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "report";
  }

  applyChrome();
  studio = window.IcarusEditor.mount();
  studio.setLang(lang);
  window.IcarusStudio = studio;
  wire();
})();
