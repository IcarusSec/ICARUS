/**
 * Shell around the page editor: language, share link, import, and export.
 * Part of ICARUS, MIT License. No tracking and no upload.
 */
(function () {
  var cfg = window.ICARUS_SITE_CONFIG || {};
  var studio = null;
  var lang = "en";

  function t(key) { return window.IcarusI18n.t(lang, key); }

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

  window.IcarusAppHash = function (payload, loadDoc, status) {
    ungzip(decodeURIComponent(payload)).then(function (json) {
      var result = window.IcarusCanvas.validateProfile(JSON.parse(json));
      if (!result.ok) { status(result.errors[0]); return; }
      loadDoc(result.doc);
      if (result.warnings[0]) status(result.warnings[0]);
    }).catch(function () { status(t("shareFail")); });
  };

  function applyChrome() {
    document.documentElement.lang = lang === "pt-BR" ? "pt-BR" : "en";
    var map = {
      templates: "templates", share: "share", "ai-open": "aiExport", "ai-import-open": "aiImport", "export-open": "exportBtn"
    };
    Object.keys(map).forEach(function (id) {
      var node = document.getElementById(id);
      if (node) node.textContent = t(map[id]);
    });
    var fileLabel = document.querySelector(".file-btn");
    if (fileLabel) fileLabel.firstChild.textContent = t("importAction");
    var hint = document.querySelector("#hintbar span");
    if (hint) hint.textContent = t("hint");
    document.getElementById("undo").title = lang === "pt-BR" ? "Desfazer" : "Undo";
    document.getElementById("redo").title = lang === "pt-BR" ? "Refazer" : "Redo";
  }

  function download(name, text, type) {
    var blob = new Blob([text], { type: type });
    var url = URL.createObjectURL(blob);
    var link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
  }

  function htmlExport() {
    var profile = studio.profile();
    var loaded = window.IcarusCanvas.docFromProfile(profile);
    var doc = loaded.doc;
    var pages = doc.pages.map(function (page, index) {
      var body = window.IcarusCanvas.renderPage(page, {
        vars: window.IcarusCanvas.sampleVars(doc, index, doc.pages.length),
        logo: doc.brand.logo
      });
      return '<section class="sheet" style="background:' + page.background + '">' + body + "</section>";
    }).join("");
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" + doc.name + "</title><style>" +
      "@page{size:A4;margin:0} body{margin:0} .sheet{width:210mm;height:297mm;position:relative;overflow:hidden;page-break-after:always}" +
      ".el{position:absolute;transform-origin:center center;white-space:pre-wrap;overflow:hidden;box-sizing:border-box}" +
      ".el svg,.el img{width:100%;height:100%} .tint{position:absolute;inset:0;mix-blend-mode:multiply} .slot-empty{display:flex;align-items:center;justify-content:center;height:100%;border:1px dashed #ccc;color:#667085;font-size:9pt}" +
      "</style><link rel=\"stylesheet\" href=\"assets/app.css\"></head><body>" + pages + "</body></html>";
  }

  function wire() {
    document.querySelectorAll("[data-set-lang]").forEach(function (button) {
      button.addEventListener("click", function () {
        lang = button.getAttribute("data-set-lang");
        document.querySelectorAll("[data-set-lang]").forEach(function (item) {
          item.setAttribute("aria-pressed", item === button ? "true" : "false");
        });
        applyChrome();
        studio.setLang(lang);
      });
    });
    document.querySelectorAll("[data-mobile]").forEach(function (button) {
      button.addEventListener("click", function () {
        document.getElementById("workspace").setAttribute("data-mobile", button.getAttribute("data-mobile"));
        document.querySelectorAll("[data-mobile]").forEach(function (item) {
          item.setAttribute("aria-pressed", item === button ? "true" : "false");
        });
      });
    });
    var menu = document.getElementById("export-menu");
    document.getElementById("export-open").addEventListener("click", function () { menu.hidden = !menu.hidden; });
    document.addEventListener("click", function (event) {
      if (!event.target.closest(".export-wrap")) menu.hidden = true;
    });
    menu.addEventListener("click", function (event) {
      var kind = event.target.getAttribute("data-export");
      if (!kind) return;
      menu.hidden = true;
      if (kind === "json") download(slug(studio.profile().name) + ".json", JSON.stringify(studio.profile(), null, 2), "application/json");
      if (kind === "html") download(slug(studio.profile().name) + ".html", htmlExport(), "text/html");
      if (kind === "print") window.print();
    });
    document.getElementById("import-file").addEventListener("change", function (event) {
      var file = event.target.files && event.target.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        var result = window.IcarusAI.parseResponse(String(reader.result));
        if (!result.ok) { setStatus(result.errors[0]); return; }
        studio.loadProfile(JSON.parse(String(reader.result)));
        setStatus("");
      };
      reader.readAsText(file);
    });
    document.getElementById("share").addEventListener("click", function () {
      gzipJson(studio.profile()).then(function (hash) {
        if (!hash || hash.length > 60000) { setStatus(t("shareBig")); return; }
        var url = location.href.split("#")[0] + "#t=" + hash;
        history.replaceState(null, "", "#t=" + hash);
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(url);
        setStatus(t("shareCopied"));
      });
    });
    document.getElementById("ai-open").addEventListener("click", function () {
      document.getElementById("ai-prompt").value = window.IcarusAI.buildPrompt(studio.profile());
      document.getElementById("ai-dialog").showModal();
    });
    document.getElementById("ai-copy").addEventListener("click", function () {
      var text = document.getElementById("ai-prompt").value;
      if (navigator.clipboard) navigator.clipboard.writeText(text);
    });
    document.getElementById("ai-download").addEventListener("click", function () {
      download("icarus-ai-prompt.txt", document.getElementById("ai-prompt").value, "text/plain");
    });
    document.getElementById("ai-import-open").addEventListener("click", function () {
      document.getElementById("ai-errors").innerHTML = "";
      document.getElementById("ai-import-dialog").showModal();
    });
    document.getElementById("ai-apply").addEventListener("click", function () {
      var result = window.IcarusAI.parseResponse(document.getElementById("ai-answer").value);
      var list = document.getElementById("ai-errors");
      if (!result.ok) {
        list.innerHTML = result.errors.map(function (error) { return "<li>" + error.replace(/</g, "") + "</li>"; }).join("");
        return;
      }
      studio.loadProfile(JSON.parse(extract(document.getElementById("ai-answer").value)));
      list.innerHTML = (result.warnings || []).map(function (error) { return "<li>" + error + "</li>"; }).join("");
      if (!result.warnings || !result.warnings.length) document.getElementById("ai-import-dialog").close();
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
      support.innerHTML = links.map(function (pair) { return '<a href="' + pair[1] + '">' + pair[0] + "</a>"; }).join("");
    }
  }

  function extract(text) {
    var parsed = window.IcarusAI.extractJson(text);
    return parsed.ok ? JSON.stringify(parsed.value) : "{}";
  }
  function slug(name) {
    return String(name || "report").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "report";
  }

  studio = window.IcarusEditor.mount();
  window.IcarusStudio = studio;
  wire();
  applyChrome();
})();
