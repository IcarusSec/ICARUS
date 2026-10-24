/**
 * Canva-style page editor. Pointer gestures, layers, and the property panel.
 * Part of ICARUS, MIT License.
 */
(function (root) {
  var C = root.IcarusCanvas;
  var KEY = "icarus.templateBuilder.draft.v3";

  function mount() {
    var doc = C.starterDoc("swiss");
    var pageIndex = 0;
    var selected = [];
    var past = [];
    var future = [];
    var zoom = 1;
    var panX = 40;
    var panY = 24;
    var lang = "en";
    var space = false;
    var clipboard = [];
    var gesture = null;
    var leftTab = "elements";

    var stage = document.getElementById("stage");
    var world = document.getElementById("world");
    var sheet = document.getElementById("sheet");
    var overlay = document.getElementById("overlay");
    var gallery = document.getElementById("gallery");
    var leftBody = document.getElementById("left-body");
    var inspector = document.getElementById("inspector");
    var nameInput = document.getElementById("template-name");

    function t(key) {
      var pack = root.IcarusI18n && root.IcarusI18n.t;
      return pack ? root.IcarusI18n.t(lang, key) : key;
    }

    function page() { return doc.pages[pageIndex]; }
    function vars() { return C.sampleVars(doc, pageIndex, doc.pages.length); }
    function byId(id) { return page().elements.filter(function (element) { return element.id === id; })[0]; }
    function selectedElements() { return selected.map(byId).filter(Boolean); }

    function snapshot() {
      return JSON.stringify({ doc: doc, pageIndex: pageIndex, selected: selected });
    }
    function pushHistory() {
      past.push(snapshot());
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
      try { localStorage.setItem(KEY, snapshot()); } catch (err) { /* quota */ }
    }

    function setStatus(text) {
      document.getElementById("status").textContent = text || "";
    }

    function paint() {
      var current = page();
      sheet.style.background = current.background;
      sheet.innerHTML = C.renderPage(current, { vars: vars(), logo: doc.brand.logo });
      if (!current.elements.length) {
        sheet.insertAdjacentHTML("beforeend", '<div class="page-empty"><strong>' + t("emptyTitle") + '</strong><span>' + t("emptyBody") + '</span><button type="button" class="text-btn" data-open-gallery="1">' + t("browseTemplates") + "</button></div>");
      }
      nameInput.value = doc.name;
      applyWorld();
      paintOverlay();
      paintLeft();
      paintInspector();
      saveDraft();
    }

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

    function hitTest(mm) {
      var list = page().elements.slice().sort(function (a, b) { return b.z - a.z; });
      for (var i = 0; i < list.length; i++) {
        var element = list[i];
        if (element.hidden || element.locked) continue;
        if (element.type === "line") {
          var dist = pointSegment(mm.x, mm.y, element.x1, element.y1, element.x2, element.y2);
          if (dist < 2.2) return element;
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
      var t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len));
      var x = x1 + t * dx;
      var y = y1 + t * dy;
      return Math.hypot(px - x, py - y);
    }

    function groupIds(element) {
      if (!element.groupId) return [element.id];
      return page().elements.filter(function (item) { return item.groupId === element.groupId; }).map(function (item) { return item.id; });
    }

    function selectOnly(ids) { selected = ids.slice(); paintOverlay(); paintLeft(); paintInspector(); }
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
      paintOverlay();
      paintLeft();
      paintInspector();
    }

    function paintOverlay() {
      var html = "";
      var elements = selectedElements();
      if (elements.length === 1) {
        var corners = C.corners(elements[0]);
        var pts = corners.map(function (point) { var s = toStage(point[0], point[1]); return s.x + "," + s.y; }).join(" ");
        html += '<svg style="position:absolute;inset:0;width:100%;height:100%;overflow:visible"><polygon points="' + pts + '" fill="none" stroke="#2f6bff" stroke-width="1.5"/></svg>';
        var handles = handlePoints(elements[0], corners);
        handles.forEach(function (handle) {
          var s = toStage(handle.x, handle.y);
          html += '<button type="button" class="' + (handle.name === "rotate" ? "rotate-handle" : "handle") + '" data-handle="' + handle.name + '" style="left:' + s.x + "px;top:" + s.y + 'px" aria-label="' + handle.name + '"></button>';
        });
      } else if (elements.length > 1) {
        var box = C.unionBounds(elements);
        var a = toStage(box.x, box.y);
        var b = toStage(box.x + box.w, box.y + box.h);
        html += '<div class="sel-box" style="left:' + a.x + "px;top:" + a.y + "px;width:" + (b.x - a.x) + "px;height:" + (b.y - a.y) + 'px"></div>';
        [["nw", box.x, box.y], ["ne", box.x + box.w, box.y], ["sw", box.x, box.y + box.h], ["se", box.x + box.w, box.y + box.h]].forEach(function (handle) {
          var s = toStage(handle[1], handle[2]);
          html += '<button type="button" class="handle" data-handle="' + handle[0] + '" data-group="1" style="left:' + s.x + "px;top:" + s.y + 'px"></button>';
        });
      }
      if (gesture && gesture.guides) {
        gesture.guides.forEach(function (guide) {
          if (guide.axis === "x") {
            var s = toStage(guide.at, 0);
            html += '<div class="guide v" style="left:' + s.x + 'px"></div>';
          } else {
            var sy = toStage(0, guide.at);
            html += '<div class="guide h" style="top:' + sy.y + 'px"></div>';
          }
        });
      }
      if (gesture && gesture.hints) {
        gesture.hints.forEach(function (hint) {
          var s = toStage(hint.x, hint.y);
          html += '<div class="gap-label" style="left:' + (s.x + 4) + "px;top:" + (s.y - 16) + 'px">' + hint.text + " mm</div>";
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
      var cx = (corners[0][0] + corners[1][0]) / 2;
      var topY = (corners[0][1] + corners[1][1]) / 2;
      var rad = (element.rotation || 0) * Math.PI / 180;
      var rot = [cx - Math.sin(rad) * -8, topY + Math.cos(rad) * -8];
      if (element.type === "line") {
        return [
          { name: "p1", x: element.x1, y: element.y1 },
          { name: "p2", x: element.x2, y: element.y2 }
        ];
      }
      var mids = [
        { name: "nw", x: corners[0][0], y: corners[0][1] },
        { name: "n", x: (corners[0][0] + corners[1][0]) / 2, y: (corners[0][1] + corners[1][1]) / 2 },
        { name: "ne", x: corners[1][0], y: corners[1][1] },
        { name: "e", x: (corners[1][0] + corners[2][0]) / 2, y: (corners[1][1] + corners[2][1]) / 2 },
        { name: "se", x: corners[2][0], y: corners[2][1] },
        { name: "s", x: (corners[2][0] + corners[3][0]) / 2, y: (corners[2][1] + corners[3][1]) / 2 },
        { name: "sw", x: corners[3][0], y: corners[3][1] },
        { name: "w", x: (corners[3][0] + corners[0][0]) / 2, y: (corners[3][1] + corners[0][1]) / 2 },
        { name: "rotate", x: rot[0], y: rot[1] }
      ];
      return mids;
    }

    function spacingHints(box, others) {
      var hints = [];
      others.forEach(function (other) {
        var right = other.x - (box.x + box.w);
        var left = box.x - (other.x + other.w);
        var below = other.y - (box.y + box.h);
        if (right > 0.4 && right < 50) hints.push({ x: box.x + box.w, y: box.y, text: right.toFixed(1) });
        else if (left > 0.4 && left < 50) hints.push({ x: other.x + other.w, y: box.y, text: left.toFixed(1) });
        else if (below > 0.4 && below < 50) hints.push({ x: box.x, y: box.y + box.h, text: below.toFixed(1) });
      });
      return hints.slice(0, 2);
    }

    function onSheetDown(event) {
      if (event.target.closest && event.target.closest("[data-open-gallery]")) {
        openGallery();
        return;
      }
      if (event.target.closest && event.target.closest("[contenteditable=true]")) return;
      if (space || event.button === 1) {
        gesture = { mode: "pan", x: event.clientX, y: event.clientY, panX: panX, panY: panY };
        stage.setPointerCapture(event.pointerId);
        return;
      }
      var mm = pagePoint(event);
      var hit = hitTest(mm);
      if (!hit) {
        if (!event.shiftKey) selected = [];
        gesture = { mode: "marquee", marquee: { x1: mm.x, y1: mm.y, x2: mm.x, y2: mm.y }, shift: event.shiftKey, base: selected.slice() };
        stage.setPointerCapture(event.pointerId);
        paintOverlay();
        paintInspector();
        return;
      }
      addToSelection(hit, event.shiftKey);
      var moving = selectedElements().filter(function (element) { return !element.locked; });
      gesture = {
        mode: "move",
        x: mm.x,
        y: mm.y,
        start: moving.map(function (element) { return JSON.parse(JSON.stringify(element)); }),
        ids: moving.map(function (element) { return element.id; })
      };
      pushHistory();
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
      pushHistory();
      gesture = {
        mode: handle.getAttribute("data-group") ? "group-resize" : handle.getAttribute("data-handle") === "rotate" ? "rotate" : "resize",
        handle: handle.getAttribute("data-handle"),
        x: mm.x,
        y: mm.y,
        start: JSON.parse(JSON.stringify(elements.length === 1 ? elements[0] : elements)),
        box: C.unionBounds(elements),
        ids: elements.map(function (element) { return element.id; })
      };
      overlay.setPointerCapture(event.pointerId);
    }

    function onMove(event) {
      if (!gesture) return;
      if (gesture.mode === "pan") {
        panX = gesture.panX + (event.clientX - gesture.x);
        panY = gesture.panY + (event.clientY - gesture.y);
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
          if (element.hidden) return false;
          return !(element.x + element.w < x1 || element.x > x2 || element.y + element.h < y1 || element.y > y2);
        }).map(function (element) { return element.id; });
        selected = gesture.shift ? gesture.base.concat(hits) : hits;
        paintOverlay();
        return;
      }
      if (gesture.mode === "move") {
        var dx = mm.x - gesture.x;
        var dy = mm.y - gesture.y;
        var first = gesture.start[0];
        var box = { x: first.x + dx, y: first.y + dy, w: first.w, h: first.h };
        if (gesture.start.length > 1) {
          var union = C.unionBounds(gesture.start);
          box = { x: union.x + dx, y: union.y + dy, w: union.w, h: union.h };
        }
        var others = page().elements.filter(function (element) { return gesture.ids.indexOf(element.id) === -1 && !element.hidden; });
        var snap = event.altKey ? { dx: 0, dy: 0, guides: [] } : C.snapDelta(box, others, 1.4);
        dx += snap.dx;
        dy += snap.dy;
        gesture.guides = snap.guides;
        gesture.start.forEach(function (start) {
          var element = byId(start.id);
          if (!element) return;
          if (element.type === "line") {
            element.x1 = Math.round((start.x1 + dx) * 10) / 10;
            element.y1 = Math.round((start.y1 + dy) * 10) / 10;
            element.x2 = Math.round((start.x2 + dx) * 10) / 10;
            element.y2 = Math.round((start.y2 + dy) * 10) / 10;
            element.x = Math.min(element.x1, element.x2) - 2;
            element.y = Math.min(element.y1, element.y2) - 2;
            element.w = Math.max(4, Math.abs(element.x2 - element.x1) + 4);
            element.h = Math.max(4, Math.abs(element.y2 - element.y1) + 4);
          } else {
            element.x = Math.round((start.x + dx) * 10) / 10;
            element.y = Math.round((start.y + dy) * 10) / 10;
          }
        });
        var live = byId(gesture.ids[0]);
        gesture.hints = live ? spacingHints(live, others) : [];
        sheet.innerHTML = C.renderPage(page(), { vars: vars(), logo: doc.brand.logo });
        paintOverlay();
        return;
      }
      if (gesture.mode === "resize" || gesture.mode === "rotate") {
        var element = byId(gesture.ids[0]);
        var start = gesture.start;
        if (gesture.handle === "p1" || gesture.handle === "p2") {
          if (gesture.handle === "p1") { element.x1 = Math.round(mm.x * 10) / 10; element.y1 = Math.round(mm.y * 10) / 10; }
          else { element.x2 = Math.round(mm.x * 10) / 10; element.y2 = Math.round(mm.y * 10) / 10; }
        } else if (gesture.mode === "rotate") {
          var cx = start.x + start.w / 2;
          var cy = start.y + start.h / 2;
          var angle = Math.atan2(mm.y - cy, mm.x - cx) * 180 / Math.PI + 90;
          if (event.shiftKey) angle = Math.round(angle / 15) * 15;
          element.rotation = Math.round(angle * 10) / 10;
        } else {
          var next = resizeRotated(start, gesture.handle, mm);
          element.x = Math.round(next.x * 10) / 10;
          element.y = Math.round(next.y * 10) / 10;
          element.w = Math.round(next.w * 10) / 10;
          element.h = Math.round(next.h * 10) / 10;
        }
        sheet.innerHTML = C.renderPage(page(), { vars: vars(), logo: doc.brand.logo });
        paintOverlay();
        return;
      }
      if (gesture.mode === "group-resize") {
        var box = gesture.box;
        var dx = mm.x - gesture.x;
        var dy = mm.y - gesture.y;
        var nx = box.x, ny = box.y, nw = box.w, nh = box.h;
        if (gesture.handle.indexOf("e") !== -1) nw = Math.max(8, box.w + dx);
        if (gesture.handle.indexOf("s") !== -1) nh = Math.max(8, box.h + dy);
        if (gesture.handle.indexOf("w") !== -1) { nx = box.x + dx; nw = Math.max(8, box.w - dx); }
        if (gesture.handle.indexOf("n") !== -1) { ny = box.y + dy; nh = Math.max(8, box.h - dy); }
        gesture.start.forEach(function (start) {
          var element = byId(start.id);
          if (!element || element.type === "line") return;
          var rx = (start.x - box.x) / box.w;
          var ry = (start.y - box.y) / box.h;
          element.x = Math.round((nx + rx * nw) * 10) / 10;
          element.y = Math.round((ny + ry * nh) * 10) / 10;
          element.w = Math.max(2, Math.round((start.w / box.w) * nw * 10) / 10);
          element.h = Math.max(2, Math.round((start.h / box.h) * nh * 10) / 10);
        });
        sheet.innerHTML = C.renderPage(page(), { vars: vars(), logo: doc.brand.logo });
        paintOverlay();
      }
    }

    function resizeRotated(start, handle, pointer) {
      var cx = start.x + start.w / 2;
      var cy = start.y + start.h / 2;
      var rad = -(start.rotation || 0) * Math.PI / 180;
      var dx = pointer.x - cx;
      var dy = pointer.y - cy;
      var lx = dx * Math.cos(rad) - dy * Math.sin(rad);
      var ly = dx * Math.sin(rad) + dy * Math.cos(rad);
      var left = -start.w / 2, right = start.w / 2, top = -start.h / 2, bottom = start.h / 2;
      if (handle.indexOf("e") !== -1) right = Math.max(left + 4, lx);
      if (handle.indexOf("w") !== -1) left = Math.min(right - 4, lx);
      if (handle.indexOf("s") !== -1) bottom = Math.max(top + 4, ly);
      if (handle.indexOf("n") !== -1) top = Math.min(bottom - 4, ly);
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
      if (gesture && (gesture.mode === "move" || gesture.mode === "resize" || gesture.mode === "rotate" || gesture.mode === "group-resize")) {
        page().elements = page().elements.map(C.normalizePage ? function (element) { return element; } : function (element) { return element; });
        page().elements.forEach(function (element) {
          if (element.type === "line") {
            var pad = 2;
            element.x = Math.round((Math.min(element.x1, element.x2) - pad) * 10) / 10;
            element.y = Math.round((Math.min(element.y1, element.y2) - pad) * 10) / 10;
            element.w = Math.round(Math.max(4, Math.abs(element.x2 - element.x1) + pad * 2) * 10) / 10;
            element.h = Math.round(Math.max(4, Math.abs(element.y2 - element.y1) + pad * 2) * 10) / 10;
          }
        });
        paint();
      } else if (gesture && gesture.mode === "marquee") {
        paintInspector();
        paintLeft();
        paintOverlay();
      }
      gesture = null;
    }

    stage.addEventListener("pointerdown", onSheetDown);
    overlay.addEventListener("pointerdown", onOverlayDown);
    stage.addEventListener("pointermove", onMove);
    overlay.addEventListener("pointermove", onMove);
    stage.addEventListener("pointerup", onUp);
    overlay.addEventListener("pointerup", onUp);

    sheet.addEventListener("dblclick", function (event) {
      var node = event.target.closest && event.target.closest(".el-text");
      if (!node) return;
      var element = byId(node.getAttribute("data-id"));
      if (!element || element.locked) return;
      node.contentEditable = "true";
      node.textContent = element.text;
      node.focus();
      var done = function () {
        node.removeEventListener("blur", done);
        pushHistory();
        element.text = node.innerText.replace(/\u00a0/g, " ").slice(0, 2000);
        node.contentEditable = "false";
        paint();
      };
      node.addEventListener("blur", done);
      node.addEventListener("keydown", function (keyEvent) {
        if (keyEvent.key === "Escape") { keyEvent.preventDefault(); node.blur(); }
        keyEvent.stopPropagation();
      });
    });

    stage.addEventListener("wheel", function (event) {
      if (event.ctrlKey || event.metaKey) {
        event.preventDefault();
        var before = pagePoint(event);
        var next = Math.max(0.25, Math.min(2.5, zoom * (event.deltaY < 0 ? 1.08 : 0.92)));
        zoom = next;
        applyWorld();
        var after = pagePoint(event);
        var rect = sheet.getBoundingClientRect();
        panX += (after.x - before.x) / C.PAGE_W * rect.width;
        panY += (after.y - before.y) / C.PAGE_H * rect.height;
        applyWorld();
        paintOverlay();
      } else {
        panX -= event.deltaX;
        panY -= event.deltaY;
        applyWorld();
        paintOverlay();
      }
    }, { passive: false });

    window.addEventListener("keydown", function (event) {
      if (event.code === "Space") space = true;
      var typing = event.target.closest && (event.target.closest("input, textarea, [contenteditable=true]"));
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "z") {
        event.preventDefault();
        if (event.shiftKey) redo(); else undo();
        return;
      }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "y") { event.preventDefault(); redo(); return; }
      if (typing) return;
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "c") { copySel(); return; }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "v") { event.preventDefault(); pasteSel(); return; }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "d") { event.preventDefault(); duplicateSel(); return; }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "g") { event.preventDefault(); event.shiftKey ? ungroup() : group(); return; }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "a") {
        event.preventDefault();
        selected = page().elements.filter(function (element) { return !element.hidden; }).map(function (element) { return element.id; });
        paintOverlay(); paintInspector(); paintLeft();
        return;
      }
      if (event.key === "Delete" || event.key === "Backspace") { event.preventDefault(); removeSel(); return; }
      if (event.key === "Escape") { selected = []; paintOverlay(); paintInspector(); paintLeft(); return; }
      var step = event.shiftKey ? 2 : 0.5;
      var move = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[event.key];
      if (move && selected.length) {
        event.preventDefault();
        pushHistory();
        selectedElements().forEach(function (element) { if (!element.locked) C.moveElement(element, move[0], move[1]); });
        paint();
      }
    });
    window.addEventListener("keyup", function (event) { if (event.code === "Space") space = false; });

    function copySel() { clipboard = selectedElements().map(function (element) { return JSON.parse(JSON.stringify(element)); }); }
    function pasteSel() {
      if (!clipboard.length) return;
      pushHistory();
      selected = clipboard.map(function (element) {
        var copy = JSON.parse(JSON.stringify(element));
        copy.id = C.uid("el");
        copy.groupId = "";
        C.moveElement(copy, 4, 4);
        page().elements.push(copy);
        return copy.id;
      });
      paint();
    }
    function duplicateSel() { copySel(); pasteSel(); }
    function removeSel() {
      if (!selected.length) return;
      pushHistory();
      page().elements = page().elements.filter(function (element) { return selected.indexOf(element.id) === -1 || element.locked; });
      selected = [];
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
      pushHistory();
      selectedElements().forEach(function (element) { element.groupId = ""; });
      paint();
    }
    function order(dir) {
      pushHistory();
      var elements = page().elements;
      var ids = selected.slice();
      if (dir === "front") ids.forEach(function (id) { var element = byId(id); if (element) element.z = 400; });
      if (dir === "back") ids.forEach(function (id) { var element = byId(id); if (element) element.z = 0; });
      if (dir === "forward" || dir === "backward") {
        elements.sort(function (a, b) { return a.z - b.z; });
        elements.forEach(function (element, index) { element.z = index + 1; });
        ids.forEach(function (id) {
          var index = elements.findIndex(function (element) { return element.id === id; });
          var swap = dir === "forward" ? index + 1 : index - 1;
          if (swap < 0 || swap >= elements.length) return;
          var z = elements[index].z;
          elements[index].z = elements[swap].z;
          elements[swap].z = z;
        });
      }
      paint();
    }
    function align(mode) {
      var elements = selectedElements();
      if (elements.length < 2) return;
      pushHistory();
      C.alignElements(elements, mode);
      paint();
    }
    function distribute(axis) {
      var elements = selectedElements();
      if (elements.length < 3) return;
      pushHistory();
      C.distribute(elements, axis);
      paint();
    }

    function addElement(type, partial) {
      pushHistory();
      var count = page().elements.length;
      var element = C.newElement(type, Object.assign({
        x: 20 + (count % 5) * 6,
        y: 24 + (count % 5) * 6,
        z: count + 1
      }, partial || {}));
      page().elements.push(element);
      selected = [element.id];
      paint();
      stage.focus();
    }

    function paintLeft() {
      document.querySelectorAll("[data-left]").forEach(function (button) {
        button.setAttribute("aria-selected", button.getAttribute("data-left") === leftTab ? "true" : "false");
      });
      if (leftTab === "elements") leftBody.innerHTML = elementsHtml();
      else if (leftTab === "pages") leftBody.innerHTML = pagesHtml();
      else if (leftTab === "layers") leftBody.innerHTML = layersHtml();
      else leftBody.innerHTML = brandHtml();
    }

    function elementsHtml() {
      function tile(action, glyph, label, extra) {
        return '<button type="button" class="tile" draggable="true" data-add="' + action + '" ' + (extra || "") + '><span class="glyph">' + glyph + '</span>' + label + "</button>";
      }
      return '<p class="group-label">' + t("textStyles") + '</p><div class="tiles">' +
        tile("text:heading", "H", t("heading")) + tile("text:subheading", "S", t("subheading")) + tile("text:body", "¶", t("body")) +
        '</div><p class="group-label">' + t("shapes") + '</p><div class="tiles">' +
        tile("shape:rect", "▭", t("rectangle")) + tile("shape:ellipse", "◯", t("ellipse")) + tile("shape:triangle", "△", t("triangle")) +
        tile("shape:polygon", "⬡", t("polygon")) + tile("shape:parallelogram", "▱", t("parallelogram")) +
        tile("line", "╱", t("line")) + tile("arrow", "→", t("arrow")) +
        '</div><p class="group-label">' + t("images") + '</p><div class="tiles">' +
        tile("upload", "▣", t("uploadImage")) + tile("frame", "▱", t("photoFrame")) + tile("logo", "⌂", t("logoSlot")) +
        '</div><p class="group-label">' + t("dataBlocks") + '</p><div class="tiles">' +
        tile("chart", "▮", t("chart")) + tile("table", "▤", t("findingsTable")) +
        '</div><input id="image-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden>';
    }

    function pagesHtml() {
      var rows = doc.pages.map(function (item, index) {
        return '<div class="page-row' + (index === pageIndex ? " active" : "") + '"><button type="button" class="grow" data-page="' + index + '">' + (index + 1) + ". " + item.name + '</button><button type="button" data-required="' + index + '" aria-pressed="' + (item.required ? "true" : "false") + '" title="' + t("requiredPage") + '">' + (item.required ? "●" : "○") + "</button></div>";
      }).join("");
      return rows + '<div class="arrange"><button type="button" class="text-btn" data-page-action="add">' + t("addPage") + '</button><button type="button" class="text-btn" data-page-action="duplicate">' + t("duplicatePage") + '</button><button type="button" class="text-btn" data-page-action="delete">' + t("deletePage") + "</button></div>";
    }

    function layersHtml() {
      var rows = page().elements.slice().sort(function (a, b) { return b.z - a.z; }).map(function (element) {
        var on = selected.indexOf(element.id) !== -1;
        return '<div class="layer-row' + (on ? " active" : "") + '"><button type="button" class="grow" data-layer="' + element.id + '">' + (element.name || element.type) + '</button><button type="button" data-hide="' + element.id + '" aria-label="' + t("hide") + '">' + (element.hidden ? "◌" : "◉") + '</button><button type="button" data-lock="' + element.id + '" aria-label="' + t("lock") + '">' + (element.locked ? "▣" : "□") + "</button></div>";
      }).join("");
      return rows || '<p class="help">' + t("layersEmpty") + "</p>";
    }

    function brandHtml() {
      var swatches = doc.brand.colors.map(function (color, index) {
        return '<button type="button" class="swatch" data-swatch="' + index + '" style="background:' + color + '" aria-label="' + color + '"></button>';
      }).join("");
      var fonts = C.FONTS.map(function (font) {
        return '<button type="button" class="font-sample" data-font="' + font.id + '" style="font-family:' + C.fontCss(font.id) + '">' + font.label + "</button>";
      }).join("");
      return '<p class="help">' + t("brandHelp") + '</p><label class="text-btn file-btn">' + t("logo") + '<input id="logo-file" type="file" accept="image/png,image/jpeg,image/gif,image/webp"></label>' +
        '<p class="group-label">' + t("palette") + '</p><div class="swatches">' + swatches + '</div>' +
        '<p class="group-label">' + t("fonts") + '</p>' + fonts +
        field("documentTitle", t("reportTitle"), doc.fields.documentTitle) +
        field("client", t("client"), doc.fields.client) +
        field("project", t("project"), doc.fields.project) +
        field("author", t("tester"), doc.fields.author) +
        field("date", t("date"), doc.fields.date) +
        field("version", t("version"), doc.fields.version);
    }
    function field(key, label, value) {
      return '<label class="field">' + label + '<input data-field="' + key + '" value="' + String(value).replace(/"/g, "&quot;") + '"></label>';
    }

    function paintInspector() {
      var elements = selectedElements();
      if (!elements.length) {
        var current = page();
        inspector.innerHTML = '<p class="help">' + t("noSelection") + '</p>' +
          fieldPage("name", t("pageName"), current.name) +
          '<label class="field">' + t("role") + '<select data-page-role><option value="cover"' + (current.role === "cover" ? " selected" : "") + '>' + t("cover") + '</option><option value="interior"' + (current.role === "interior" ? " selected" : "") + '>' + t("interior") + "</option></select></label>" +
          '<label class="field">' + t("background") + '<input data-page-bg type="color" value="' + current.background + '"></label>' +
          '<label class="field"><input data-page-required type="checkbox"' + (current.required ? " checked" : "") + "> " + t("requiredPage") + "</label>";
        return;
      }
      var arrange = '<div class="arrange">' +
        btn("align", "left", t("alignLeft")) + btn("align", "center", t("alignCenter")) + btn("align", "right", t("alignRight")) +
        btn("align", "top", t("alignTop")) + btn("align", "middle", t("alignMiddle")) + btn("align", "bottom", t("alignBottom")) +
        btn("dist", "h", t("distributeH")) + btn("dist", "v", t("distributeV")) +
        btn("order", "forward", t("forward")) + btn("order", "backward", t("backward")) +
        btn("order", "front", t("front")) + btn("order", "back", t("back")) +
        btn("edit", "group", t("group")) + btn("edit", "ungroup", t("ungroup")) +
        btn("edit", "duplicate", t("duplicate")) + btn("edit", "delete", t("del")) +
        "</div>";
      if (elements.length > 1) {
        inspector.innerHTML = "<p><strong>" + elements.length + " " + t("multi") + "</strong></p>" + arrange;
        return;
      }
      var element = elements[0];
      var html = "<p><strong>" + (element.name || element.type) + "</strong></p>" + arrange;
      html += numField("x", t("fromLeft"), element.x) + numField("y", t("fromTop"), element.y) + numField("w", t("width"), element.w) + numField("h", t("height"), element.h) + numField("rotation", t("rotation"), element.rotation || 0);
      if (element.type === "text") {
        html += '<label class="field">' + t("text") + '<textarea data-prop="text" rows="4">' + escapeText(element.text) + "</textarea></label>";
        html += '<div class="arrange"><button type="button" class="text-btn" data-insert-field>' + t("insertField") + "</button></div>";
        html += '<label class="field">' + t("font") + "<select data-prop=\"font\">" + C.FONTS.map(function (font) {
          return '<option value="' + font.id + '"' + (element.font === font.id ? " selected" : "") + ">" + font.label + "</option>";
        }).join("") + "</select></label>";
        html += numField("size", t("size"), element.size);
        html += '<label class="field">' + t("color") + '<input data-prop="color" type="color" value="' + element.color + '"></label>';
        html += '<div class="seg">' + seg("align", "left", element.align) + seg("align", "center", element.align) + seg("align", "right", element.align) + "</div>";
        html += '<div class="seg">' + seg("weight", "400", String(element.weight)) + seg("weight", "700", String(element.weight)) + "</div>";
        html += numField("lineHeight", t("lineSpacing"), element.lineHeight);
        html += '<label class="field"><input data-prop="blend" type="checkbox"' + (element.blend === "difference" ? " checked" : "") + "> " + t("invert") + '</label><p class="help">' + t("invertHelp") + "</p>";
      }
      if (element.type === "shape") {
        html += '<label class="field">' + t("fill") + '<input data-prop="fill" type="color" value="' + element.fill + '"></label>';
        html += '<label class="field">' + t("border") + '<input data-prop="stroke" type="color" value="' + (element.stroke || "#1A1A1A") + '"></label>';
        html += numField("strokeWidth", t("borderWidth"), element.strokeWidth || 0);
      }
      if (element.type === "image") {
        html += '<label class="field"><input data-prop="grayscale" type="checkbox"' + (element.grayscale ? " checked" : "") + "> " + t("grayscale") + "</label>";
        html += '<label class="field">' + t("tint") + '<input data-prop="tint" type="color" value="' + (element.tint || "#A3205A") + '"></label>';
        html += numField("tintOpacity", t("tintStrength"), element.tintOpacity);
        html += '<label class="field">' + t("frame") + '<select data-prop="clip">' + ["rect", "ellipse", "parallelogram", "triangle"].map(function (clip) {
          return '<option value="' + clip + '"' + (element.clip === clip ? " selected" : "") + ">" + t(clip) + "</option>";
        }).join("") + "</select></label>";
        html += '<button type="button" class="text-btn" data-replace-image>' + t("uploadImage") + "</button>";
      }
      if (element.type === "line") {
        html += '<label class="field">' + t("border") + '<input data-prop="stroke" type="color" value="' + element.stroke + '"></label>';
        html += numField("strokeWidth", t("borderWidth"), element.strokeWidth);
        html += '<label class="field"><input data-prop="arrow" type="checkbox"' + (element.arrow ? " checked" : "") + "> " + t("arrow") + "</label>";
      }
      html += '<label class="field"><input data-prop="locked" type="checkbox"' + (element.locked ? " checked" : "") + "> " + t("lock") + "</label>";
      inspector.innerHTML = html;
    }
    function btn(group, value, label) {
      return '<button type="button" class="text-btn" data-act="' + group + '" data-value="' + value + '">' + label + "</button>";
    }
    function seg(prop, value, current) {
      return '<button type="button" class="text-btn" data-prop-set="' + prop + '" data-value="' + value + '"' + (String(current) === String(value) ? ' aria-pressed="true"' : "") + ">" + t(value === "400" ? "regular" : value === "700" ? "bold" : value) + "</button>";
    }
    function numField(prop, label, value) {
      return '<label class="field">' + label + '<input data-prop="' + prop + '" type="number" step="0.5" value="' + value + '"></label>';
    }
    function fieldPage(key, label, value) {
      return '<label class="field">' + label + '<input data-page-name value="' + String(value).replace(/"/g, "&quot;") + '"></label>';
    }
    function escapeText(text) {
      return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;");
    }

    leftBody.addEventListener("click", function (event) {
      var add = event.target.closest && event.target.closest("[data-add]");
      if (add) {
        var kind = add.getAttribute("data-add");
        if (kind === "upload") { document.getElementById("image-file").click(); return; }
        if (kind.indexOf("text:") === 0) addElement("text", { preset: kind.split(":")[1] });
        else if (kind.indexOf("shape:") === 0) addElement("shape", { shape: kind.split(":")[1], fill: doc.brand.colors[0] });
        else if (kind === "line") addElement("line", {});
        else if (kind === "arrow") addElement("line", { arrow: true, x1: 30, y1: 40, x2: 90, y2: 40 });
        else if (kind === "frame") addElement("image", { clip: "parallelogram", src: "", w: 60, h: 80, name: "Photo frame" });
        else if (kind === "logo") addElement("image", { slot: "clientLogo", w: 32, h: 14 });
        else if (kind === "chart") addElement("chart", {});
        else if (kind === "table") addElement("table", {});
        return;
      }
      var pageBtn = event.target.closest && event.target.closest("[data-page]");
      if (pageBtn) { pageIndex = Number(pageBtn.getAttribute("data-page")); selected = []; paint(); return; }
      var req = event.target.closest && event.target.closest("[data-required]");
      if (req) {
        pushHistory();
        doc.pages[Number(req.getAttribute("data-required"))].required = !doc.pages[Number(req.getAttribute("data-required"))].required;
        paint();
        return;
      }
      var action = event.target.closest && event.target.closest("[data-page-action]");
      if (action) {
        var name = action.getAttribute("data-page-action");
        pushHistory();
        if (name === "add") { doc.pages.push(C.blankPage(t("pageName") + " " + (doc.pages.length + 1))); pageIndex = doc.pages.length - 1; }
        if (name === "duplicate") {
          var copy = JSON.parse(JSON.stringify(page()));
          copy.id = C.uid("page");
          copy.required = false;
          copy.name = copy.name + " 2";
          doc.pages.splice(pageIndex + 1, 0, copy);
          pageIndex += 1;
        }
        if (name === "delete" && !page().required && doc.pages.length > 1) {
          doc.pages.splice(pageIndex, 1);
          pageIndex = Math.max(0, pageIndex - 1);
        }
        selected = [];
        paint();
        return;
      }
      var layer = event.target.closest && event.target.closest("[data-layer]");
      if (layer) { selected = [layer.getAttribute("data-layer")]; paintOverlay(); paintInspector(); paintLeft(); return; }
      var hide = event.target.closest && event.target.closest("[data-hide]");
      if (hide) { pushHistory(); byId(hide.getAttribute("data-hide")).hidden = !byId(hide.getAttribute("data-hide")).hidden; paint(); return; }
      var lock = event.target.closest && event.target.closest("[data-lock]");
      if (lock) { pushHistory(); var item = byId(lock.getAttribute("data-lock")); item.locked = !item.locked; paint(); return; }
      var font = event.target.closest && event.target.closest("[data-font]");
      if (font && selectedElements()[0] && selectedElements()[0].type === "text") {
        pushHistory();
        selectedElements()[0].font = font.getAttribute("data-font");
        paint();
      }
      var swatch = event.target.closest && event.target.closest("[data-swatch]");
      if (swatch) {
        var input = document.createElement("input");
        input.type = "color";
        input.value = doc.brand.colors[Number(swatch.getAttribute("data-swatch"))];
        input.addEventListener("input", function () {
          doc.brand.colors[Number(swatch.getAttribute("data-swatch"))] = input.value.toUpperCase();
          swatch.style.background = input.value;
        });
        input.click();
      }
    });
    leftBody.addEventListener("change", function (event) {
      if (event.target.id === "image-file") readImage(event.target.files[0], function (url) { addElement("image", { src: url, w: 70, h: 50 }); });
      if (event.target.id === "logo-file") readImage(event.target.files[0], function (url) {
        pushHistory();
        doc.brand.logo = url;
        addElement("image", { src: url, slot: "companyLogo", w: 28, h: 14, name: "Logo" });
      });
      var fieldInput = event.target.closest && event.target.closest("[data-field]");
      if (fieldInput) {
        pushHistory();
        doc.fields[fieldInput.getAttribute("data-field")] = fieldInput.value;
        paint();
      }
    });

    inspector.addEventListener("click", function (event) {
      var act = event.target.closest && event.target.closest("[data-act]");
      if (act) {
        var groupName = act.getAttribute("data-act");
        var value = act.getAttribute("data-value");
        if (groupName === "align") align(value);
        if (groupName === "dist") distribute(value);
        if (groupName === "order") order(value);
        if (groupName === "edit" && value === "group") group();
        if (groupName === "edit" && value === "ungroup") ungroup();
        if (groupName === "edit" && value === "duplicate") duplicateSel();
        if (groupName === "edit" && value === "delete") removeSel();
        return;
      }
      var set = event.target.closest && event.target.closest("[data-prop-set]");
      if (set) {
        var element = selectedElements()[0];
        if (!element) return;
        pushHistory();
        element[set.getAttribute("data-prop-set")] = set.getAttribute("data-prop-set") === "weight" ? Number(set.getAttribute("data-value")) : set.getAttribute("data-value");
        paint();
      }
      if (event.target.closest && event.target.closest("[data-insert-field]")) openVarMenu(event.target);
      if (event.target.closest && event.target.closest("[data-replace-image]")) {
        var picker = document.createElement("input");
        picker.type = "file";
        picker.accept = "image/png,image/jpeg,image/gif,image/webp";
        picker.addEventListener("change", function () {
          readImage(picker.files[0], function (url) {
            pushHistory();
            selectedElements()[0].src = url;
            paint();
          });
        });
        picker.click();
      }
    });
    inspector.addEventListener("input", function (event) {
      var prop = event.target.getAttribute && event.target.getAttribute("data-prop");
      var element = selectedElements()[0];
      if (event.target.hasAttribute("data-page-name")) { page().name = event.target.value.slice(0, 80); paintLeft(); return; }
      if (event.target.hasAttribute("data-page-bg")) { page().background = event.target.value.toUpperCase(); sheet.style.background = page().background; return; }
      if (event.target.hasAttribute("data-page-role")) { page().role = event.target.value; return; }
      if (event.target.hasAttribute("data-page-required")) { page().required = event.target.checked; return; }
      if (!element || !prop) return;
      if (!event.target.dataset.history) { pushHistory(); event.target.dataset.history = "1"; }
      if (event.target.type === "checkbox") {
        if (prop === "blend") {
          element.blend = event.target.checked ? "difference" : "normal";
          if (event.target.checked) element.color = "#FFFFFF";
        } else if (prop === "grayscale" || prop === "arrow" || prop === "locked") element[prop] = event.target.checked;
      } else if (event.target.type === "number") element[prop] = Number(event.target.value);
      else if (prop === "text") element.text = event.target.value;
      else if (prop === "clip") { element.clip = event.target.value; element.points = element.clip === "parallelogram" ? [[0.2, 0], [1, 0], [0.8, 1], [0, 1]] : element.points; }
      else element[prop] = event.target.type === "color" ? event.target.value.toUpperCase() : event.target.value;
      sheet.innerHTML = C.renderPage(page(), { vars: vars(), logo: doc.brand.logo });
      paintOverlay();
    });
    inspector.addEventListener("change", function (event) {
      if (event.target.dataset) delete event.target.dataset.history;
      if (event.target.hasAttribute("data-page-role")) page().role = event.target.value;
      if (event.target.hasAttribute("data-page-required")) { pushHistory(); page().required = event.target.checked; paintLeft(); }
      saveDraft();
    });

    function openVarMenu(anchor) {
      var menu = document.createElement("div");
      menu.className = "menu-pop";
      menu.innerHTML = C.VARIABLES.map(function (variable) {
        return '<button type="button" data-var="' + variable.id + '">' + t("var_" + variable.id) + "</button>";
      }).join("");
      document.body.appendChild(menu);
      var rect = anchor.getBoundingClientRect();
      menu.style.left = rect.left + "px";
      menu.style.top = (rect.bottom + 4) + "px";
      menu.addEventListener("click", function (event) {
        var id = event.target.getAttribute("data-var");
        if (!id) return;
        var element = selectedElements()[0];
        if (element && element.type === "text") {
          pushHistory();
          element.text = (element.text || "") + "{{" + id + "}}";
          paint();
        }
        menu.remove();
      });
      setTimeout(function () {
        document.addEventListener("pointerdown", function close(ev) {
          if (!menu.contains(ev.target)) { menu.remove(); document.removeEventListener("pointerdown", close); }
        });
      }, 0);
    }

    function readImage(file, done) {
      if (!file) return;
      if (!/^image\/(png|jpeg|gif|webp)$/.test(file.type)) { setStatus(t("imageType")); return; }
      if (file.size > 600000) { setStatus(t("imageSize")); return; }
      var reader = new FileReader();
      reader.onload = function () { done(String(reader.result)); };
      reader.readAsDataURL(file);
    }

    function openGallery() {
      gallery.hidden = false;
      gallery.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center"><h2>' + t("galleryTitle") + '</h2><button type="button" class="text-btn" data-close-gallery="1">Close</button></div><p class="help">' + t("galleryLead") + '</p><div class="cards">' +
        C.STARTERS.map(function (starter) {
          var sample = C.starterDoc(starter.id);
          var cover = sample.pages[0];
          var html = C.renderPage(cover, { vars: C.sampleVars(sample, 0, sample.pages.length) });
          return '<button type="button" class="card" data-starter="' + starter.id + '"><div class="thumb"><div class="thumb-scale" style="transform:scale(0.22)"><div class="sheet" style="background:' + cover.background + '">' + html + '</div></div></div><strong>' + t("starter_" + starter.id) + "</strong><span>" + t("starterBlurb_" + starter.id) + "</span></button>";
        }).join("") + "</div>";
    }
    gallery.addEventListener("click", function (event) {
      if (event.target.closest && event.target.closest("[data-close-gallery]")) { gallery.hidden = true; return; }
      var card = event.target.closest && event.target.closest("[data-starter]");
      if (!card) return;
      pushHistory();
      var next = C.starterDoc(card.getAttribute("data-starter"));
      next.id = doc.id;
      next.name = t("starter_" + card.getAttribute("data-starter"));
      doc = next;
      pageIndex = 0;
      selected = [];
      gallery.hidden = true;
      paint();
      fit();
    });

    function fit() {
      var rect = stage.getBoundingClientRect();
      var pxW = 210 * 96 / 25.4;
      var pxH = 297 * 96 / 25.4;
      zoom = Math.max(0.25, Math.min(1.4, Math.min((rect.width - 48) / pxW, (rect.height - 48) / pxH)));
      panX = Math.max(16, (rect.width - pxW * zoom) / 2);
      panY = 20;
      applyWorld();
      paintOverlay();
    }

    document.getElementById("undo").addEventListener("click", undo);
    document.getElementById("redo").addEventListener("click", redo);
    document.getElementById("zoom-in").addEventListener("click", function () { zoom = Math.min(2.5, zoom * 1.1); applyWorld(); paintOverlay(); });
    document.getElementById("zoom-out").addEventListener("click", function () { zoom = Math.max(0.25, zoom / 1.1); applyWorld(); paintOverlay(); });
    document.getElementById("zoom-fit").addEventListener("click", fit);
    document.getElementById("templates").addEventListener("click", openGallery);
    document.querySelectorAll("[data-left]").forEach(function (button) {
      button.addEventListener("click", function () { leftTab = button.getAttribute("data-left"); paintLeft(); });
    });
    nameInput.addEventListener("change", function () { doc.name = nameInput.value.slice(0, 120) || "Untitled report"; saveDraft(); });
    document.getElementById("hint-dismiss").addEventListener("click", function () {
      document.getElementById("hintbar").hidden = true;
      try { localStorage.setItem("icarus.hint.dismissed", "1"); } catch (err) { /* ignore */ }
    });
    if (localStorage.getItem("icarus.hint.dismissed") === "1") document.getElementById("hintbar").hidden = true;

    function loadDoc(next, quiet) {
      doc = C.normalizeDoc(next);
      pageIndex = 0;
      selected = [];
      if (!quiet) past = []; future = [];
      undoButtons();
      paint();
      fit();
    }

    window.addEventListener("resize", function () { paintOverlay(); });

    var drafted = null;
    try { drafted = localStorage.getItem(KEY); } catch (err) { drafted = null; }
    if (location.hash.indexOf("#t=") === 0) {
      gallery.hidden = true;
      paint();
      root.IcarusAppHash && root.IcarusAppHash(location.hash.slice(3), loadDoc, setStatus);
    } else if (drafted) {
      restore(drafted);
      past = []; future = [];
      undoButtons();
      fit();
    } else {
      loadDoc(doc, true);
      openGallery();
    }

    return {
      setLang: function (next) { lang = next; paint(); },
      profile: function () { doc.name = nameInput.value || doc.name; return C.profileFromDoc(doc); },
      loadProfile: function (data) {
        var result = C.validateProfile(data);
        if (!result.ok) return result;
        loadDoc(result.doc);
        if (result.warnings.length) setStatus(result.warnings[0]);
        return result;
      },
      undo: undo,
      redo: redo,
      fit: fit,
      doc: function () { return doc; }
    };
  }

  root.IcarusEditor = { mount: mount };
})(typeof globalThis !== "undefined" ? globalThis : this);
