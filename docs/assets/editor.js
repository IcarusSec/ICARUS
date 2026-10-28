/**
 * Canva-style page editor. Pointer gestures, layers, and the property panel.
 * Part of ICARUS, MIT License.
 */
(function (root) {
  var C = root.IcarusCanvas;
  var KEY = "icarus.templateBuilder.draft.v3";
  var MM_PER_PX = 25.4 / 96;
  var PAGE_BOX = { x: 0, y: 0, w: 210, h: 297 };
  var TABLE_KEYS = ["title", "severity", "path", "cwe", "category"];

  var ICONS = {
    alignLeft: '<path d="M4 3v18"/><rect x="7" y="6" width="12" height="4" rx="1"/><rect x="7" y="14" width="7" height="4" rx="1"/>',
    alignCenter: '<path d="M12 3v18"/><rect x="5" y="6" width="14" height="4" rx="1"/><rect x="8" y="14" width="8" height="4" rx="1"/>',
    alignRight: '<path d="M20 3v18"/><rect x="5" y="6" width="12" height="4" rx="1"/><rect x="10" y="14" width="7" height="4" rx="1"/>',
    alignTop: '<path d="M3 4h18"/><rect x="6" y="7" width="4" height="12" rx="1"/><rect x="14" y="7" width="4" height="7" rx="1"/>',
    alignMiddle: '<path d="M3 12h18"/><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="8" width="4" height="8" rx="1"/>',
    alignBottom: '<path d="M3 20h18"/><rect x="6" y="5" width="4" height="12" rx="1"/><rect x="14" y="10" width="4" height="7" rx="1"/>',
    distributeH: '<path d="M3 4v16M21 4v16"/><rect x="9" y="7" width="6" height="10" rx="1"/>',
    distributeV: '<path d="M4 3h16M4 21h16"/><rect x="7" y="9" width="10" height="6" rx="1"/>',
    front: '<rect x="8" y="8" width="12" height="12" rx="1" fill="currentColor"/><path d="M4 16V4h12"/>',
    forward: '<rect x="8" y="8" width="12" height="12" rx="1"/><path d="M4 14V4h10"/><path d="M14 11v6M11 14h6"/>',
    backward: '<rect x="4" y="4" width="12" height="12" rx="1"/><path d="M20 10v10H10"/><path d="M7 10h6"/>',
    back: '<rect x="4" y="4" width="12" height="12" rx="1" fill="currentColor"/><path d="M20 8v12H8"/>',
    group: '<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/><path d="M11 7h4v6M13 17H9v-6" stroke-dasharray="2 2"/>',
    ungroup: '<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/>',
    duplicate: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    unlock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.5-2"/>',
    eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    eyeOff: '<path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/>',
    up: '<path d="M6 15l6-6 6 6"/>',
    down: '<path d="M6 9l6 6 6-6"/>',
    textLeft: '<path d="M4 6h16M4 10h10M4 14h16M4 18h10"/>',
    textCenter: '<path d="M4 6h16M7 10h10M4 14h16M7 18h10"/>',
    textRight: '<path d="M4 6h16M10 10h10M4 14h16M10 18h10"/>',
    bold: '<path d="M7 5h6a3.5 3.5 0 0 1 0 7H7zM7 12h7a3.5 3.5 0 0 1 0 7H7z"/>',
    italic: '<path d="M10 5h8M6 19h8M14 5l-4 14"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13 7l4 4"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    plus: '<path d="M12 5v14M5 12h14"/>'
  };

  function icon(name) {
    return '<svg viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || "") + "</svg>";
  }

  function mount() {
    var doc = C.starterDoc("swiss");
    var pageIndex = 0;
    var selected = [];
    var past = [];
    var future = [];
    var zoom = 1;
    var panX = 40;
    var panY = 24;
    var fitMode = true;
    var lang = "en";
    var space = false;
    var clipboard = [];
    var gesture = null;
    var leftTab = "elements";
    var moreOpen = false;
    var draftWarned = false;
    var caret = null;
    var editing = null;

    var stage = document.getElementById("stage");
    var world = document.getElementById("world");
    var sheet = document.getElementById("sheet");
    var overlay = document.getElementById("overlay");
    var gallery = document.getElementById("gallery");
    var leftBody = document.getElementById("left-body");
    var inspector = document.getElementById("inspector");
    var nameInput = document.getElementById("template-name");
    var esc = C.esc;

    function t(key) {
      var pack = root.IcarusI18n && root.IcarusI18n.t;
      return pack ? root.IcarusI18n.t(lang, key) : key;
    }

    function page() { return doc.pages[pageIndex]; }
    function vars() { return C.sampleVars(doc, pageIndex, doc.pages.length); }
    function byId(id) { return page().elements.filter(function (element) { return element.id === id; })[0]; }
    function selectedElements() { return selected.map(byId).filter(Boolean); }
    function single() { var list = selectedElements(); return list.length === 1 ? list[0] : null; }
    function round(n) { return Math.round(n * 10) / 10; }

    function snapshot() {
      return JSON.stringify({ doc: doc, pageIndex: pageIndex, selected: selected });
    }
    /** Records the state before a change. Pass a snapshot taken earlier to record that instead. */
    function pushHistory(before) {
      past.push(before || snapshot());
      if (past.length > 80) past.shift();
      future = [];
      undoButtons();
    }
    function restore(raw) {
      var data = JSON.parse(raw);
      doc = C.normalizeDoc(data.doc);
      pageIndex = Math.max(0, Math.min(doc.pages.length - 1, data.pageIndex || 0));
      selected = data.selected || [];
      paint();
    }
    function undo() { if (!past.length) return; future.push(snapshot()); restore(past.pop()); undoButtons(); }
    function redo() { if (!future.length) return; past.push(snapshot()); restore(future.pop()); undoButtons(); }
    function undoButtons() {
      document.getElementById("undo").disabled = !past.length;
      document.getElementById("redo").disabled = !future.length;
    }

    function saveDraft() {
      try {
        localStorage.setItem(KEY, snapshot());
        draftWarned = false;
      } catch (err) {
        if (!draftWarned) setStatus(t("draftFull"));
        draftWarned = true;
      }
    }

    function setStatus(text) {
      document.getElementById("status").textContent = text || "";
    }

    function renderSheet() {
      sheet.innerHTML = C.renderPage(page(), { vars: vars(), logo: doc.brand.logo });
    }

    function paint() {
      var current = page();
      sheet.style.background = current.background;
      renderSheet();
      if (!current.elements.length) {
        sheet.insertAdjacentHTML("beforeend", '<div class="page-empty"><strong>' + esc(t("emptyTitle")) + "</strong><span>" + esc(t("emptyBody")) + '</span><button type="button" class="text-btn" data-open-gallery="1">' + esc(t("browseTemplates")) + "</button></div>");
      }
      if (document.activeElement !== nameInput) nameInput.value = doc.name;
      applyWorld();
      paintOverlay();
      paintLeft();
      paintInspector();
      saveDraft();
    }
    function paintSelection() { paintOverlay(); paintLeft(); paintInspector(); }

    function applyWorld() {
      world.style.transform = "translate(" + panX + "px," + panY + "px) scale(" + zoom + ")";
      document.getElementById("zoom-label").textContent = Math.round(zoom * 100) + "%";
    }

    function pagePoint(event) {
      var rect = sheet.getBoundingClientRect();
      return {
        x: (event.clientX - rect.left) / rect.width * C.PAGE_W,
        y: (event.clientY - rect.top) / rect.height * C.PAGE_H
      };
    }
    function toStage(mmx, mmy) {
      var rect = sheet.getBoundingClientRect();
      var host = stage.getBoundingClientRect();
      return {
        x: rect.left - host.left + (mmx / C.PAGE_W) * rect.width,
        y: rect.top - host.top + (mmy / C.PAGE_H) * rect.height
      };
    }
    /** The middle of what the user can see, kept on the page. */
    function visibleCenter() {
      var host = stage.getBoundingClientRect();
      var mm = pagePoint({ clientX: host.left + host.width / 2, clientY: host.top + host.height / 2 });
      return { x: Math.max(30, Math.min(C.PAGE_W - 30, mm.x)), y: Math.max(30, Math.min(C.PAGE_H - 30, mm.y)) };
    }

    function hitTest(mm) {
      var list = page().elements.slice().sort(function (a, b) { return b.z - a.z; });
      for (var i = 0; i < list.length; i++) {
        var element = list[i];
        if (element.hidden || element.locked) continue;
        if (element.type === "line") {
          var dist = pointSegment(mm.x, mm.y, element.x1, element.y1, element.x2, element.y2);
          if (dist < 2.2 / Math.min(1, zoom)) return element;
          continue;
        }
        var local = toLocal(element, mm.x, mm.y);
        if (Math.abs(local.x) <= element.w / 2 && Math.abs(local.y) <= element.h / 2) return element;
      }
      return null;
    }
    function toLocal(element, px, py) {
      var cx = element.x + element.w / 2;
      var cy = element.y + element.h / 2;
      var rad = -(element.rotation || 0) * Math.PI / 180;
      var dx = px - cx;
      var dy = py - cy;
      return { x: dx * Math.cos(rad) - dy * Math.sin(rad), y: dx * Math.sin(rad) + dy * Math.cos(rad) };
    }
    function pointSegment(px, py, x1, y1, x2, y2) {
      var dx = x2 - x1;
      var dy = y2 - y1;
      var len = dx * dx + dy * dy || 1;
      var k = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len));
      return Math.hypot(px - (x1 + k * dx), py - (y1 + k * dy));
    }

    function groupIds(element) {
      if (!element.groupId) return [element.id];
      return page().elements.filter(function (item) { return item.groupId === element.groupId; }).map(function (item) { return item.id; });
    }
    function withGroups(ids) {
      var out = [];
      ids.forEach(function (id) {
        var element = byId(id);
        if (!element) return;
        groupIds(element).forEach(function (member) { if (out.indexOf(member) === -1) out.push(member); });
      });
      return out;
    }

    function addToSelection(element, toggle) {
      var ids = groupIds(element);
      if (toggle) {
        var allOn = ids.every(function (id) { return selected.indexOf(id) !== -1; });
        ids.forEach(function (id) {
          var at = selected.indexOf(id);
          if (allOn && at !== -1) selected.splice(at, 1);
          else if (!allOn && at === -1) selected.push(id);
        });
      } else if (!ids.every(function (id) { return selected.indexOf(id) !== -1; })) {
        selected = ids;
      }
      paintSelection();
    }

    function paintOverlay() {
      var html = "";
      var elements = selectedElements();
      if (elements.length === 1 && !editing) {
        var corners = C.corners(elements[0]);
        if (elements[0].type !== "line") {
          var pts = corners.map(function (point) { var s = toStage(point[0], point[1]); return s.x + "," + s.y; }).join(" ");
          html += '<svg class="sel-outline"><polygon points="' + pts + '"/></svg>';
        }
        if (!elements[0].locked) {
          handlePoints(elements[0], corners).forEach(function (handle) {
            var s = toStage(handle.x, handle.y);
            html += '<button type="button" tabindex="-1" class="' + (handle.name === "rotate" ? "rotate-handle" : "handle") + '" data-handle="' + handle.name + '" style="left:' + s.x + "px;top:" + s.y + 'px" aria-label="' + esc(t(handle.name === "rotate" ? "rotation" : "resize")) + '"></button>';
          });
        }
      } else if (elements.length > 1) {
        var box = C.unionBounds(elements);
        var a = toStage(box.x, box.y);
        var b = toStage(box.x + box.w, box.y + box.h);
        html += '<div class="sel-box" style="left:' + a.x + "px;top:" + a.y + "px;width:" + (b.x - a.x) + "px;height:" + (b.y - a.y) + 'px"></div>';
        [["nw", box.x, box.y], ["ne", box.x + box.w, box.y], ["sw", box.x, box.y + box.h], ["se", box.x + box.w, box.y + box.h]].forEach(function (handle) {
          var s = toStage(handle[1], handle[2]);
          html += '<button type="button" tabindex="-1" class="handle" data-handle="' + handle[0] + '" data-group="1" style="left:' + s.x + "px;top:" + s.y + 'px" aria-label="' + esc(t("resize")) + '"></button>';
        });
      }
      if (gesture && gesture.guides) {
        gesture.guides.forEach(function (guide) {
          if (guide.axis === "x") html += '<div class="guide v" style="left:' + toStage(guide.at, 0).x + 'px"></div>';
          else html += '<div class="guide h" style="top:' + toStage(0, guide.at).y + 'px"></div>';
        });
      }
      if (gesture && gesture.hints) {
        gesture.hints.forEach(function (hint) {
          var s = toStage(hint.x, hint.y);
          html += '<div class="gap-label" style="left:' + (s.x + 4) + "px;top:" + (s.y - 18) + 'px">' + esc(hint.text) + "</div>";
        });
      }
      if (gesture && gesture.marquee) {
        var m = gesture.marquee;
        var p1 = toStage(Math.min(m.x1, m.x2), Math.min(m.y1, m.y2));
        var p2 = toStage(Math.max(m.x1, m.x2), Math.max(m.y1, m.y2));
        html += '<div class="marquee" style="left:' + p1.x + "px;top:" + p1.y + "px;width:" + (p2.x - p1.x) + "px;height:" + (p2.y - p1.y) + 'px"></div>';
      }
      overlay.innerHTML = html;
    }

    function handlePoints(element, corners) {
      if (element.type === "line") {
        return [{ name: "p1", x: element.x1, y: element.y1 }, { name: "p2", x: element.x2, y: element.y2 }];
      }
      var rad = (element.rotation || 0) * Math.PI / 180;
      var lift = 7 / Math.max(0.4, zoom);
      var topX = (corners[0][0] + corners[1][0]) / 2;
      var topY = (corners[0][1] + corners[1][1]) / 2;
      function mid(a, b) { return [(corners[a][0] + corners[b][0]) / 2, (corners[a][1] + corners[b][1]) / 2]; }
      var list = [
        { name: "nw", x: corners[0][0], y: corners[0][1] },
        { name: "ne", x: corners[1][0], y: corners[1][1] },
        { name: "se", x: corners[2][0], y: corners[2][1] },
        { name: "sw", x: corners[3][0], y: corners[3][1] },
        { name: "rotate", x: topX + Math.sin(rad) * lift, y: topY - Math.cos(rad) * lift }
      ];
      var size = { w: element.w * zoom, h: element.h * zoom };
      if (size.w > 14) list.push({ name: "n", x: mid(0, 1)[0], y: mid(0, 1)[1] }, { name: "s", x: mid(2, 3)[0], y: mid(2, 3)[1] });
      if (size.h > 14) list.push({ name: "e", x: mid(1, 2)[0], y: mid(1, 2)[1] }, { name: "w", x: mid(3, 0)[0], y: mid(3, 0)[1] });
      return list;
    }

    function spacingHints(box, others) {
      var hints = [];
      others.forEach(function (other) {
        var right = other.x - (box.x + box.w);
        var left = box.x - (other.x + other.w);
        var below = other.y - (box.y + box.h);
        var overlapY = box.y < other.y + other.h && other.y < box.y + box.h;
        var overlapX = box.x < other.x + other.w && other.x < box.x + box.w;
        if (overlapY && right > 0.4 && right < 50) hints.push({ x: box.x + box.w, y: box.y, text: right.toFixed(1) + " mm" });
        else if (overlapY && left > 0.4 && left < 50) hints.push({ x: other.x + other.w, y: box.y, text: left.toFixed(1) + " mm" });
        else if (overlapX && below > 0.4 && below < 50) hints.push({ x: box.x, y: box.y + box.h, text: below.toFixed(1) + " mm" });
      });
      return hints.slice(0, 2);
    }

    function closeMenus() {
      document.querySelectorAll(".menu-pop").forEach(function (menu) { menu.remove(); });
    }

    function onSheetDown(event) {
      if (event.target.closest && event.target.closest("[data-open-gallery]")) { openGallery(); return; }
      if (event.target.closest && event.target.closest("[contenteditable=true]")) return;
      if (editing && document.activeElement) document.activeElement.blur();
      closeMenus();
      if (space || event.button === 1) {
        event.preventDefault();
        gesture = { mode: "pan", x: event.clientX, y: event.clientY, panX: panX, panY: panY };
        stage.setPointerCapture(event.pointerId);
        stage.classList.add("panning");
        return;
      }
      var mm = pagePoint(event);
      var hit = hitTest(mm);
      if (event.button === 2) {
        if (hit && selected.indexOf(hit.id) === -1) addToSelection(hit, false);
        return;
      }
      if (!hit) {
        if (!event.shiftKey) selected = [];
        gesture = { mode: "marquee", marquee: { x1: mm.x, y1: mm.y, x2: mm.x, y2: mm.y }, shift: event.shiftKey, base: selected.slice() };
        stage.setPointerCapture(event.pointerId);
        paintSelection();
        return;
      }
      var before = snapshot();
      addToSelection(hit, event.shiftKey);
      if (selected.indexOf(hit.id) === -1) return;
      var moving = selectedElements().filter(function (element) { return !element.locked; });
      gesture = {
        mode: "move",
        cx: event.clientX,
        cy: event.clientY,
        x: mm.x,
        y: mm.y,
        before: before,
        start: moving.map(function (element) { return JSON.parse(JSON.stringify(element)); }),
        ids: moving.map(function (element) { return element.id; })
      };
      stage.setPointerCapture(event.pointerId);
    }

    function onOverlayDown(event) {
      var handle = event.target.closest && event.target.closest("[data-handle]");
      if (!handle) return;
      event.stopPropagation();
      event.preventDefault();
      var mm = pagePoint(event);
      var elements = selectedElements().filter(function (element) { return !element.locked; });
      if (!elements.length) return;
      var name = handle.getAttribute("data-handle");
      gesture = {
        mode: handle.getAttribute("data-group") ? "group-resize" : name === "rotate" ? "rotate" : "resize",
        handle: name,
        cx: event.clientX,
        cy: event.clientY,
        x: mm.x,
        y: mm.y,
        before: snapshot(),
        start: JSON.parse(JSON.stringify(elements.length === 1 ? elements[0] : elements)),
        box: C.unionBounds(elements),
        ids: elements.map(function (element) { return element.id; })
      };
      overlay.setPointerCapture(event.pointerId);
    }

    /** A click is not an edit. History starts once the pointer has really moved. */
    function started(event) {
      if (gesture.moved) return true;
      if (Math.hypot(event.clientX - gesture.cx, event.clientY - gesture.cy) < 3) return false;
      gesture.moved = true;
      pushHistory(gesture.before);
      return true;
    }

    function onMove(event) {
      if (!gesture) return;
      if (gesture.mode === "pan") {
        panX = gesture.panX + (event.clientX - gesture.x);
        panY = gesture.panY + (event.clientY - gesture.y);
        fitMode = false;
        applyWorld();
        paintOverlay();
        return;
      }
      var mm = pagePoint(event);
      if (gesture.mode === "marquee") {
        gesture.marquee.x2 = mm.x;
        gesture.marquee.y2 = mm.y;
        var x1 = Math.min(gesture.marquee.x1, mm.x);
        var y1 = Math.min(gesture.marquee.y1, mm.y);
        var x2 = Math.max(gesture.marquee.x1, mm.x);
        var y2 = Math.max(gesture.marquee.y1, mm.y);
        var hits = page().elements.filter(function (element) {
          if (element.hidden || element.locked) return false;
          return !(element.x + element.w < x1 || element.x > x2 || element.y + element.h < y1 || element.y > y2);
        }).map(function (element) { return element.id; });
        selected = withGroups(gesture.shift ? gesture.base.concat(hits) : hits);
        paintOverlay();
        return;
      }
      if (!started(event)) return;
      if (gesture.mode === "move") moveGesture(event, mm);
      else if (gesture.mode === "resize" || gesture.mode === "rotate") resizeGesture(event, mm);
      else if (gesture.mode === "group-resize") groupResizeGesture(event, mm);
      renderSheet();
      paintOverlay();
    }

    function moveGesture(event, mm) {
      if (!gesture.start.length) return;
      var dx = mm.x - gesture.x;
      var dy = mm.y - gesture.y;
      if (event.shiftKey) {
        if (Math.abs(dx) > Math.abs(dy)) dy = 0; else dx = 0;
      }
      var union = C.unionBounds(gesture.start);
      var box = { x: union.x + dx, y: union.y + dy, w: union.w, h: union.h };
      var others = page().elements.filter(function (element) { return gesture.ids.indexOf(element.id) === -1 && !element.hidden; });
      var snap = event.altKey ? { dx: 0, dy: 0, guides: [] } : C.snapDelta(box, others, 1.4 / Math.max(0.5, zoom));
      dx += snap.dx;
      dy += snap.dy;
      gesture.guides = snap.guides;
      gesture.start.forEach(function (start) {
        var element = byId(start.id);
        if (!element) return;
        if (element.type === "line") {
          element.x1 = round(start.x1 + dx);
          element.y1 = round(start.y1 + dy);
          element.x2 = round(start.x2 + dx);
          element.y2 = round(start.y2 + dy);
          C.syncLineBox(element);
        } else {
          element.x = round(start.x + dx);
          element.y = round(start.y + dy);
        }
      });
      gesture.hints = spacingHints({ x: box.x + snap.dx, y: box.y + snap.dy, w: box.w, h: box.h }, others);
    }

    function resizeGesture(event, mm) {
      var element = byId(gesture.ids[0]);
      var start = gesture.start;
      if (!element) return;
      if (gesture.handle === "p1" || gesture.handle === "p2") {
        var end = { x: mm.x, y: mm.y };
        var fixed = gesture.handle === "p1" ? { x: start.x2, y: start.y2 } : { x: start.x1, y: start.y1 };
        if (event.shiftKey) {
          var angle = Math.round(Math.atan2(end.y - fixed.y, end.x - fixed.x) / (Math.PI / 4)) * (Math.PI / 4);
          var len = Math.hypot(end.x - fixed.x, end.y - fixed.y);
          end = { x: fixed.x + Math.cos(angle) * len, y: fixed.y + Math.sin(angle) * len };
        }
        if (gesture.handle === "p1") { element.x1 = round(end.x); element.y1 = round(end.y); }
        else { element.x2 = round(end.x); element.y2 = round(end.y); }
        C.syncLineBox(element);
        gesture.hints = [{ x: end.x, y: end.y, text: Math.hypot(element.x2 - element.x1, element.y2 - element.y1).toFixed(1) + " mm" }];
      } else if (gesture.mode === "rotate") {
        var cx = start.x + start.w / 2;
        var cy = start.y + start.h / 2;
        var deg = Math.atan2(mm.y - cy, mm.x - cx) * 180 / Math.PI + 90;
        if (deg > 180) deg -= 360;
        deg = event.shiftKey ? Math.round(deg / 15) * 15 : Math.abs(deg % 45) < 2 ? Math.round(deg / 45) * 45 : deg;
        element.rotation = round(deg);
        gesture.hints = [{ x: mm.x, y: mm.y, text: Math.round(element.rotation) + "°" }];
      } else {
        var next = resizeRotated(start, gesture.handle, mm, event.shiftKey);
        element.x = round(next.x);
        element.y = round(next.y);
        element.w = round(next.w);
        element.h = round(next.h);
        gesture.hints = [{ x: mm.x, y: mm.y, text: element.w.toFixed(1) + " × " + element.h.toFixed(1) + " mm" }];
      }
    }

    function groupResizeGesture(event, mm) {
      var box = gesture.box;
      var dx = mm.x - gesture.x;
      var dy = mm.y - gesture.y;
      var nx = box.x, ny = box.y, nw = box.w, nh = box.h;
      if (gesture.handle.indexOf("e") !== -1) nw = Math.max(4, box.w + dx);
      if (gesture.handle.indexOf("s") !== -1) nh = Math.max(4, box.h + dy);
      if (gesture.handle.indexOf("w") !== -1) nw = Math.max(4, box.w - dx);
      if (gesture.handle.indexOf("n") !== -1) nh = Math.max(4, box.h - dy);
      if (event.shiftKey) {
        var ratio = box.w / box.h;
        if (nw / ratio > nh) nh = nw / ratio; else nw = nh * ratio;
      }
      if (gesture.handle.indexOf("w") !== -1) nx = box.x + box.w - nw;
      if (gesture.handle.indexOf("n") !== -1) ny = box.y + box.h - nh;
      var sx = nw / box.w;
      var sy = nh / box.h;
      gesture.start.forEach(function (start) {
        var element = byId(start.id);
        if (!element) return;
        if (element.type === "line") {
          element.x1 = round(nx + (start.x1 - box.x) * sx);
          element.y1 = round(ny + (start.y1 - box.y) * sy);
          element.x2 = round(nx + (start.x2 - box.x) * sx);
          element.y2 = round(ny + (start.y2 - box.y) * sy);
          C.syncLineBox(element);
          return;
        }
        element.x = round(nx + (start.x - box.x) * sx);
        element.y = round(ny + (start.y - box.y) * sy);
        element.w = Math.max(1, round(start.w * sx));
        element.h = Math.max(1, round(start.h * sy));
      });
    }

    function resizeRotated(start, handle, pointer, keepRatio) {
      var cx = start.x + start.w / 2;
      var cy = start.y + start.h / 2;
      var rad = -(start.rotation || 0) * Math.PI / 180;
      var dx = pointer.x - cx;
      var dy = pointer.y - cy;
      var lx = dx * Math.cos(rad) - dy * Math.sin(rad);
      var ly = dx * Math.sin(rad) + dy * Math.cos(rad);
      var left = -start.w / 2, right = start.w / 2, top = -start.h / 2, bottom = start.h / 2;
      if (handle.indexOf("e") !== -1) right = Math.max(left + 2, lx);
      if (handle.indexOf("w") !== -1) left = Math.min(right - 2, lx);
      if (handle.indexOf("s") !== -1) bottom = Math.max(top + 2, ly);
      if (handle.indexOf("n") !== -1) top = Math.min(bottom - 2, ly);
      if (keepRatio && handle.length === 2) {
        var ratio = start.w / start.h;
        var w = right - left;
        var h = bottom - top;
        if (w / ratio > h) h = w / ratio; else w = h * ratio;
        if (handle.indexOf("e") !== -1) right = left + w; else left = right - w;
        if (handle.indexOf("s") !== -1) bottom = top + h; else top = bottom - h;
      }
      var nw = right - left;
      var nh = bottom - top;
      var ncx = (left + right) / 2;
      var ncy = (top + bottom) / 2;
      var r = (start.rotation || 0) * Math.PI / 180;
      var px = cx + ncx * Math.cos(r) - ncy * Math.sin(r);
      var py = cy + ncx * Math.sin(r) + ncy * Math.cos(r);
      return { x: px - nw / 2, y: py - nh / 2, w: nw, h: nh };
    }

    function onUp() {
      if (!gesture) return;
      var mode = gesture.mode;
      var moved = gesture.moved;
      gesture = null;
      stage.classList.remove("panning");
      if (mode === "marquee") paintSelection();
      else if (mode === "pan") paintOverlay();
      else if (moved) paint();
      else paintOverlay();
    }

    stage.addEventListener("pointerdown", onSheetDown);
    overlay.addEventListener("pointerdown", onOverlayDown);
    stage.addEventListener("pointermove", onMove);
    overlay.addEventListener("pointermove", onMove);
    stage.addEventListener("pointerup", onUp);
    overlay.addEventListener("pointerup", onUp);
    stage.addEventListener("pointercancel", onUp);
    overlay.addEventListener("pointercancel", onUp);

    /** Grows a text box so typed text is never cut off. It never shrinks a box the user sized. */
    function fitTextHeight(element) {
      if (!element || element.type !== "text") return false;
      var node = sheet.querySelector('[data-id="' + cssId(element.id) + '"]');
      if (!node) return false;
      var needed = node.scrollHeight * MM_PER_PX;
      if (needed <= element.h + 0.3) return false;
      element.h = Math.min(500, Math.ceil(needed * 10) / 10 + 0.5);
      return true;
    }
    function cssId(id) { return root.CSS && root.CSS.escape ? root.CSS.escape(id) : String(id).replace(/"/g, ""); }

    function startTextEdit(element) {
      if (!element || element.type !== "text" || element.locked) return;
      selected = [element.id];
      var node = sheet.querySelector('[data-id="' + cssId(element.id) + '"]');
      if (!node) return;
      editing = element.id;
      paintOverlay();
      node.contentEditable = "true";
      node.spellcheck = true;
      node.classList.add("editing");
      node.textContent = element.text;
      node.focus();
      var range = document.createRange();
      range.selectNodeContents(node);
      var sel = root.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      var finished = false;
      function done(cancel) {
        if (finished) return;
        finished = true;
        node.removeEventListener("blur", onBlur);
        node.removeEventListener("keydown", onKey);
        editing = null;
        node.contentEditable = "false";
        var text = node.innerText.replace(/ /g, " ").replace(/\n$/, "").slice(0, 2000);
        if (!cancel && text !== element.text) {
          pushHistory();
          element.text = text;
          renderSheet();
          fitTextHeight(element);
        }
        paint();
      }
      function onBlur() { done(false); }
      function onKey(keyEvent) {
        keyEvent.stopPropagation();
        if (keyEvent.key === "Escape") { keyEvent.preventDefault(); done(false); }
        if (keyEvent.key === "Enter" && (keyEvent.metaKey || keyEvent.ctrlKey)) { keyEvent.preventDefault(); done(false); }
      }
      node.addEventListener("blur", onBlur);
      node.addEventListener("keydown", onKey);
      node.addEventListener("paste", function (pasteEvent) {
        pasteEvent.preventDefault();
        var plain = (pasteEvent.clipboardData || root.clipboardData).getData("text/plain");
        document.execCommand("insertText", false, plain);
      });
    }

    // Pointer capture sends the double-click to the stage, so find the element by position.
    stage.addEventListener("dblclick", function (event) {
      if (editing) return;
      var hit = hitTest(pagePoint(event));
      if (hit && hit.type === "text") startTextEdit(hit);
      else if (hit && hit.type === "image" && !hit.slot) replaceImage(hit);
    });

    stage.addEventListener("wheel", function (event) {
      event.preventDefault();
      if (event.ctrlKey || event.metaKey) {
        zoomAt(zoom * Math.exp(-event.deltaY * 0.0025), event.clientX, event.clientY);
      } else {
        panX -= event.shiftKey && !event.deltaX ? event.deltaY : event.deltaX;
        panY -= event.shiftKey && !event.deltaX ? 0 : event.deltaY;
        fitMode = false;
        applyWorld();
        paintOverlay();
      }
    }, { passive: false });

    function zoomAt(next, clientX, clientY) {
      var host = stage.getBoundingClientRect();
      if (clientX == null) { clientX = host.left + host.width / 2; clientY = host.top + host.height / 2; }
      var before = pagePoint({ clientX: clientX, clientY: clientY });
      zoom = Math.max(0.1, Math.min(4, next));
      fitMode = false;
      applyWorld();
      var after = pagePoint({ clientX: clientX, clientY: clientY });
      var rect = sheet.getBoundingClientRect();
      panX += (after.x - before.x) / C.PAGE_W * rect.width;
      panY += (after.y - before.y) / C.PAGE_H * rect.height;
      applyWorld();
      paintOverlay();
    }

    function isTyping(target) {
      return !!(target && target.closest && target.closest("input, textarea, select, [contenteditable=true]"));
    }

    root.addEventListener("keydown", function (event) {
      if (document.querySelector("dialog[open]")) return;
      var typing = isTyping(event.target);
      var mod = event.metaKey || event.ctrlKey;
      var key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
      if (key === "Escape") {
        if (document.querySelector(".menu-pop")) { closeMenus(); return; }
        if (!gallery.hidden) { gallery.hidden = true; return; }
        if (typing) { event.target.blur(); return; }
        selected = [];
        paintSelection();
        return;
      }
      if (typing) return;
      if (event.code === "Space") { event.preventDefault(); space = true; stage.classList.add("can-pan"); return; }
      if (mod && key === "z") { event.preventDefault(); if (event.shiftKey) redo(); else undo(); return; }
      if (mod && key === "y") { event.preventDefault(); redo(); return; }
      if (mod && key === "c") { copySel(); return; }
      if (mod && key === "x") { copySel(); removeSel(); return; }
      if (mod && key === "d") { event.preventDefault(); duplicateSel(); return; }
      if (mod && key === "g") { event.preventDefault(); if (event.shiftKey) ungroup(); else group(); return; }
      if (mod && key === "l") { event.preventDefault(); toggleLock(); return; }
      if (mod && (key === "]" || key === "}")) { event.preventDefault(); order(event.shiftKey ? "front" : "forward"); return; }
      if (mod && (key === "[" || key === "{")) { event.preventDefault(); order(event.shiftKey ? "back" : "backward"); return; }
      if (mod && key === "a") {
        event.preventDefault();
        selected = page().elements.filter(function (element) { return !element.hidden && !element.locked; }).map(function (element) { return element.id; });
        paintSelection();
        return;
      }
      if (mod) return;
      if (key === "Delete" || key === "Backspace") { event.preventDefault(); removeSel(); return; }
      if (key === "Enter" && single()) { event.preventDefault(); startTextEdit(single()); return; }
      if (key === "?") { event.preventDefault(); openShortcuts(); return; }
      if (event.shiftKey && event.code === "Digit1") { event.preventDefault(); fit(); return; }
      if (event.shiftKey && event.code === "Digit0") { event.preventDefault(); actualSize(); return; }
      var step = event.shiftKey ? 5 : 0.5;
      var move = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[key];
      if (move && selected.length) {
        event.preventDefault();
        var movable = selectedElements().filter(function (element) { return !element.locked; });
        if (!movable.length) return;
        if (!event.repeat) pushHistory();
        movable.forEach(function (element) { C.moveElement(element, move[0], move[1]); });
        renderSheet();
        paintOverlay();
        paintInspector();
        saveDraft();
      }
    });
    root.addEventListener("keyup", function (event) {
      if (event.code === "Space") { space = false; stage.classList.remove("can-pan"); }
    });
    root.addEventListener("blur", function () { space = false; stage.classList.remove("can-pan"); });

    document.addEventListener("paste", function (event) {
      if (isTyping(event.target) || document.querySelector("dialog[open]")) return;
      var items = event.clipboardData ? Array.prototype.slice.call(event.clipboardData.files || []) : [];
      var image = items.filter(function (file) { return /^image\//.test(file.type); })[0];
      if (image) {
        event.preventDefault();
        readImage(image, function (url, size) { addElement("image", Object.assign({ src: url, name: t("type_image") }, size)); });
        return;
      }
      if (clipboard.length) { event.preventDefault(); pasteSel(); }
    });

    function copySel() {
      clipboard = selectedElements().map(function (element) { return JSON.parse(JSON.stringify(element)); });
    }
    function pasteSel() {
      if (!clipboard.length) return;
      pushHistory();
      var top = C.maxZ(page().elements);
      var groups = {};
      selected = clipboard.map(function (element, index) {
        var copy = JSON.parse(JSON.stringify(element));
        copy.id = C.uid("el");
        if (copy.groupId) copy.groupId = groups[copy.groupId] || (groups[copy.groupId] = C.uid("grp"));
        copy.z = top + index + 1;
        copy.locked = false;
        C.moveElement(copy, 4, 4);
        page().elements.push(copy);
        return copy.id;
      });
      clipboard = selectedElements().map(function (element) { return JSON.parse(JSON.stringify(element)); });
      paint();
    }
    function duplicateSel() {
      var keep = clipboard;
      copySel();
      pasteSel();
      clipboard = keep;
    }
    function removeSel() {
      var doomed = selectedElements().filter(function (element) { return !element.locked; });
      if (!doomed.length) return;
      pushHistory();
      var ids = doomed.map(function (element) { return element.id; });
      page().elements = page().elements.filter(function (element) { return ids.indexOf(element.id) === -1; });
      selected = [];
      paint();
    }
    function toggleLock() {
      var list = selectedElements();
      if (!list.length) return;
      pushHistory();
      var lock = !list.every(function (element) { return element.locked; });
      list.forEach(function (element) { element.locked = lock; });
      paint();
    }
    function group() {
      if (selected.length < 2) return;
      pushHistory();
      var id = C.uid("grp");
      selectedElements().forEach(function (element) { element.groupId = id; });
      paint();
    }
    function ungroup() {
      if (!selectedElements().some(function (element) { return element.groupId; })) return;
      pushHistory();
      selectedElements().forEach(function (element) { element.groupId = ""; });
      paint();
    }
    function order(dir) {
      if (!selected.length) return;
      pushHistory();
      C.reorder(page().elements, selected, dir);
      paint();
    }
    /** One element aligns to the page. Several align to each other. */
    function align(mode) {
      var elements = selectedElements().filter(function (element) { return !element.locked; });
      if (!elements.length) return;
      pushHistory();
      if (elements.length === 1 || withGroups([elements[0].id]).length === elements.length) {
        var own = C.unionBounds(elements);
        var dx = 0, dy = 0;
        if (mode === "left") dx = -own.x;
        if (mode === "center") dx = (PAGE_BOX.w - own.w) / 2 - own.x;
        if (mode === "right") dx = PAGE_BOX.w - own.w - own.x;
        if (mode === "top") dy = -own.y;
        if (mode === "middle") dy = (PAGE_BOX.h - own.h) / 2 - own.y;
        if (mode === "bottom") dy = PAGE_BOX.h - own.h - own.y;
        elements.forEach(function (element) { C.moveElement(element, dx, dy); });
      } else {
        C.alignElements(elements, mode);
      }
      paint();
    }
    function distribute(axis) {
      var elements = selectedElements();
      if (elements.length < 3) return;
      pushHistory();
      C.distribute(elements, axis);
      paint();
    }

    /** Adds an element centred on a point in millimetres, or in the middle of the view. */
    function addElement(type, partial, at, skipHistory) {
      if (!skipHistory) pushHistory();
      var element = C.newElement(type, Object.assign({}, partial || {}, { z: C.maxZ(page().elements) + 1 }));
      var center = at || visibleCenter();
      var box = C.unionBounds([element]);
      C.moveElement(element, center.x - (box.x + box.w / 2), center.y - (box.y + box.h / 2));
      page().elements.push(element);
      selected = [element.id];
      if (gallery) gallery.hidden = true;
      paint();
      return element;
    }

    function addFromKind(kind, at) {
      if (kind === "upload") { document.getElementById("image-file").click(); return; }
      var sub = kind.split(":")[1];
      if (kind.indexOf("text:") === 0) {
        var label = t({ heading: "newHeading", subheading: "newSubheading", body: "newBody" }[sub]);
        addElement("text", { preset: sub, name: label, text: label }, at);
      } else if (kind.indexOf("shape:") === 0) addElement("shape", { shape: sub, name: t(sub === "rect" ? "rectangle" : sub), fill: doc.brand.colors[2] || "#1A1A1A" }, at);
      else if (kind === "line") addElement("line", { name: t("line"), x1: 20, y1: 40, x2: 100, y2: 40 }, at);
      else if (kind === "arrow") addElement("line", { name: t("arrow"), arrow: true, x1: 20, y1: 40, x2: 80, y2: 40 }, at);
      else if (kind === "frame") addElement("image", { clip: "parallelogram", src: "", w: 60, h: 80, name: t("photoFrame") }, at);
      else if (kind === "logo") addElement("image", { slot: "clientLogo", name: t("logoSlot"), w: 32, h: 14 }, at);
      else if (kind === "chart") addElement("chart", { name: t("chart") }, at);
      else if (kind === "table") addElement("table", { name: t("findingsTable") }, at);
    }

    leftBody.addEventListener("dragstart", function (event) {
      var add = event.target.closest && event.target.closest("[data-add]");
      if (!add || add.getAttribute("data-add") === "upload") { event.preventDefault(); return; }
      event.dataTransfer.setData("application/x-icarus-add", add.getAttribute("data-add"));
      event.dataTransfer.effectAllowed = "copy";
    });
    stage.addEventListener("dragover", function (event) {
      var types = Array.prototype.slice.call(event.dataTransfer.types || []);
      if (types.indexOf("application/x-icarus-add") === -1 && types.indexOf("Files") === -1) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = "copy";
      stage.classList.add("drop-target");
    });
    stage.addEventListener("dragleave", function () { stage.classList.remove("drop-target"); });
    stage.addEventListener("drop", function (event) {
      stage.classList.remove("drop-target");
      var at = pagePoint(event);
      var kind = event.dataTransfer.getData("application/x-icarus-add");
      event.preventDefault();
      if (kind) { addFromKind(kind, at); return; }
      Array.prototype.slice.call(event.dataTransfer.files || []).slice(0, 6).forEach(function (file, index) {
        readImage(file, function (url, size) {
          addElement("image", Object.assign({ src: url, name: t("type_image") }, size), { x: at.x + index * 6, y: at.y + index * 6 });
        });
      });
    });

    function paintLeft() {
      document.querySelectorAll("[data-left]").forEach(function (button) {
        button.setAttribute("aria-selected", button.getAttribute("data-left") === leftTab ? "true" : "false");
      });
      var scroll = leftBody.scrollTop;
      if (leftTab === "elements") leftBody.innerHTML = elementsHtml();
      else if (leftTab === "pages") leftBody.innerHTML = pagesHtml();
      else if (leftTab === "layers") leftBody.innerHTML = layersHtml();
      else leftBody.innerHTML = brandHtml();
      leftBody.scrollTop = scroll;
    }

    function elementsHtml() {
      function tile(action, glyph, label) {
        return '<button type="button" class="tile" draggable="true" data-add="' + action + '"><span class="glyph" aria-hidden="true">' + glyph + "</span>" + esc(label) + "</button>";
      }
      var shapeSvg = function (inner) { return '<svg viewBox="0 0 24 24">' + inner + "</svg>"; };
      return '<p class="help">' + esc(t("elementsHelp")) + "</p>" +
        '<p class="group-label">' + esc(t("textStyles")) + '</p><div class="tiles text-tiles">' +
        '<button type="button" class="tile wide" draggable="true" data-add="text:heading"><span class="sample-h">' + esc(t("heading")) + "</span></button>" +
        '<button type="button" class="tile wide" draggable="true" data-add="text:subheading"><span class="sample-s">' + esc(t("subheading")) + "</span></button>" +
        '<button type="button" class="tile wide" draggable="true" data-add="text:body"><span class="sample-b">' + esc(t("body")) + "</span></button>" +
        '</div><p class="group-label">' + esc(t("shapes")) + '</p><div class="tiles tiles-3">' +
        tile("shape:rect", shapeSvg('<rect x="3" y="6" width="18" height="12" fill="currentColor"/>'), t("rectangle")) +
        tile("shape:ellipse", shapeSvg('<ellipse cx="12" cy="12" rx="9" ry="7" fill="currentColor"/>'), t("ellipse")) +
        tile("shape:triangle", shapeSvg('<path d="M3 5h18l-9 15z" fill="currentColor"/>'), t("triangle")) +
        tile("shape:polygon", shapeSvg('<path d="M12 3l9 6-3 11H6L3 9z" fill="currentColor"/>'), t("polygon")) +
        tile("shape:parallelogram", shapeSvg('<path d="M7 6h14l-4 12H3z" fill="currentColor"/>'), t("parallelogram")) +
        tile("line", shapeSvg('<path d="M4 18L20 6" stroke="currentColor" stroke-width="2"/>'), t("line")) +
        tile("arrow", shapeSvg('<path d="M4 12h14M13 7l5 5-5 5" stroke="currentColor" stroke-width="2" fill="none"/>'), t("arrow")) +
        '</div><p class="group-label">' + esc(t("images")) + '</p><div class="tiles">' +
        tile("upload", icon("plus"), t("uploadImage")) + tile("frame", shapeSvg('<path d="M7 4h14l-4 16H3z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-dasharray="3 2"/>'), t("photoFrame")) +
        tile("logo", shapeSvg('<rect x="3" y="7" width="18" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="1.6" stroke-dasharray="3 2"/>'), t("logoSlot")) +
        '</div><p class="group-label">' + esc(t("dataBlocks")) + '</p><div class="tiles">' +
        tile("chart", shapeSvg('<path d="M5 20V12M10 20V6M15 20v-9M20 20v-4" stroke="currentColor" stroke-width="3"/>'), t("chart")) +
        tile("table", shapeSvg('<rect x="3" y="5" width="18" height="14" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M3 10h18M3 14.5h18M9 5v14" stroke="currentColor" stroke-width="1.6"/>'), t("findingsTable")) +
        '</div><input id="image-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden>';
    }

    function pagesHtml() {
      var scale = 52 / (C.PAGE_W / MM_PER_PX);
      var rows = doc.pages.map(function (item, index) {
        var thumb = '<span class="page-thumb" aria-hidden="true"><span class="sheet" style="background:' + item.background + ";transform:scale(" + scale + ')">' +
          C.renderPage(item, { vars: C.sampleVars(doc, index, doc.pages.length), logo: doc.brand.logo }) + "</span></span>";
        return '<div class="page-row' + (index === pageIndex ? " active" : "") + '">' +
          '<button type="button" class="page-pick" data-page="' + index + '">' + thumb + '<span class="page-meta"><strong>' + esc(item.name) + "</strong><small>" + (index + 1) + " · " + esc(t(item.role === "cover" ? "cover" : "interior")) + (item.required ? " · " + esc(t("requiredShort")) : "") + "</small></span></button>" +
          '<span class="row-tools"><button type="button" class="mini" data-page-move="' + index + ':-1" aria-label="' + esc(t("moveUp")) + '" title="' + esc(t("moveUp")) + '"' + (index === 0 ? " disabled" : "") + ">" + icon("up") + "</button>" +
          '<button type="button" class="mini" data-page-move="' + index + ':1" aria-label="' + esc(t("moveDown")) + '" title="' + esc(t("moveDown")) + '"' + (index === doc.pages.length - 1 ? " disabled" : "") + ">" + icon("down") + "</button></span></div>";
      }).join("");
      return rows + '<div class="arrange"><button type="button" class="text-btn" data-page-action="add">' + icon("plus") + esc(t("addPage")) + '</button><button type="button" class="text-btn" data-page-action="duplicate">' + esc(t("duplicatePage")) + '</button><button type="button" class="text-btn danger" data-page-action="delete"' + (page().required || doc.pages.length < 2 ? " disabled title=\"" + esc(t("cannotDelete")) + "\"" : "") + ">" + esc(t("deletePage")) + "</button></div>";
    }

    function layersHtml() {
      var rows = page().elements.slice().sort(function (a, b) { return b.z - a.z; }).map(function (element) {
        var on = selected.indexOf(element.id) !== -1;
        return '<div class="layer-row' + (on ? " active" : "") + (element.hidden ? " is-hidden" : "") + '"><button type="button" class="grow" data-layer="' + esc(element.id) + '"><span class="layer-type">' + esc(t("type_" + element.type)) + "</span>" + esc(element.name || t("type_" + element.type)) + (element.groupId ? ' <span class="layer-group">' + esc(t("grouped")) + "</span>" : "") + "</button>" +
          '<button type="button" class="mini" data-hide="' + esc(element.id) + '" aria-pressed="' + element.hidden + '" aria-label="' + esc(t("hide")) + '" title="' + esc(t(element.hidden ? "show" : "hide")) + '">' + icon(element.hidden ? "eyeOff" : "eye") + "</button>" +
          '<button type="button" class="mini" data-lock="' + esc(element.id) + '" aria-pressed="' + element.locked + '" aria-label="' + esc(t("lock")) + '" title="' + esc(t(element.locked ? "unlock" : "lock")) + '">' + icon(element.locked ? "lock" : "unlock") + "</button></div>";
      }).join("");
      return rows ? '<p class="help">' + esc(t("layersHelp")) + "</p>" + rows : '<p class="help">' + esc(t("layersEmpty")) + "</p>";
    }

    function brandHtml() {
      var swatches = doc.brand.colors.map(function (color, index) {
        return '<label class="swatch" style="background:' + color + '" title="' + color + '"><span class="sr">' + esc(t("brandColor")) + " " + (index + 1) + '</span><input type="color" data-brand-color="' + index + '" value="' + color.toLowerCase() + '"></label>';
      }).join("");
      var fonts = C.FONTS.map(function (font) {
        return '<button type="button" class="font-sample" data-font="' + font.id + '" style="font-family:' + esc(C.fontCss(font.id)) + '">' + esc(font.label) + "</button>";
      }).join("");
      var logo = doc.brand.logo
        ? '<div class="logo-preview"><img alt="" src="' + esc(doc.brand.logo) + '"><button type="button" class="text-btn" data-logo-remove>' + esc(t("remove")) + "</button></div>"
        : "";
      return '<p class="help">' + esc(t("brandHelp")) + "</p>" + logo +
        '<label class="text-btn file-btn">' + esc(doc.brand.logo ? t("replaceLogo") : t("logo")) + '<input id="logo-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp"></label>' +
        '<p class="group-label">' + esc(t("palette")) + '</p><div class="swatches">' + swatches + "</div>" +
        '<p class="group-label">' + esc(t("fonts")) + '</p><p class="help">' + esc(t("fontsHelp")) + "</p>" + fonts +
        '<p class="group-label">' + esc(t("sampleData")) + '</p><p class="help">' + esc(t("sampleHelp")) + "</p>" +
        field("documentTitle", t("reportTitle"), doc.fields.documentTitle) +
        field("client", t("client"), doc.fields.client) +
        field("project", t("project"), doc.fields.project) +
        field("author", t("tester"), doc.fields.author) +
        field("date", t("date"), doc.fields.date) +
        field("version", t("version"), doc.fields.version) +
        field("classification", t("var_classification"), doc.fields.classification);
    }
    function field(key, label, value) {
      return '<label class="field">' + esc(label) + '<input data-field="' + key + '" value="' + esc(value) + '"></label>';
    }

    function paintInspector() {
      if (inspector.contains(document.activeElement) && document.activeElement.matches("input:not([type=checkbox]):not([type=color]):not([type=range]), textarea")) return;
      var elements = selectedElements();
      if (!elements.length) { inspector.innerHTML = pageInspector(); return; }
      if (elements.length > 1) { inspector.innerHTML = multiInspector(elements); return; }
      inspector.innerHTML = elementInspector(elements[0]);
    }

    function section(title, body) {
      return '<section class="insp-section"><h3>' + esc(title) + "</h3>" + body + "</section>";
    }
    function toolButton(act, value, iconName, label, pressed, disabled) {
      return '<button type="button" class="tool" data-act="' + act + '" data-value="' + value + '" title="' + esc(label) + '" aria-label="' + esc(label) + '"' + (pressed != null ? ' aria-pressed="' + !!pressed + '"' : "") + (disabled ? " disabled" : "") + ">" + icon(iconName) + "</button>";
    }
    function alignBar(multi) {
      return '<div class="toolbar" role="group" aria-label="' + esc(t("alignGroup")) + '">' +
        toolButton("align", "left", "alignLeft", t(multi ? "alignLeft" : "alignPageLeft")) +
        toolButton("align", "center", "alignCenter", t(multi ? "alignCenter" : "alignPageCenter")) +
        toolButton("align", "right", "alignRight", t(multi ? "alignRight" : "alignPageRight")) +
        '<span class="sep"></span>' +
        toolButton("align", "top", "alignTop", t(multi ? "alignTop" : "alignPageTop")) +
        toolButton("align", "middle", "alignMiddle", t(multi ? "alignMiddle" : "alignPageMiddle")) +
        toolButton("align", "bottom", "alignBottom", t(multi ? "alignBottom" : "alignPageBottom")) +
        "</div>";
    }
    function actionBar(elements) {
      var locked = elements.every(function (element) { return element.locked; });
      var grouped = elements.some(function (element) { return element.groupId; });
      return '<div class="toolbar" role="group" aria-label="' + esc(t("arrangeGroup")) + '">' +
        toolButton("order", "front", "front", t("front") + " (Ctrl+Shift+])") +
        toolButton("order", "forward", "forward", t("forward") + " (Ctrl+])") +
        toolButton("order", "backward", "backward", t("backward") + " (Ctrl+[)") +
        toolButton("order", "back", "back", t("back") + " (Ctrl+Shift+[)") +
        '<span class="sep"></span>' +
        (elements.length > 1 ? toolButton("edit", "group", "group", t("group") + " (Ctrl+G)") : "") +
        (grouped ? toolButton("edit", "ungroup", "ungroup", t("ungroup") + " (Ctrl+Shift+G)") : "") +
        toolButton("edit", "lock", locked ? "lock" : "unlock", t(locked ? "unlock" : "lock") + " (Ctrl+L)", locked) +
        toolButton("edit", "duplicate", "duplicate", t("duplicate") + " (Ctrl+D)") +
        toolButton("edit", "delete", "trash", t("del") + " (Delete)", null, locked) +
        "</div>";
    }

    function pageInspector() {
      var current = page();
      return '<div class="insp-head"><strong>' + esc(t("pageSettings")) + "</strong></div>" +
        '<p class="help">' + esc(t("noSelection")) + "</p>" +
        '<label class="field">' + esc(t("pageName")) + '<input data-page-name maxlength="80" value="' + esc(current.name) + '"></label>' +
        '<label class="field">' + esc(t("role")) + '<select data-page-role><option value="cover"' + (current.role === "cover" ? " selected" : "") + ">" + esc(t("cover")) + '</option><option value="interior"' + (current.role === "interior" ? " selected" : "") + ">" + esc(t("interior")) + "</option></select></label>" +
        colorField("page-bg", t("background"), current.background) +
        '<label class="check"><input data-page-required type="checkbox"' + (current.required ? " checked" : "") + "> " + esc(t("requiredPage")) + "</label>" +
        '<p class="help">' + esc(t("requiredHelp")) + "</p>" +
        '<p class="tip">' + esc(t("shortcutTip")) + "</p>";
    }

    function multiInspector(elements) {
      return '<div class="insp-head"><strong>' + elements.length + " " + esc(t("multi")) + "</strong></div>" +
        section(t("alignGroup"), alignBar(true) + (elements.length > 2 ? '<div class="toolbar">' + toolButton("dist", "h", "distributeH", t("distributeH")) + toolButton("dist", "v", "distributeV", t("distributeV")) + "</div>" : "")) +
        section(t("arrangeGroup"), actionBar(elements));
    }

    function numField(prop, label, value, opts) {
      opts = opts || {};
      return '<label class="field' + (opts.cls ? " " + opts.cls : "") + '">' + esc(label) + '<span class="num"><input data-prop="' + prop + '" type="number" inputmode="decimal" step="' + (opts.step || 0.5) + '"' + (opts.min != null ? ' min="' + opts.min + '"' : "") + (opts.max != null ? ' max="' + opts.max + '"' : "") + ' value="' + value + '">' + (opts.unit ? "<i>" + esc(opts.unit) + "</i>" : "") + "</span></label>";
    }
    function rangeField(prop, label, value) {
      var pct = Math.round(value * 100);
      return '<label class="field">' + esc(label) + '<span class="range"><input data-prop="' + prop + '" data-scale="100" type="range" min="0" max="100" step="1" value="' + pct + '"><output>' + pct + "%</output></span></label>";
    }
    function colorField(prop, label, value, noneLabel) {
      var page = prop === "page-bg";
      var attr = page ? "data-page-bg" : 'data-prop="' + prop + '"';
      var chips = doc.brand.colors.map(function (color) {
        return '<button type="button" class="chip' + (value === color ? " on" : "") + '" data-chip="' + prop + '" data-color="' + color + '" style="background:' + color + '" title="' + color + '" aria-label="' + esc(t("useColor")) + " " + color + '"></button>';
      }).join("");
      var none = noneLabel ? '<button type="button" class="chip none' + (!value ? " on" : "") + '" data-chip="' + prop + '" data-color="" title="' + esc(noneLabel) + '" aria-label="' + esc(noneLabel) + '"></button>' : "";
      return '<div class="field"><span>' + esc(label) + '</span><div class="color-row"><label class="color-well" style="background:' + (value || "transparent") + '"><input type="color" ' + attr + ' value="' + (value || "#ffffff").toLowerCase() + '" aria-label="' + esc(label) + '"></label>' + chips + none + "</div></div>";
    }
    function check(prop, label, on) {
      return '<label class="check"><input data-prop="' + prop + '" type="checkbox"' + (on ? " checked" : "") + "> " + esc(label) + "</label>";
    }
    function selectField(prop, label, value, options) {
      return '<label class="field">' + esc(label) + '<select data-prop="' + prop + '">' + options.map(function (option) {
        return '<option value="' + esc(option[0]) + '"' + (String(value) === String(option[0]) ? " selected" : "") + ">" + esc(option[1]) + "</option>";
      }).join("") + "</select></label>";
    }
    function seg(prop, value, current, iconName, label) {
      return '<button type="button" class="tool" data-prop-set="' + prop + '" data-value="' + value + '" aria-pressed="' + (String(current) === String(value)) + '" title="' + esc(label) + '" aria-label="' + esc(label) + '">' + icon(iconName) + "</button>";
    }

    function elementInspector(element) {
      var html = '<div class="insp-head"><input class="el-name" data-prop="name" maxlength="80" value="' + esc(element.name || "") + '" placeholder="' + esc(t("type_" + element.type)) + '" aria-label="' + esc(t("elementName")) + '"><span class="badge">' + esc(t("type_" + element.type)) + "</span></div>";
      if (element.locked) html += '<p class="note">' + esc(t("lockedNote")) + "</p>";
      html += actionBar([element]);
      if (element.type === "text") html += textSection(element);
      if (element.type === "shape") html += shapeSection(element);
      if (element.type === "image") html += imageSection(element);
      if (element.type === "line") html += lineSection(element);
      if (element.type === "chart") html += chartSection(element);
      if (element.type === "table") html += tableSection(element);
      var position = element.type === "line"
        ? '<div class="row-2">' + numField("x1", t("startX"), element.x1, { unit: "mm" }) + numField("y1", t("startY"), element.y1, { unit: "mm" }) + numField("x2", t("endX"), element.x2, { unit: "mm" }) + numField("y2", t("endY"), element.y2, { unit: "mm" }) + "</div>"
        : alignBar(false) + '<div class="row-2">' + numField("x", t("fromLeft"), element.x, { unit: "mm" }) + numField("y", t("fromTop"), element.y, { unit: "mm" }) + numField("w", t("width"), element.w, { unit: "mm", min: 1 }) + numField("h", t("height"), element.h, { unit: "mm", min: 1 }) + "</div>" + numField("rotation", t("rotation"), element.rotation || 0, { step: 1, min: -180, max: 180, unit: "°" });
      html += section(t("position"), position);
      var more = rangeField("opacity", t("opacity"), element.opacity);
      if (element.type === "text") {
        more += '<div class="row-2">' + numField("letterSpacing", t("letterSpacing"), element.letterSpacing, { step: 0.1, min: -4, max: 8, unit: "px" }) + numField("padding", t("padding"), element.padding, { step: 0.5, min: 0, max: 20, unit: "mm" }) + "</div>";
        more += colorField("fill", t("textBackground"), element.fill, t("noFill"));
        more += check("blend", t("invert"), element.blend === "difference") + '<p class="help">' + esc(t("invertHelp")) + "</p>";
      }
      html += '<details class="more"' + (moreOpen ? " open" : "") + "><summary>" + esc(t("moreOptions")) + "</summary>" + more + "</details>";
      return html;
    }

    function textSection(element) {
      var body = '<label class="field">' + esc(t("text")) + '<textarea data-prop="text" rows="3">' + esc(element.text) + "</textarea></label>" +
        '<div class="arrange"><button type="button" class="text-btn" data-insert-field>' + esc(t("insertField")) + '</button><button type="button" class="text-btn" data-edit-text>' + icon("edit") + esc(t("editOnPage")) + "</button></div>" +
        '<div class="row-2">' + selectField("font", t("font"), element.font, C.FONTS.map(function (font) { return [font.id, font.label]; })) + numField("size", t("size"), element.size, { step: 1, min: 6, max: 200, unit: "pt" }) + "</div>" +
        '<div class="toolbar">' +
        seg("align", "left", element.align, "textLeft", t("left")) + seg("align", "center", element.align, "textCenter", t("center")) + seg("align", "right", element.align, "textRight", t("right")) +
        '<span class="sep"></span>' +
        '<button type="button" class="tool" data-toggle="weight" aria-pressed="' + (element.weight >= 700) + '" title="' + esc(t("bold")) + '" aria-label="' + esc(t("bold")) + '">' + icon("bold") + "</button>" +
        '<button type="button" class="tool" data-toggle="italic" aria-pressed="' + !!element.italic + '" title="' + esc(t("italic")) + '" aria-label="' + esc(t("italic")) + '">' + icon("italic") + "</button>" +
        "</div>" +
        colorField("color", t("color"), element.color) +
        numField("lineHeight", t("lineSpacing"), element.lineHeight, { step: 0.05, min: 0.7, max: 2 });
      return section(t("textGroup"), body);
    }

    function shapeSection(element) {
      var shapes = ["rect", "ellipse", "triangle", "polygon", "parallelogram"].map(function (shape) { return [shape, t(shape === "rect" ? "rectangle" : shape)]; });
      return section(t("shapeGroup"), selectField("shape", t("shapeKind"), element.shape, shapes) +
        colorField("fill", t("fill"), element.fill) +
        colorField("stroke", t("border"), element.stroke, t("noBorder")) +
        numField("strokeWidth", t("borderWidth"), element.strokeWidth || 0, { step: 0.1, min: 0, max: 8, unit: "mm" }));
    }

    function imageSection(element) {
      var body = "";
      if (element.slot === "clientLogo") body += '<p class="help">' + esc(t("clientSlotHelp")) + "</p>";
      else if (element.slot === "companyLogo") body += '<p class="help">' + esc(t("companySlotHelp")) + "</p>";
      else body += '<button type="button" class="text-btn block" data-replace-image>' + esc(element.src ? t("replaceImage") : t("uploadImage")) + "</button>";
      body += selectField("clip", t("frame"), element.clip, ["rect", "ellipse", "parallelogram", "triangle"].map(function (clip) { return [clip, t(clip === "rect" ? "rectangle" : clip)]; }));
      body += check("grayscale", t("grayscale"), element.grayscale);
      body += colorField("tint", t("tint"), element.tint, t("noTint"));
      if (element.tint) body += rangeField("tintOpacity", t("tintStrength"), element.tintOpacity);
      return section(t("imageGroup"), body);
    }

    function lineSection(element) {
      return section(t("lineGroup"), colorField("stroke", t("border"), element.stroke) +
        numField("strokeWidth", t("borderWidth"), element.strokeWidth, { step: 0.05, min: 0.1, max: 8, unit: "mm" }) +
        check("arrow", t("arrowHead"), element.arrow) +
        '<p class="help">' + esc(t("lineHelp")) + "</p>");
    }

    function chartSection(element) {
      var body = selectField("chart", t("chartKind"), element.chart, [["bar", t("chartBar")], ["pie", t("chartPie")], ["line", t("chartLine")]]) +
        selectField("binding", t("chartData"), element.binding, [["findingsBySeverity", t("chartLive")], ["static", t("chartStatic")]]);
      body += '<div class="cat-list">' + element.categories.map(function (cat, index) {
        return '<div class="cat-row"><label class="color-well small" style="background:' + cat.color + '"><input type="color" data-cat-color="' + index + '" value="' + cat.color.toLowerCase() + '" aria-label="' + esc(cat.label) + '"></label>' +
          '<input class="cat-label" data-cat-label="' + index + '" value="' + esc(cat.label) + '" maxlength="24" aria-label="' + esc(t("label")) + '">' +
          (element.binding === "static" ? '<input class="cat-value" data-cat-value="' + index + '" type="number" min="0" max="999" value="' + cat.value + '" aria-label="' + esc(t("value")) + '">' : "") + "</div>";
      }).join("") + "</div>";
      body += '<p class="help">' + esc(t(element.binding === "static" ? "chartStaticHelp" : "chartLiveHelp")) + "</p>";
      return section(t("chartGroup"), body);
    }

    function tableSection(element) {
      var keys = TABLE_KEYS.map(function (key) { return [key, t("col_" + key)]; });
      var body = selectField("mode", t("tableData"), element.mode, [["findings", t("tableLive")], ["static", t("tableStatic")]]);
      body += '<p class="group-label">' + esc(t("columns")) + "</p>" + element.columns.map(function (col, index) {
        return '<div class="col-row"><input data-col-label="' + index + '" value="' + esc(col.label) + '" maxlength="40" aria-label="' + esc(t("label")) + '">' +
          (element.mode === "findings" ? '<select data-col-key="' + index + '" aria-label="' + esc(t("columnData")) + '">' + keys.map(function (key) {
            return '<option value="' + key[0] + '"' + (col.key === key[0] ? " selected" : "") + ">" + esc(key[1]) + "</option>";
          }).join("") + "</select>" : "") +
          '<button type="button" class="mini" data-col-remove="' + index + '" aria-label="' + esc(t("remove")) + '" title="' + esc(t("remove")) + '"' + (element.columns.length < 2 ? " disabled" : "") + ">" + icon("close") + "</button></div>";
      }).join("");
      if (element.columns.length < 6) body += '<button type="button" class="text-btn" data-col-add>' + icon("plus") + esc(t("addColumn")) + "</button>";
      if (element.mode === "static") {
        body += '<label class="field">' + esc(t("tableRows")) + '<textarea data-table-rows rows="4">' + esc(element.rows.map(function (row) { return row.join(" | "); }).join("\n")) + '</textarea></label><p class="help">' + esc(t("tableRowsHelp")) + "</p>";
      }
      body += '<div class="row-2">' + numField("size", t("size"), element.size, { step: 0.5, min: 6, max: 18, unit: "pt" }) + selectField("font", t("font"), element.font, C.FONTS.map(function (font) { return [font.id, font.label]; })) + "</div>";
      body += colorField("headerFill", t("headerFill"), element.headerFill);
      body += colorField("color", t("color"), element.color);
      return section(t("tableGroup"), body);
    }

    function afterPropChange(element) {
      if (element.type === "line") C.syncLineBox(element);
      renderSheet();
      if (fitTextHeight(element)) renderSheet();
      paintOverlay();
    }

    leftBody.addEventListener("click", function (event) {
      var target = event.target.closest ? event.target : event.target.parentElement;
      var add = target.closest("[data-add]");
      if (add) { addFromKind(add.getAttribute("data-add")); return; }
      var pageBtn = target.closest("[data-page]");
      if (pageBtn) { pageIndex = Number(pageBtn.getAttribute("data-page")); selected = []; paint(); return; }
      var pageMove = target.closest("[data-page-move]");
      if (pageMove) {
        var parts = pageMove.getAttribute("data-page-move").split(":").map(Number);
        var to = parts[0] + parts[1];
        if (to < 0 || to >= doc.pages.length) return;
        pushHistory();
        var moved = doc.pages.splice(parts[0], 1)[0];
        doc.pages.splice(to, 0, moved);
        pageIndex = to;
        paint();
        return;
      }
      var action = target.closest("[data-page-action]");
      if (action) {
        var name = action.getAttribute("data-page-action");
        if (name === "delete" && (page().required || doc.pages.length < 2)) { setStatus(t("cannotDelete")); return; }
        pushHistory();
        if (name === "add") {
          doc.pages.splice(pageIndex + 1, 0, C.blankPage(t("pageName") + " " + (doc.pages.length + 1)));
          pageIndex += 1;
        }
        if (name === "duplicate") {
          var copy = JSON.parse(JSON.stringify(page()));
          copy.id = C.uid("page");
          copy.required = false;
          copy.name = (copy.name + " 2").slice(0, 80);
          copy.elements.forEach(function (element) { element.id = C.uid("el"); });
          doc.pages.splice(pageIndex + 1, 0, copy);
          pageIndex += 1;
        }
        if (name === "delete") {
          doc.pages.splice(pageIndex, 1);
          pageIndex = Math.max(0, pageIndex - 1);
        }
        selected = [];
        paint();
        return;
      }
      var layer = target.closest("[data-layer]");
      if (layer) {
        var item = byId(layer.getAttribute("data-layer"));
        if (!item) return;
        if (event.shiftKey || event.metaKey || event.ctrlKey) {
          var at = selected.indexOf(item.id);
          if (at === -1) selected.push(item.id); else selected.splice(at, 1);
        } else selected = [item.id];
        paintSelection();
        return;
      }
      var hide = target.closest("[data-hide]");
      if (hide) {
        pushHistory();
        var hidden = byId(hide.getAttribute("data-hide"));
        hidden.hidden = !hidden.hidden;
        if (hidden.hidden) selected = selected.filter(function (id) { return id !== hidden.id; });
        paint();
        return;
      }
      var lock = target.closest("[data-lock]");
      if (lock) { pushHistory(); var locked = byId(lock.getAttribute("data-lock")); locked.locked = !locked.locked; paint(); return; }
      var font = target.closest("[data-font]");
      if (font) {
        var texts = selectedElements().filter(function (element) { return element.type === "text" || element.type === "table"; });
        if (!texts.length) { setStatus(t("fontPick")); return; }
        pushHistory();
        texts.forEach(function (element) { element.font = font.getAttribute("data-font"); });
        paint();
        return;
      }
      if (target.closest("[data-logo-remove]")) {
        pushHistory();
        doc.brand.logo = "";
        paint();
      }
    });
    leftBody.addEventListener("input", function (event) {
      var swatch = event.target.getAttribute("data-brand-color");
      if (swatch == null) return;
      if (!event.target.dataset.history) { pushHistory(); event.target.dataset.history = "1"; }
      doc.brand.colors[Number(swatch)] = event.target.value.toUpperCase();
      event.target.parentNode.style.background = event.target.value;
    });
    leftBody.addEventListener("change", function (event) {
      if (event.target.id === "image-file") {
        readImage(event.target.files[0], function (url, size) { addElement("image", Object.assign({ src: url, name: t("type_image") }, size)); });
        event.target.value = "";
        return;
      }
      if (event.target.id === "logo-file") {
        readImage(event.target.files[0], function (url, size) {
          pushHistory();
          doc.brand.logo = url;
          var hasSlot = doc.pages.some(function (item) { return item.elements.some(function (element) { return element.slot === "companyLogo"; }); });
          if (hasSlot) { paint(); return; }
          var height = 14;
          addElement("image", { src: url, slot: "companyLogo", w: Math.min(60, round(height * size.ratio)), h: height, name: t("logoName") }, { x: 30, y: 20 }, true);
        });
        event.target.value = "";
        return;
      }
      if (event.target.hasAttribute("data-brand-color")) {
        delete event.target.dataset.history;
        paint();
        return;
      }
      var fieldInput = event.target.closest && event.target.closest("[data-field]");
      if (fieldInput) {
        pushHistory();
        doc.fields[fieldInput.getAttribute("data-field")] = fieldInput.value.slice(0, 160);
        paint();
      }
    });

    inspector.addEventListener("toggle", function (event) {
      if (event.target.classList && event.target.classList.contains("more")) moreOpen = event.target.open;
    }, true);

    inspector.addEventListener("click", function (event) {
      var target = event.target.closest ? event.target : event.target.parentElement;
      var element = single();
      var act = target.closest("[data-act]");
      if (act) {
        var groupName = act.getAttribute("data-act");
        var value = act.getAttribute("data-value");
        if (groupName === "align") align(value);
        if (groupName === "dist") distribute(value);
        if (groupName === "order") order(value);
        if (groupName === "edit" && value === "group") group();
        if (groupName === "edit" && value === "ungroup") ungroup();
        if (groupName === "edit" && value === "lock") toggleLock();
        if (groupName === "edit" && value === "duplicate") duplicateSel();
        if (groupName === "edit" && value === "delete") removeSel();
        return;
      }
      var chip = target.closest("[data-chip]");
      if (chip) {
        var prop = chip.getAttribute("data-chip");
        var color = chip.getAttribute("data-color");
        pushHistory();
        if (prop === "page-bg") page().background = color || "#FFFFFF";
        else if (element) {
          element[prop] = color;
          if (prop === "stroke" && element.type === "shape" && color && !element.strokeWidth) element.strokeWidth = 0.5;
        }
        paint();
        return;
      }
      var set = target.closest("[data-prop-set]");
      if (set && element) {
        pushHistory();
        element[set.getAttribute("data-prop-set")] = set.getAttribute("data-value");
        paint();
        return;
      }
      var toggle = target.closest("[data-toggle]");
      if (toggle && element) {
        pushHistory();
        if (toggle.getAttribute("data-toggle") === "weight") element.weight = element.weight >= 700 ? 400 : 700;
        else element.italic = !element.italic;
        paint();
        return;
      }
      if (target.closest("[data-insert-field]")) { openVarMenu(target.closest("[data-insert-field]")); return; }
      if (target.closest("[data-edit-text]")) { startTextEdit(element); return; }
      if (target.closest("[data-col-add]") && element) {
        pushHistory();
        var used = element.columns.map(function (col) { return col.key; });
        var key = TABLE_KEYS.filter(function (item) { return used.indexOf(item) === -1; })[0] || "c" + element.columns.length;
        element.columns.push({ key: key, label: t("col_" + key) === "col_" + key ? t("column") : t("col_" + key) });
        paint();
        return;
      }
      var remove = target.closest("[data-col-remove]");
      if (remove && element && element.columns.length > 1) {
        pushHistory();
        element.columns.splice(Number(remove.getAttribute("data-col-remove")), 1);
        paint();
        return;
      }
      if (target.closest("[data-replace-image]") && element) replaceImage(element);
    });

    function replaceImage(element) {
      var picker = document.createElement("input");
      picker.type = "file";
      picker.accept = "image/png,image/jpeg,image/gif,image/webp";
      picker.addEventListener("change", function () {
        readImage(picker.files[0], function (url) {
          pushHistory();
          element.src = url;
          paint();
        });
      });
      picker.click();
    }

    inspector.addEventListener("input", function (event) {
      var target = event.target;
      var element = single();
      if (!target.dataset.history) { pushHistory(); target.dataset.history = "1"; }
      if (target.hasAttribute("data-page-name")) { page().name = target.value.slice(0, 80); return; }
      if (target.hasAttribute("data-page-bg")) {
        page().background = target.value.toUpperCase();
        sheet.style.background = page().background;
        target.parentNode.style.background = target.value;
        return;
      }
      if (!element) return;
      var catColor = target.getAttribute("data-cat-color");
      var catLabel = target.getAttribute("data-cat-label");
      var catValue = target.getAttribute("data-cat-value");
      var colLabel = target.getAttribute("data-col-label");
      if (catColor != null) { element.categories[Number(catColor)].color = target.value.toUpperCase(); target.parentNode.style.background = target.value; }
      else if (catLabel != null) element.categories[Number(catLabel)].label = target.value.slice(0, 24);
      else if (catValue != null) element.categories[Number(catValue)].value = Math.max(0, Math.min(999, Number(target.value) || 0));
      else if (colLabel != null) element.columns[Number(colLabel)].label = target.value.slice(0, 40);
      else if (target.hasAttribute("data-table-rows")) {
        element.rows = target.value.split("\n").slice(0, 12).filter(function (line) { return line.trim(); }).map(function (line) {
          return line.split("|").slice(0, 6).map(function (cell) { return cell.trim().slice(0, 180); });
        });
      } else {
        var prop = target.getAttribute("data-prop");
        if (!prop) return;
        if (target.type === "checkbox") {
          if (prop === "blend") {
            element.blend = target.checked ? "difference" : "normal";
            if (target.checked) element.color = "#FFFFFF";
          } else element[prop] = target.checked;
        } else if (target.type === "range") {
          element[prop] = Number(target.value) / 100;
          var out = target.parentNode.querySelector("output");
          if (out) out.textContent = target.value + "%";
        } else if (target.type === "number") {
          var n = Number(target.value);
          if (target.value === "" || !isFinite(n)) return;
          if (target.min !== "") n = Math.max(Number(target.min), n);
          if (target.max !== "") n = Math.min(Number(target.max), n);
          element[prop] = n;
        } else if (target.type === "color") {
          element[prop] = target.value.toUpperCase();
          target.parentNode.style.background = target.value;
        } else if (prop === "name") {
          element.name = target.value.slice(0, 80);
          if (leftTab === "layers") paintLeft();
          return;
        } else if (prop === "text") {
          element.text = target.value.slice(0, 2000);
        } else if (prop === "clip") {
          element.clip = target.value;
          element.points = target.value === "parallelogram" ? [[0.2, 0], [1, 0], [0.8, 1], [0, 1]] : target.value === "triangle" ? [[0, 0], [1, 0], [0.5, 1]] : [[0, 0], [1, 0], [1, 1], [0, 1]];
        } else if (prop === "shape") {
          element.shape = target.value;
          element.points = { triangle: [[0, 0], [1, 0], [0.5, 1]], polygon: [[0.5, 0], [1, 0.25], [0.8, 1], [0.2, 1], [0, 0.25]], parallelogram: [[0.2, 0], [1, 0], [0.8, 1], [0, 1]] }[target.value] || [[0, 0], [1, 0], [1, 1], [0, 1]];
        } else element[prop] = target.value;
      }
      afterPropChange(element);
    });
    inspector.addEventListener("change", function (event) {
      var target = event.target;
      delete target.dataset.history;
      if (target.hasAttribute("data-page-role")) page().role = target.value;
      if (target.hasAttribute("data-page-required")) page().required = target.checked;
      var colKey = target.getAttribute("data-col-key");
      var element = single();
      if (colKey != null && element) {
        var col = element.columns[Number(colKey)];
        var oldDefault = t("col_" + col.key);
        if (!col.label || col.label === oldDefault) col.label = t("col_" + target.value);
        col.key = target.value;
      }
      if (target.tagName === "SELECT" || target.type === "checkbox" || target.type === "color" || colKey != null || target.hasAttribute("data-page-name")) {
        target.blur();
        paint();
      } else {
        paintLeft();
        saveDraft();
      }
    });
    inspector.addEventListener("focusout", function (event) {
      if (event.target.getAttribute && event.target.getAttribute("data-prop") === "text") {
        caret = { start: event.target.selectionStart, end: event.target.selectionEnd };
      }
      setTimeout(function () {
        if (!inspector.contains(document.activeElement)) paintInspector();
      }, 0);
    });

    function openVarMenu(anchor) {
      closeMenus();
      var textarea = inspector.querySelector('[data-prop="text"]');
      if (textarea && document.activeElement === textarea) caret = { start: textarea.selectionStart, end: textarea.selectionEnd };
      var menu = document.createElement("div");
      menu.className = "menu-pop";
      menu.setAttribute("role", "menu");
      menu.innerHTML = C.VARIABLES.map(function (variable) {
        return '<button type="button" role="menuitem" data-var="' + variable.id + '"><span>' + esc(t("var_" + variable.id)) + "</span><code>" + esc(vars()[variable.id] || "") + "</code></button>";
      }).join("");
      document.body.appendChild(menu);
      placeMenu(menu, anchor.getBoundingClientRect().left, anchor.getBoundingClientRect().bottom + 4);
      menu.querySelector("button").focus();
      menu.addEventListener("click", function (event) {
        var button = event.target.closest("[data-var]");
        if (!button) return;
        var element = single();
        if (element && element.type === "text") {
          pushHistory();
          var token = "{{" + button.getAttribute("data-var") + "}}";
          var text = element.text || "";
          var start = caret && caret.start != null ? Math.min(caret.start, text.length) : text.length;
          var end = caret && caret.end != null ? Math.min(caret.end, text.length) : start;
          element.text = text.slice(0, start) + token + text.slice(end);
          caret = { start: start + token.length, end: start + token.length };
          renderSheet();
          fitTextHeight(element);
          paint();
        }
        menu.remove();
      });
    }

    function placeMenu(menu, x, y) {
      menu.style.left = "0px";
      menu.style.top = "0px";
      var w = menu.offsetWidth;
      var h = menu.offsetHeight;
      menu.style.left = Math.max(8, Math.min(root.innerWidth - w - 8, x)) + "px";
      menu.style.top = Math.max(8, Math.min(root.innerHeight - h - 8, y)) + "px";
    }
    document.addEventListener("pointerdown", function (event) {
      if (!event.target.closest(".menu-pop") && !event.target.closest("[data-insert-field]")) closeMenus();
    });

    stage.addEventListener("contextmenu", function (event) {
      event.preventDefault();
      closeMenus();
      var any = selected.length > 0;
      var list = selectedElements();
      var locked = any && list.every(function (element) { return element.locked; });
      var items = [
        ["copy", t("copy"), "Ctrl+C", !any],
        ["paste", t("paste"), "Ctrl+V", !clipboard.length],
        ["duplicate", t("duplicate"), "Ctrl+D", !any],
        ["delete", t("del"), "Del", !any || locked],
        null,
        ["front", t("front"), "Ctrl+Shift+]", !any],
        ["back", t("back"), "Ctrl+Shift+[", !any],
        null,
        ["lock", t(locked ? "unlock" : "lock"), "Ctrl+L", !any],
        ["group", t("group"), "Ctrl+G", list.length < 2],
        ["ungroup", t("ungroup"), "Ctrl+Shift+G", !list.some(function (element) { return element.groupId; })]
      ];
      if (single() && single().type === "text") items.unshift(["edit", t("editOnPage"), "Enter", single().locked], null);
      var menu = document.createElement("div");
      menu.className = "menu-pop";
      menu.setAttribute("role", "menu");
      menu.innerHTML = items.map(function (item) {
        if (!item) return '<hr>';
        return '<button type="button" role="menuitem" data-ctx="' + item[0] + '"' + (item[3] ? " disabled" : "") + "><span>" + esc(item[1]) + "</span><kbd>" + esc(item[2]) + "</kbd></button>";
      }).join("");
      document.body.appendChild(menu);
      placeMenu(menu, event.clientX, event.clientY);
      menu.addEventListener("click", function (clickEvent) {
        var button = clickEvent.target.closest("[data-ctx]");
        if (!button || button.disabled) return;
        var name = button.getAttribute("data-ctx");
        menu.remove();
        if (name === "copy") copySel();
        if (name === "paste") pasteSel();
        if (name === "duplicate") duplicateSel();
        if (name === "delete") removeSel();
        if (name === "front" || name === "back") order(name);
        if (name === "lock") toggleLock();
        if (name === "group") group();
        if (name === "ungroup") ungroup();
        if (name === "edit") startTextEdit(single());
      });
    });

    /** Re-encodes an upload as JPEG, or PNG when it has transparency, small enough to live in the template. */
    function readImage(file, done) {
      if (!file) return;
      if (!/^image\/(png|jpeg|gif|webp)$/.test(file.type)) { setStatus(t("imageType")); return; }
      if (file.size > 25 * 1024 * 1024) { setStatus(t("imageSize")); return; }
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.onload = function () {
        URL.revokeObjectURL(url);
        var data = encodeImage(img, file.type !== "image/jpeg");
        if (!data) { setStatus(t("imageSize")); return; }
        var ratio = img.naturalWidth / Math.max(1, img.naturalHeight);
        var w = ratio >= 1 ? 80 : round(80 * ratio);
        var h = ratio >= 1 ? round(80 / ratio) : 80;
        setStatus("");
        done(data, { w: w, h: h, ratio: ratio });
      };
      img.onerror = function () { URL.revokeObjectURL(url); setStatus(t("imageType")); };
      img.src = url;
    }
    function encodeImage(img, mayHaveAlpha) {
      var side = 1800;
      for (var attempt = 0; attempt < 7; attempt++) {
        var scale = Math.min(1, side / Math.max(img.naturalWidth, img.naturalHeight));
        var w = Math.max(1, Math.round(img.naturalWidth * scale));
        var h = Math.max(1, Math.round(img.naturalHeight * scale));
        var canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        var ctx = canvas.getContext("2d");
        ctx.drawImage(img, 0, 0, w, h);
        var alpha = mayHaveAlpha && hasAlpha(ctx, w, h);
        var data = alpha ? canvas.toDataURL("image/png") : canvas.toDataURL("image/jpeg", Math.max(0.6, 0.88 - attempt * 0.05));
        if (data.length <= C.MAX_IMAGE - 1000) return data;
        side = Math.round(side * 0.72);
      }
      return null;
    }
    function hasAlpha(ctx, w, h) {
      try {
        var pixels = ctx.getImageData(0, 0, w, h).data;
        var stride = Math.max(4, Math.floor(pixels.length / 4 / 40000) * 4);
        for (var i = 3; i < pixels.length; i += stride) if (pixels[i] < 250) return true;
      } catch (err) { return true; }
      return false;
    }

    function openGallery() {
      gallery.hidden = false;
      gallery.innerHTML = '<div class="gallery-head"><div><h2>' + esc(t("galleryTitle")) + '</h2><p class="help">' + esc(t("galleryLead")) + '</p></div><button type="button" class="icon-btn" data-close-gallery="1" aria-label="' + esc(t("close")) + '" title="' + esc(t("close")) + '">' + icon("close") + '</button></div><div class="cards">' +
        C.STARTERS.map(function (starter) {
          var sample = C.starterDoc(starter.id);
          var cover = sample.pages[0];
          var html = C.renderPage(cover, { vars: C.sampleVars(sample, 0, sample.pages.length) });
          return '<button type="button" class="card" data-starter="' + starter.id + '"><div class="thumb"><div class="thumb-scale"><div class="sheet" style="background:' + cover.background + '">' + html + '</div></div></div><strong>' + esc(t("starter_" + starter.id)) + "</strong><span>" + esc(t("starterBlurb_" + starter.id)) + "</span></button>";
        }).join("") + "</div>";
      var first = gallery.querySelector("[data-starter]");
      if (first) first.focus();
    }
    gallery.addEventListener("click", function (event) {
      if (event.target.closest && event.target.closest("[data-close-gallery]")) { gallery.hidden = true; return; }
      var card = event.target.closest && event.target.closest("[data-starter]");
      if (!card) return;
      var next = C.starterDoc(card.getAttribute("data-starter"));
      next.id = doc.id;
      next.name = t("starter_" + card.getAttribute("data-starter"));
      if (doc.brand.logo) next.brand.logo = doc.brand.logo;
      next.fields = doc.fields;
      gallery.hidden = true;
      loadDoc(next);
    });

    function fit() {
      var rect = stage.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      var pxW = C.PAGE_W / MM_PER_PX;
      var pxH = C.PAGE_H / MM_PER_PX;
      var pad = rect.width < 600 ? 16 : 48;
      zoom = Math.max(0.1, Math.min(1.5, Math.min((rect.width - pad) / pxW, (rect.height - pad) / pxH)));
      panX = (rect.width - pxW * zoom) / 2;
      panY = Math.max(pad / 2, (rect.height - pxH * zoom) / 2);
      fitMode = true;
      applyWorld();
      paintOverlay();
    }
    function actualSize() {
      var rect = stage.getBoundingClientRect();
      var center = pagePoint({ clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 });
      zoom = 1;
      fitMode = false;
      applyWorld();
      var now = toStage(center.x, center.y);
      panX += rect.width / 2 - now.x;
      panY += rect.height / 2 - now.y;
      applyWorld();
      paintOverlay();
    }

    function openShortcuts() {
      var dialog = document.getElementById("keys-dialog");
      if (!dialog) return;
      var rows = [
        ["kbSelect", "Click · Shift+Click"], ["kbMove", "Drag · ←↑→↓ · Shift+↑ (5 mm)"], ["kbAxis", "Shift+Drag"], ["kbNoSnap", "Alt+Drag"],
        ["kbRatio", "Shift+Resize"], ["kbRotate", "Shift+Rotate (15°)"], ["kbEdit", "Double-click · Enter"], ["kbUndo", "Ctrl+Z · Ctrl+Shift+Z"],
        ["kbCopy", "Ctrl+C · Ctrl+X · Ctrl+V"], ["kbDuplicate", "Ctrl+D"], ["kbGroup", "Ctrl+G · Ctrl+Shift+G"], ["kbOrder", "Ctrl+] · Ctrl+["],
        ["kbLock", "Ctrl+L"], ["kbAll", "Ctrl+A"], ["kbPan", "Space+Drag · Scroll"], ["kbZoom", "Ctrl+Scroll · Shift+1 · Shift+0"], ["kbImage", "Ctrl+V · Drop"]
      ];
      document.getElementById("keys-list").innerHTML = rows.map(function (row) {
        return "<tr><th>" + esc(t(row[0])) + "</th><td>" + row[1].split(" · ").map(function (key) { return "<kbd>" + esc(key) + "</kbd>"; }).join(" ") + "</td></tr>";
      }).join("");
      dialog.showModal();
    }

    document.getElementById("undo").addEventListener("click", undo);
    document.getElementById("redo").addEventListener("click", redo);
    document.getElementById("zoom-in").addEventListener("click", function () { zoomAt(zoom * 1.2); });
    document.getElementById("zoom-out").addEventListener("click", function () { zoomAt(zoom / 1.2); });
    document.getElementById("zoom-label").addEventListener("click", actualSize);
    document.getElementById("zoom-fit").addEventListener("click", fit);
    document.getElementById("templates").addEventListener("click", openGallery);
    var keysButton = document.getElementById("keys-open");
    if (keysButton) keysButton.addEventListener("click", openShortcuts);
    document.querySelectorAll("[data-left]").forEach(function (button) {
      button.addEventListener("click", function () { leftTab = button.getAttribute("data-left"); paintLeft(); });
    });
    nameInput.addEventListener("change", function () {
      pushHistory();
      doc.name = nameInput.value.trim().slice(0, 120) || t("untitled");
      nameInput.value = doc.name;
      saveDraft();
    });
    nameInput.addEventListener("keydown", function (event) { if (event.key === "Enter") nameInput.blur(); });
    document.getElementById("hint-dismiss").addEventListener("click", function () {
      document.getElementById("hintbar").hidden = true;
      try { localStorage.setItem("icarus.hint.dismissed", "1"); } catch (err) { /* private mode */ }
    });
    try { if (localStorage.getItem("icarus.hint.dismissed") === "1") document.getElementById("hintbar").hidden = true; } catch (err) { /* private mode */ }

    /** Replaces the design. The previous one stays one undo away unless this is the first load. */
    function loadDoc(next, first) {
      if (!first) pushHistory();
      doc = C.normalizeDoc(next);
      pageIndex = 0;
      selected = [];
      paint();
      fit();
    }

    var resizeTimer = null;
    root.addEventListener("resize", function () {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(function () { if (fitMode) fit(); else paintOverlay(); }, 60);
    });

    var drafted = null;
    var restored = false;
    try { drafted = localStorage.getItem(KEY); } catch (err) { drafted = null; }
    if (drafted) {
      try { restore(drafted); restored = true; } catch (err) { restored = false; }
    }
    if (restored) fit();
    else loadDoc(doc, true);
    if (location.hash.indexOf("#t=") === 0) {
      // The shared design replaces the draft, which stays one undo away.
      root.IcarusAppHash && root.IcarusAppHash(location.hash.slice(3), function (shared) { loadDoc(shared); }, setStatus);
    } else if (!restored) {
      openGallery();
    }

    return {
      setLang: function (next) { lang = next; paint(); },
      profile: function () { return C.profileFromDoc(doc); },
      loadProfile: function (data) {
        var result = C.validateProfile(data);
        if (!result.ok) return result;
        loadDoc(result.doc);
        setStatus(result.warnings.length ? result.warnings[0] : "");
        return result;
      },
      allPages: function () {
        return doc.pages.map(function (item, index) {
          return { background: item.background, html: C.renderPage(item, { vars: C.sampleVars(doc, index, doc.pages.length), logo: doc.brand.logo }) };
        });
      },
      undo: undo,
      redo: redo,
      fit: fit,
      setStatus: setStatus,
      doc: function () { return doc; }
    };
  }

  root.IcarusEditor = { mount: mount };
})(typeof globalThis !== "undefined" ? globalThis : this);
