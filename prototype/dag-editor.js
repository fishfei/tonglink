/* DAG 画布：节点拖拽 / 端口连线 / 选择删除 / 平移缩放 / 网格吸附（零依赖） */
(function () {
  window.Proto = window.Proto || {};
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var NODE_W = 220, PORT_Y = 30, GRID = 20;

  Proto.createDagEditor = function (viewport, opts) {
    opts = opts || {};
    var workspace = document.createElement('div');
    workspace.className = 'dag-workspace';
    var edgesSvg = document.createElementNS(SVG_NS, 'svg');
    edgesSvg.setAttribute('class', 'dag-edges');
    var nodeLayer = document.createElement('div');
    nodeLayer.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;';
    workspace.appendChild(edgesSvg);
    workspace.appendChild(nodeLayer);
    viewport.appendChild(workspace);

    var scale = 1, tx = 24, ty = 12;
    var flow = null, selected = null, selectedEdge = null;
    var gesture = null, timers = [];

    function applyTransform() {
      workspace.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
      if (opts.onZoom) opts.onZoom(Math.round(scale * 100));
    }
    function toWorkspace(cx, cy) {
      var r = viewport.getBoundingClientRect();
      return { x: (cx - r.left - tx) / scale, y: (cy - r.top - ty) / scale };
    }
    function snap(v) { return Math.max(0, Math.round(v / GRID) * GRID); }
    function nodeById(id) {
      if (!flow) return null;
      for (var i = 0; i < flow.nodes.length; i++) if (flow.nodes[i].id === id) return flow.nodes[i];
      return null;
    }
    function portPos(node, which) {
      return { x: which === 'in' ? node.x : node.x + NODE_W, y: node.y + PORT_Y };
    }
    function esc(s) {
      return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
      });
    }
    function curve(a, b) {
      var dx = Math.max(50, Math.abs(b.x - a.x) / 2);
      return 'M ' + a.x.toFixed(1) + ',' + a.y.toFixed(1) + ' C ' +
        (a.x + dx).toFixed(1) + ',' + a.y.toFixed(1) + ' ' +
        (b.x - dx).toFixed(1) + ',' + b.y.toFixed(1) + ' ' +
        b.x.toFixed(1) + ',' + b.y.toFixed(1);
    }

    /* ---------- 渲染 ---------- */
    function nodeHTML(n) {
      var theme = Proto.NODE_THEME[n.type] || Proto.NODE_THEME.source;
      var kindName = { trigger: '触发器', source: '数据源', code: '代码节点', logic: '逻辑节点', output: '输出节点' }[n.type] || '节点';
      var bodyTag = n.subtype ? '<span class="mini-tag">' + esc(n.subtype.toUpperCase()) + '</span>' : '';
      var hint = '<span style="color:#94A3B8">双击配置属性</span>';
      return '<div class="dag-node' + (selected && selected.id === n.id ? ' selected' : '') + '" data-node-id="' + esc(n.id) + '"' +
        ' style="left:' + n.x + 'px;top:' + n.y + 'px">' +
        '<div class="port in" data-port="in" data-node-id="' + esc(n.id) + '" title="输入端口"></div>' +
        '<div class="node-header">' +
        '<span class="node-kind" style="background:' + theme.soft + ';color:' + theme.color + '">' + Proto.icon(theme.icon, 17) + '</span>' +
        '<div class="node-titles"><div class="node-title">' + esc(n.title) + '</div><div class="node-sub">' + esc(n.subtitle || kindName) + '</div></div>' +
        '</div><div class="node-body">' + bodyTag + hint + '</div>' +
        '<div class="port out" data-port="out" data-node-id="' + esc(n.id) + '" title="输出端口：拖动到下一个节点的输入端口"></div>' +
        '</div>';
    }

    function renderNodes(animate) {
      nodeLayer.innerHTML = flow ? flow.nodes.map(nodeHTML).join('') : '';
      if (animate) {
        var els = nodeLayer.querySelectorAll('.dag-node');
        els.forEach(function (el, i) {
          el.classList.add('flow-stage-in');
          timers.push(setTimeout(function () { el.classList.add('shown'); }, 90 + i * 130));
        });
      }
    }

    function drawEdges(temp) {
      if (!flow) return;
      var defs = '<defs><marker id="dagArrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">' +
        '<path d="M0,0 L8,4 L0,8 z" fill="#94A3B8"/></marker></defs>';
      var paths = flow.connections.map(function (c) {
        var a = nodeById(c.from.nodeId), b = nodeById(c.to.nodeId);
        if (!a || !b) return '';
        var d = curve(portPos(a, 'out'), portPos(b, 'in'));
        return '<path class="dag-edge' + (selectedEdge === c.id ? ' selected' : '') + '" data-edge-id="' + esc(c.id) + '" d="' + d + '" marker-end="url(#dagArrow)"/>';
      }).join('');
      var tmp = temp ? '<path class="dag-edge-temp" d="' + curve(temp.a, temp.b) + '"/>' : '';
      edgesSvg.innerHTML = defs + paths + tmp;
      nodeLayer.querySelectorAll('.port').forEach(function (p) {
        var id = p.getAttribute('data-node-id'), which = p.getAttribute('data-port');
        var connected = flow.connections.some(function (c) {
          return which === 'out' ? c.from.nodeId === id : c.to.nodeId === id;
        });
        p.classList.toggle('port-connected', connected);
      });
    }
    function redrawEdges() { drawEdges(null); }

    function selectNode(n) {
      selected = n; selectedEdge = null;
      nodeLayer.querySelectorAll('.dag-node').forEach(function (el) {
        el.classList.toggle('selected', !!n && el.getAttribute('data-node-id') === n.id);
      });
      drawEdges(null);
      if (opts.onSelect) opts.onSelect(n);
    }
    function selectEdge(id) {
      selectedEdge = id; selected = null;
      nodeLayer.querySelectorAll('.dag-node').forEach(function (el) { el.classList.remove('selected'); });
      drawEdges(null);
      if (opts.onSelect) opts.onSelect(null);
    }

    function positionNode(n) {
      var el = nodeLayer.querySelector('[data-node-id="' + n.id + '"]');
      if (el) { el.style.left = n.x + 'px'; el.style.top = n.y + 'px'; }
    }

    /* ---------- 手势 ---------- */
    function onMouseDown(e) {
      var port = e.target.closest('.port');
      if (port && port.getAttribute('data-port') === 'out') {
        var n = nodeById(port.getAttribute('data-node-id'));
        if (n) {
          gesture = { kind: 'connect', fromId: n.id };
          drawEdges({ a: portPos(n, 'out'), b: toWorkspace(e.clientX, e.clientY) });
        }
        e.preventDefault(); e.stopPropagation();
        return;
      }
      var nodeEl = e.target.closest('.dag-node');
      if (nodeEl) {
        var node = nodeById(nodeEl.getAttribute('data-node-id'));
        selectNode(node);
        if (e.target.closest('.node-header')) {
          gesture = { kind: 'node', id: node.id, mx: e.clientX, my: e.clientY, nx: node.x, ny: node.y };
          e.preventDefault();
        }
        e.stopPropagation();
        return;
      }
      var edgeEl = e.target.closest('.dag-edge');
      if (edgeEl) { selectEdge(edgeEl.getAttribute('data-edge-id')); e.stopPropagation(); return; }
      if (e.button === 1 || e.button === 0) {
        gesture = { kind: 'pan', mx: e.clientX, my: e.clientY, tx: tx, ty: ty };
        viewport.classList.add('panning');
        e.preventDefault();
      }
    }

    function onMouseMove(e) {
      if (!gesture) return;
      if (gesture.kind === 'pan') {
        tx = gesture.tx + (e.clientX - gesture.mx);
        ty = gesture.ty + (e.clientY - gesture.my);
        applyTransform();
      } else if (gesture.kind === 'node') {
        var n = nodeById(gesture.id);
        if (n) {
          n.x = snap(gesture.nx + (e.clientX - gesture.mx) / scale);
          n.y = snap(gesture.ny + (e.clientY - gesture.my) / scale);
          positionNode(n); redrawEdges();
          if (opts.onChange) opts.onChange();
        }
      } else if (gesture.kind === 'connect') {
        var from = nodeById(gesture.fromId);
        if (from) drawEdges({ a: portPos(from, 'out'), b: toWorkspace(e.clientX, e.clientY) });
      }
    }

    function onMouseUp(e) {
      if (!gesture) return;
      if (gesture.kind === 'connect') {
        var target = document.elementFromPoint(e.clientX, e.clientY);
        var inPort = target && target.closest ? target.closest('.port.in') : null;
        if (inPort && inPort.getAttribute('data-node-id') !== gesture.fromId) {
          var toId = inPort.getAttribute('data-node-id');
          var exists = flow.connections.some(function (c) { return c.from.nodeId === gesture.fromId && c.to.nodeId === toId; });
          if (!exists) {
            flow.connections.push({ id: 'e-' + Date.now(), from: { nodeId: gesture.fromId, port: 'out' }, to: { nodeId: toId, port: 'in' } });
            if (opts.onChange) opts.onChange();
          }
        }
        redrawEdges();
      }
      gesture = null;
      viewport.classList.remove('panning');
    }

    function onDblClick(e) {
      var nodeEl = e.target.closest('.dag-node');
      if (!nodeEl) return;
      var n = nodeById(nodeEl.getAttribute('data-node-id'));
      if (n && opts.onOpenConfig) opts.onOpenConfig(n);
    }

    function onWheel(e) {
      e.preventDefault();
      var r = viewport.getBoundingClientRect();
      var before = toWorkspace(e.clientX, e.clientY);
      var factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
      var next = Math.min(1.6, Math.max(0.4, scale * factor));
      scale = next;
      tx = (e.clientX - r.left) - before.x * scale;
      ty = (e.clientY - r.top) - before.y * scale;
      applyTransform();
    }

    function onKey(e) {
      if (!selected && !selectedEdge) return;
      var tag = (e.target.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable) return;
      if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); api.removeSelected(); }
    }

    function onDragOver(e) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }
    function onDrop(e) {
      e.preventDefault();
      var raw = e.dataTransfer.getData('text/plain');
      if (!raw || !opts.onDrop) return;
      var parts = raw.split('|');
      var pt = toWorkspace(e.clientX, e.clientY);
      opts.onDrop(parts[0], parts[1], snap(pt.x - NODE_W / 2), snap(pt.y - 20));
    }

    viewport.addEventListener('mousedown', onMouseDown);
    viewport.addEventListener('dblclick', onDblClick);
    viewport.addEventListener('wheel', onWheel, { passive: false });
    viewport.addEventListener('dragover', onDragOver);
    viewport.addEventListener('drop', onDrop);
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    window.addEventListener('keydown', onKey);
    applyTransform();

    /* ---------- 对外 API ---------- */
    var api = {
      setFlow: function (f, animate) {
        timers.forEach(clearTimeout); timers = [];
        flow = f; selected = null; selectedEdge = null;
        renderNodes(!!animate);
        redrawEdges();
      },
      redrawEdges: redrawEdges,
      getFlow: function () { return flow; },
      addNode: function (n) {
        flow.nodes.push(n); renderNodes(false); redrawEdges(); selectNode(n);
      },
      updateNode: function (id, patch) {
        var n = nodeById(id);
        if (!n) return;
        Object.keys(patch).forEach(function (k) { n[k] = patch[k]; });
        var el = nodeLayer.querySelector('[data-node-id="' + id + '"]');
        if (el) {
          el.querySelector('.node-title').textContent = n.title;
          el.querySelector('.node-sub').textContent = n.subtitle || '';
        }
        if (opts.onChange) opts.onChange();
      },
      removeSelected: function () {
        if (selected) {
          var id = selected.id;
          flow.nodes = flow.nodes.filter(function (n) { return n.id !== id; });
          flow.connections = flow.connections.filter(function (c) { return c.from.nodeId !== id && c.to.nodeId !== id; });
          selected = null; renderNodes(false); redrawEdges();
          if (opts.onSelect) opts.onSelect(null);
          if (opts.onChange) opts.onChange();
          return true;
        }
        if (selectedEdge) {
          flow.connections = flow.connections.filter(function (c) { return c.id !== selectedEdge; });
          selectedEdge = null; redrawEdges();
          if (opts.onChange) opts.onChange();
          return true;
        }
        return false;
      },
      selectNode: function (id) { var n = nodeById(id); if (n) selectNode(n); },
      highlight: function (id, kind) {
        var el = nodeLayer.querySelector('[data-node-id="' + id + '"]');
        if (el) { el.classList.remove('node-running', 'node-success', 'node-error'); if (kind) el.classList.add('node-' + kind); }
      },
      clearHighlights: function () {
        nodeLayer.querySelectorAll('.dag-node').forEach(function (el) {
          el.classList.remove('node-running', 'node-success', 'node-error');
        });
      },
      runAnimation: function (done) {
        api.clearHighlights();
        var order = flow.nodes.slice().sort(function (a, b) { return a.x - b.x; });
        var i = 0;
        function step() {
          if (i >= order.length) { if (done) done(); return; }
          var n = order[i++];
          api.highlight(n.id, 'running');
          timers.push(setTimeout(function () {
            api.highlight(n.id, 'success');
            timers.push(setTimeout(step, 110));
          }, 320));
        }
        step();
      },
      zoom: function (factor) {
        var r = viewport.getBoundingClientRect();
        var before = toWorkspace(r.left + r.width / 2, r.top + r.height / 2);
        scale = Math.min(1.6, Math.max(0.4, scale * factor));
        tx = r.width / 2 - before.x * scale;
        ty = r.height / 2 - before.y * scale;
        applyTransform();
      },
      fit: function () {
        if (!flow || !flow.nodes.length) return;
        var xs = flow.nodes.map(function (n) { return n.x; });
        var ys = flow.nodes.map(function (n) { return n.y; });
        var minX = Math.min.apply(null, xs), minY = Math.min.apply(null, ys);
        var maxX = Math.max.apply(null, xs) + NODE_W, maxY = Math.max.apply(null, ys) + 100;
        var r = viewport.getBoundingClientRect();
        scale = Math.min(1.1, (r.width - 80) / (maxX - minX), (r.height - 80) / (maxY - minY));
        scale = Math.max(0.4, scale);
        tx = (r.width - (maxX - minX) * scale) / 2 - minX * scale;
        ty = (r.height - (maxY - minY) * scale) / 2 - minY * scale;
        applyTransform();
      },
      getScale: function () { return scale; },
      destroy: function () {
        timers.forEach(clearTimeout); timers = [];
        viewport.removeEventListener('mousedown', onMouseDown);
        viewport.removeEventListener('dblclick', onDblClick);
        viewport.removeEventListener('wheel', onWheel);
        viewport.removeEventListener('dragover', onDragOver);
        viewport.removeEventListener('drop', onDrop);
        window.removeEventListener('mousemove', onMouseMove);
        window.removeEventListener('mouseup', onMouseUp);
        window.removeEventListener('keydown', onKey);
        if (workspace.parentNode) workspace.parentNode.removeChild(workspace);
      }
    };
    return api;
  };
})();
