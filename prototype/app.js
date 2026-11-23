/* 应用外壳：hash 路由 / 全局状态 / 六个页面渲染与联动 */
(function () {
  window.Proto = window.Proto || {};
  var D = Proto.DATA, clone = Proto.clone;

  var state = {
    projects: clone(D.projects),
    currentProjectId: 'P-001',
    user: null,
    loggedIn: false,
    sources: clone(D.sources),
    variables: clone(D.variables),
    credentials: clone(D.credentials),
    flows: clone(D.flows),
    flowStatus: clone(D.flowStatus),
    executions: clone(D.executions),
    currentFlowId: 'flow-1',
    selectedExecId: 'EXP-20261121-0041',
    range: '24h',
    varTab: 'variables',
    sourceQuery: '', sourceType: 'all',
    varScope: 'all', execFilter: { flow: 'all', status: 'all', trigger: 'all', q: '' },
    revealed: {}, ioOpen: {}, runSeq: 44,
    aiPageMessages: [], pendingNodeTitle: '', dag: null, dirty: false,
    installedNodes: []
  };
  Proto.state = state;

  var CODE_SAMPLES = {
    nodejs: [
      '// 输入：$input.all() 返回 items 数组',
      '// 输出：return 数组，每个元素形如 { json: {...} }',
      'const results = [];',
      'for (const item of $input.all()) {',
      '  const v = Number(item.json.value);',
      '  if (!Number.isFinite(v)) continue;',
      '  results.push({ json: {',
      '    tag: item.json.tag,',
      '    value: Number(v.toFixed(2)),',
      '    ts: new Date().toISOString()',
      '  }});',
      '}',
      'return results;'
    ].join('\n'),
    python: [
      '# 输入：_items，形如 [{"json": {...}}]',
      '# 输出：返回列表，每个元素形如 {"json": {...}}',
      'results = []',
      'for item in _items:',
      '    try:',
      '        value = float(item["json"]["value"])',
      '    except (ValueError, TypeError):',
      '        continue',
      '    results.append({"json": {',
      '        "tag": item["json"].get("tag"),',
      '        "value": round(value, 2)',
      '    }})',
      'results'
    ].join('\n')
  };

  /* ---------- 工具 ---------- */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function badge(kind, text) { return '<span class="badge badge-' + kind + '">' + text + '</span>'; }
  function sourceStatus(s) {
    return { online: badge('success', '在线'), offline: badge('gray', '离线'), error: badge('danger', '异常') }[s] || badge('gray', s);
  }
  function execStatus(s) {
    return { success: badge('success', '成功'), error: badge('danger', '失败'), running: badge('warning', '运行中') }[s] || badge('gray', s);
  }
  function flowStatus(s) {
    return s === 'running' ? badge('info', '运行中') : badge('gray', '已暂停');
  }
  function nowTime() {
    var d = new Date();
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
  }
  function toast(msg, type) {
    var root = document.getElementById('toastRoot');
    var el = document.createElement('div');
    el.className = 'toast ' + (type || '');
    var ic = type === 'error' ? 'alert' : type === 'success' ? 'check' : 'info';
    el.innerHTML = '<span style="color:' + (type === 'error' ? 'var(--danger)' : type === 'success' ? 'var(--success)' : 'var(--primary)') + '">' + Proto.icon(ic, 17) + '</span><span>' + msg + '</span>';
    root.appendChild(el);
    setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 3200);
  }
  function flowsOfProject(pid) {
    return state.flows.filter(function (f) { return f.projectId === pid; });
  }
  function currentFlow() {
    var list = flowsOfProject(state.currentProjectId);
    return list.filter(function (f) { return f.id === state.currentFlowId; })[0] || list[0] || null;
  }
  function projectName(id) {
    var p = state.projects.filter(function (x) { return x.id === id; })[0];
    return p ? p.name : '未分类';
  }
  function sourceLabel(id) {
    var s = state.sources.filter(function (x) { return x.id === id; })[0];
    return s ? s.name : (id || '未选择');
  }
  /* 节点类型 → 可用的数据源协议，避免选到不匹配的连接 */
  var DS_PROTOCOL_BY_SUBTYPE = {
    opcua: ['OPC UA'], modbus: ['Modbus TCP'], s7comm: ['S7comm'], mqtt: ['MQTT'],
    kafka: ['Kafka'], mysql: ['MySQL'], oracle: ['Oracle'], postgres: ['PostgreSQL'],
    sqlserver: ['SQL Server'], mongodb: ['MongoDB'], http: ['HTTP/REST'],
    timeseries: ['PostgreSQL', 'InfluxDB'], influxdb: ['InfluxDB']
  };
  function datasourceProtocols(n) { return DS_PROTOCOL_BY_SUBTYPE[n.subtype] || null; }
  function credLabel(id) {
    var c = state.credentials.filter(function (x) { return x.id === id; })[0];
    return c ? c.name : (id || '未选择');
  }
  /* 兼容旧配置：手填地址或凭据名时，自动匹配到已建的数据源 / 凭据 ID */
  function refValue(n, fd) {
    var v = n.config[fd.key];
    if (v) {
      if (fd.type === 'credential' && !state.credentials.some(function (c) { return c.id === v; })) {
        var byName = state.credentials.filter(function (c) { return c.name === v; })[0];
        if (byName) return byName.id;
      }
      if (fd.type === 'datasource' && !state.sources.some(function (s) { return s.id === v; })) {
        var legacy = state.sources.filter(function (s) { return s.name === v || s.address === v; })[0];
        if (legacy) return legacy.id;
      }
      return v;
    }
    if (fd.type === 'datasource') {
      var addr = n.config.endpoint || n.config.host || n.config.broker || n.config.brokers || n.config.address || n.config.url || '';
      if (addr) {
        var hit = state.sources.filter(function (s) { return s.address === addr || addr.indexOf(s.address) >= 0 || s.address.indexOf(addr) >= 0; })[0];
        if (hit) return hit.id;
      }
    }
    return '';
  }
  function closeDrawer() {
    var o = document.getElementById('appDrawerOverlay');
    if (o && o.parentNode) o.parentNode.removeChild(o);
  }
  function openDrawer(cfg) {
    closeDrawer();
    var o = document.createElement('div');
    o.className = 'overlay'; o.id = 'appDrawerOverlay';
    o.innerHTML = '<div class="drawer ' + (cfg.wide ? 'wide' : '') + '">' +
      '<div class="drawer-head"><div><div class="drawer-title">' + esc(cfg.title) + '</div>' +
      (cfg.sub ? '<div class="drawer-sub">' + esc(cfg.sub) + '</div>' : '') + '</div>' +
      '<button class="btn btn-ghost btn-sm" data-action="close-drawer">' + Proto.icon('close', 16) + '</button></div>' +
      '<div class="drawer-body">' + cfg.body + '</div>' +
      '<div class="drawer-foot">' + (cfg.footer || '<button class="btn" data-action="close-drawer">取消</button>') + '</div></div>';
    document.getElementById('overlayRoot').appendChild(o);
    o.addEventListener('click', function (e) { if (e.target === o) closeDrawer(); });
    return o;
  }
  function confirmModal(title, msg, onOk) {
    var o = document.createElement('div');
    o.className = 'modal-wrap'; o.id = 'confirmModal';
    o.innerHTML = '<div class="modal"><div class="modal-body"><div class="strong" style="font-size:15px;margin-bottom:8px">' + esc(title) + '</div>' +
      '<div class="muted">' + esc(msg) + '</div></div><div class="modal-foot">' +
      '<button class="btn" data-action="confirm-cancel">取消</button><button class="btn btn-danger" data-action="confirm-ok">确认删除</button></div></div>';
    document.getElementById('overlayRoot').appendChild(o);
    o._onOk = onOk;
  }
  function field(label, inner, hint) {
    return '<div class="field"><label>' + label + '</label>' + inner + (hint ? '<div class="field-hint">' + hint + '</div>' : '') + '</div>';
  }
  function input(name, value, placeholder) {
    return '<input class="input" name="' + name + '" value="' + esc(value || '') + '" placeholder="' + esc(placeholder || '') + '">';
  }
  function select(name, options, value) {
    return '<select class="select" name="' + name + '">' + options.map(function (o) {
      var v = typeof o === 'string' ? o : o.v, t = typeof o === 'string' ? o : o.t;
      return '<option value="' + esc(v) + '"' + (v === value ? ' selected' : '') + '>' + esc(t) + '</option>';
    }).join('') + '</select>';
  }
  function getVal(name) {
    var el = document.querySelector('#appDrawerOverlay [name="' + name + '"]');
    return el ? el.value.trim() : '';
  }
  function safeFileName(name, fallback) {
    var s = String(name == null ? '' : name).trim()
      .replace(/[\\/:*?"<>|\r\n\t]+/g, '_')
      .replace(/\s+/g, '_')
      .replace(/^\.+|\.+$/g, '');
    return (s || fallback || 'export').slice(0, 80);
  }
  function exportJSON(filename, data) {
    var blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    toast('已导出 ' + filename, 'success');
  }
  function importJSON(onData) {
    var inp = document.createElement('input');
    inp.type = 'file'; inp.accept = 'application/json,.json'; inp.style.display = 'none';
    document.body.appendChild(inp);
    inp.addEventListener('change', function () {
      var file = inp.files && inp.files[0];
      if (!file) { if (inp.parentNode) inp.parentNode.removeChild(inp); return; }
      var reader = new FileReader();
      reader.onload = function () {
        try { onData(JSON.parse(reader.result)); }
        catch (e) { toast('导入失败：JSON 格式不正确（' + e.message + '）', 'error'); }
      };
      reader.onerror = function () { toast('导入失败：无法读取文件', 'error'); };
      reader.readAsText(file, 'utf-8');
      if (inp.parentNode) inp.parentNode.removeChild(inp);
    });
    inp.click();
  }
  function asArray(v) { return Array.isArray(v) ? v : (v && Array.isArray(v.items) ? v.items : null); }

  /* ---------- 路由 ---------- */
  function pageName() {
    var h = (location.hash || '').replace(/^#\/?/, '').split('?')[0];
    return ['login', 'dashboard', 'sources', 'flows', 'executions', 'variables', 'ai'].indexOf(h) >= 0 ? h : 'dashboard';
  }
  function navigate(p) { location.hash = '#/' + p; }

  function render() {
    var p = pageName();
    document.body.classList.toggle('login-mode', p === 'login');
    if (p === 'login') {
      if (state.dag) { state.dag.destroy(); state.dag = null; }
      renderLogin(document.getElementById('page'));
      return;
    }
    document.querySelectorAll('.nav-item').forEach(function (a) {
      a.classList.toggle('active', a.getAttribute('data-nav') === p);
    });
    var avatar = document.getElementById('topAvatar');
    if (avatar) avatar.textContent = state.user ? state.user.name.slice(0, 1) : '游';
    var page = document.getElementById('page');
    page.className = 'page' + (p === 'flows' ? ' page-flows' : '');
    if (state.dag) { state.dag.destroy(); state.dag = null; }
    if (p === 'dashboard') renderDashboard(page);
    else if (p === 'sources') renderSources(page);
    else if (p === 'flows') renderFlows(page);
    else if (p === 'variables') renderVariables(page);
    else if (p === 'executions') renderExecutions(page);
    else renderAI(page);
    window.scrollTo(0, 0);
    page.scrollTop = 0;
  }

  /* ---------- 登录（原型内置账号） ---------- */
  var USERS = [
    { username: 'admin', password: 'Admin@123', name: '系统管理员', role: '管理员' },
    { username: 'engineer', password: 'Eng@123', name: '李工', role: '采集工程师' },
    { username: 'viewer', password: 'View@123', name: '张敏', role: '只读用户' }
  ];
  function renderLogin(page) {
    var saved = '';
    try { saved = localStorage.getItem('tls_last_user') || ''; } catch (e) { saved = ''; }
    page.innerHTML = '<div class="login-page"><div class="login-hero">' +
      '<div class="login-brand"><span class="brand-mark">' + Proto.icon('layers', 22) + '</span> TongLink Studio</div>' +
      '<h1>让 IT 与 OT 数据<br>在同一条流水线流动</h1>' +
      '<p class="login-hero-desc">面向工业现场的数据采集平台：可视化编排、多协议接入、代码节点、执行观测与 AI 辅助。</p>' +
      '<div class="login-features">' +
      '<div>' + Proto.icon('workflow', 18) + '<span>DAG 可视化编排</span></div>' +
      '<div>' + Proto.icon('cpu', 18) + '<span>OPC UA / Modbus / MQTT</span></div>' +
      '<div>' + Proto.icon('terminal', 18) + '<span>节点级执行日志</span></div>' +
      '<div>' + Proto.icon('sparkles', 18) + '<span>AI 辅助诊断与生成</span></div></div>' +
      '<svg class="login-illus" viewBox="0 0 520 200" fill="none" aria-hidden="true">' +
      '<path d="M60 100h110M225 100h100M380 100h70" stroke="#93C5FD" stroke-width="2" stroke-dasharray="5 5"/>' +
      '<rect x="10" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE"/><rect x="26" y="86" width="28" height="28" rx="6" fill="#BFDBFE"/><circle cx="40" cy="100" r="7" fill="#2563EB"/>' +
      '<rect x="170" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE"/><rect x="186" y="86" width="28" height="28" rx="6" fill="#BFDBFE"/><path d="M193 100h14M200 93v14" stroke="#2563EB" stroke-width="3"/>' +
      '<rect x="325" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE"/><rect x="341" y="86" width="28" height="28" rx="6" fill="#BFDBFE"/><path d="M348 94h14M348 100h14M348 106h14" stroke="#2563EB" stroke-width="2.5"/>' +
      '<circle cx="465" cy="100" r="26" fill="#fff" stroke="#BFDBFE"/><path d="m457 100 5 5 10-11" stroke="#16A34A" stroke-width="3" fill="none" stroke-linecap="round"/></svg>' +
      '<div class="login-hero-foot">MIT Open Source · v0.1 交互原型</div></div>' +
      '<div class="login-panel"><div class="login-card">' +
      '<div class="login-card-head"><div class="login-mini-logo">' + Proto.icon('layers', 20) + '</div>' +
      '<h2>欢迎回来</h2><p>登录 TongLink Studio 控制台</p></div>' +
      '<div class="field"><label>用户名</label><input class="input" id="loginUser" value="' + esc(saved) + '" placeholder="请输入用户名" autocomplete="username"></div>' +
      '<div class="field"><label>密码</label><input class="input" id="loginPass" type="password" placeholder="请输入密码" autocomplete="current-password"></div>' +
      '<div class="login-row"><label class="login-remember"><input type="checkbox" id="loginRemember"' + (saved ? ' checked' : '') + '> 记住用户名</label>' +
      '<button class="btn btn-ghost btn-xs" data-action="skip-login">原型演示 · 跳过登录 →</button></div>' +
      '<div id="loginError" class="login-error"></div>' +
      '<button class="btn btn-primary login-submit" data-action="do-login">' + Proto.icon('shield', 16) + ' 登 录</button>' +
      '<div class="login-divider"><span>内置演示账号（点击自动填充）</span></div>' +
      '<div class="login-accounts">' + USERS.map(function (u) {
        return '<button class="login-account" data-action="fill-user" data-username="' + esc(u.username) + '"><strong>' + esc(u.username) + '</strong><span>' + esc(u.role) + ' · ' + esc(u.password) + '</span></button>';
      }).join('') + '</div>' +
      '<div class="login-tip">' + Proto.icon('info', 13) + ' 原型使用本地内置账号校验，不连接后端认证服务。</div>' +
      '</div></div></div>';
    setTimeout(function () {
      var el = document.getElementById(saved ? 'loginPass' : 'loginUser'); if (el) el.focus();
      ['loginUser', 'loginPass'].forEach(function (id) {
        var input = document.getElementById(id);
        if (input) input.addEventListener('keydown', function (e) { if (e.key === 'Enter') doLogin(); });
      });
    }, 60);
  }
  function doLogin() {
    var u = (document.getElementById('loginUser') || {}).value || '';
    var p = (document.getElementById('loginPass') || {}).value || '';
    var hit = USERS.filter(function (x) { return x.username === u.trim() && x.password === p; })[0];
    if (!hit) {
      var err = document.getElementById('loginError');
      if (err) err.textContent = '用户名或密码不正确，请使用下方内置账号。';
      return;
    }
    state.user = hit; state.loggedIn = true;
    try {
      if (document.getElementById('loginRemember').checked) localStorage.setItem('tls_last_user', hit.username);
      else localStorage.removeItem('tls_last_user');
    } catch (e) { /* file:// 下可能禁用存储，忽略 */ }
    navigate('dashboard');
    toast('欢迎回来，' + hit.name, 'success');
  }

  /* ---------- 数据看板 ---------- */
  function renderDashboard(page) {
    var d = D.dashboard[state.range];
    var kpis = d.kpis.map(function (k) {
      var color = k.trend === 'up' ? '#16A34A' : '#DC2626';
      return '<div class="card kpi-card"><div class="kpi-label">' + esc(k.label) + '</div>' +
        '<div class="kpi-value">' + esc(k.value) + '</div>' +
        '<div class="kpi-foot ' + (k.trend === 'up' ? 'trend-up' : 'trend-down') + '">' + esc(k.foot) + '</div>' +
        '<div class="kpi-spark">' + Proto.charts.sparkline(k.spark, color) + '</div></div>';
    }).join('');
    var legend = d.series.map(function (s) {
      return '<span><i class="legend-dot" style="background:' + s.color + '"></i>' + esc(s.name) + '</span>';
    }).join('');
    var donutLegend = d.donut.map(function (s) {
      return '<div style="display:flex;justify-content:space-between;font-size:12px;margin:6px 0"><span><i class="legend-dot" style="background:' + s.color + '"></i>' + esc(s.name) + '</span><strong>' + s.value.toLocaleString() + '</strong></div>';
    }).join('');
    var rows = state.flowStatus.map(function (f) {
      return '<tr><td><a href="javascript:;" data-action="goto-flow" data-flow-id="' + esc(f.id) + '" class="strong">' + esc(f.name) + '</a></td>' +
        '<td class="tiny">' + esc(projectName(f.projectId)) + '</td><td>' + flowStatus(f.status) + '</td><td class="mono tiny">' + esc(f.nextRun) + '</td>' +
        '<td class="strong">' + f.records.toLocaleString() + '</td><td>' + esc(f.successRate) + '</td><td class="tiny muted">' + esc(f.lastRun) + '</td></tr>';
    }).join('');

    page.innerHTML = '<div class="page-head"><div><h1 class="page-title">数据看板</h1>' +
      '<p class="page-desc">实时掌握流执行情况、下次执行时间与采集数据量</p></div>' +
      '<div class="page-actions"><div class="range-switch">' +
      ['24h', '7d', '30d'].map(function (r) {
        return '<button data-action="set-range" data-range="' + r + '" class="' + (state.range === r ? 'active' : '') + '">' + ({ '24h': '24 小时', '7d': '7 天', '30d': '30 天' }[r]) + '</button>';
      }).join('') + '</div><button class="btn" data-action="refresh-dashboard">' + Proto.icon('refresh', 15) + ' 刷新</button></div></div>' +
      '<div class="grid-4 mb14">' + kpis + '</div>' +
      '<div class="grid-2 mb14">' +
      '<div class="card chart-card"><div class="card-head"><h3 class="card-title">执行趋势</h3><div class="chart-legend">' + legend + '</div></div><div class="card-body">' +
      Proto.charts.lineChart(d.labels, d.series, { anomaly: d.anomaly }) +
      '<div class="info-box warning" style="margin-top:10px">' + Proto.icon('alert', 14) + ' 检测到异常点（' + esc(d.anomaly.label) + '）：' + esc(d.anomaly.text) +
      ' <button class="btn btn-xs btn-ghost" data-action="anomaly" style="color:var(--purple)">AI 分析 →</button></div></div></div>' +
      '<div class="card chart-card"><div class="card-head"><h3 class="card-title">各流程采集记录数</h3><span class="tiny muted">按流程统计</span></div><div class="card-body">' + Proto.charts.barChart(d.bars) + '</div></div></div>' +
      '<div class="grid-2 mb14" style="grid-template-columns:320px minmax(0,1fr)">' +
      '<div class="card"><div class="card-head"><h3 class="card-title">执行状态分布</h3></div><div class="card-body">' + Proto.charts.donutChart(d.donut) + donutLegend + '</div></div>' +
      '<div class="card"><div class="card-head"><h3 class="card-title">流程运行状态</h3><span class="tiny muted">点击流程名进入流程编排</span></div>' +
      '<div class="card-body" style="padding:0;overflow:auto"><table class="table"><thead><tr><th>流程名称</th><th>所属项目</th><th>状态</th><th>下次执行时间</th><th>采集记录数</th><th>成功率</th><th>最近运行</th></tr></thead><tbody>' + rows + '</tbody></table></div></div></div>';
  }

  /* ---------- 数据源管理 ---------- */
  function filteredSources() {
    var q = state.sourceQuery.toLowerCase();
    return state.sources.filter(function (s) {
      var okType = state.sourceType === 'all' || s.category === state.sourceType || s.protocol === state.sourceType;
      var okQ = !q || (s.name + s.address + s.area + s.protocol).toLowerCase().indexOf(q) >= 0;
      return okType && okQ;
    });
  }
  function sourceRowsHTML() {
    var list = filteredSources();
    if (!list.length) return '<tr><td colspan="8" class="empty">没有匹配的数据源</td></tr>';
    return list.map(function (s) {
      var cat = s.category === 'OT' ? 'badge-teal' : 'badge-info';
      var color = s.category === 'OT' ? '#0F766E' : '#2563EB';
      var soft = s.category === 'OT' ? '#CCFBF1' : '#EFF6FF';
      return '<tr><td class="strong">' + esc(s.name) + '<div class="tiny muted" style="font-weight:400">' + esc(s.id) + '</div></td>' +
        '<td><span class="badge ' + cat + '">' + esc(s.category) + '</span></td>' +
        '<td><div class="protocol-cell"><span class="protocol-icon" style="background:' + soft + ';color:' + color + '">' + Proto.icon(s.category === 'OT' ? 'cpu' : 'database', 15) + '</span>' + esc(s.protocol) + '</div></td>' +
        '<td class="mono tiny">' + esc(s.address) + '<div class="muted" style="font-family:inherit">' + esc(s.detail) + '</div></td>' +
        '<td>' + esc(s.area) + '</td><td class="tiny">' + esc(s.freq) + '</td><td>' + sourceStatus(s.status) + '<div class="tiny muted" style="margin-top:3px">' + esc(s.last) + '</div></td>' +
        '<td><div class="row-actions"><button class="btn btn-xs" data-action="test-source" data-id="' + esc(s.id) + '">' + Proto.icon('bolt', 12) + ' 测试</button>' +
        '<button class="btn btn-xs btn-ghost" data-action="export-source" data-id="' + esc(s.id) + '" title="导出该数据源，文件名即数据源名称">' + Proto.icon('download', 12) + '</button>' +
        '<button class="btn btn-xs btn-ghost" data-action="edit-source" data-id="' + esc(s.id) + '">' + Proto.icon('edit', 12) + '</button>' +
        '<button class="btn btn-xs btn-ghost" data-action="delete-source" data-id="' + esc(s.id) + '">' + Proto.icon('trash', 12) + '</button></div></td></tr>';
    }).join('');
  }
  function renderSources(page) {
    var counts = { online: 0, offline: 0, error: 0 };
    state.sources.forEach(function (s) { counts[s.status] = (counts[s.status] || 0) + 1; });
    var tiles = [
      { n: state.sources.length, l: '数据源总数', i: 'database', c: '#2563EB', b: '#EFF6FF' },
      { n: counts.online || 0, l: '在线', i: 'check', c: '#16A34A', b: '#DCFCE7' },
      { n: counts.offline || 0, l: '离线', i: 'cloud', c: '#6B7280', b: '#F1F5F9' },
      { n: counts.error || 0, l: '异常', i: 'alert', c: '#DC2626', b: '#FEE2E2' }
    ].map(function (t) {
      return '<div class="stat-tile"><div class="stat-icon" style="background:' + t.b + ';color:' + t.c + '">' + Proto.icon(t.i, 18) + '</div><div><div class="stat-num">' + t.n + '</div><div class="stat-label">' + t.l + '</div></div></div>';
    }).join('');
    var protocols = ['all', 'OT', 'IT', 'OPC UA', 'Modbus TCP', 'MQTT', 'HTTP/REST', 'PostgreSQL', 'Kafka'];
    page.innerHTML = '<div class="page-head"><div><h1 class="page-title">数据源管理</h1><p class="page-desc">统一接入 OT 现场设备与 IT 业务系统</p></div>' +
      '<div class="page-actions"><button class="btn" data-action="import-sources">' + Proto.icon('download', 15) + ' 导入</button>' +
      '<button class="btn" data-action="export-sources" title="导出全部数据源（单行右侧图标可按名称导出单个）">' + Proto.icon('download', 15) + ' 导出全部</button>' +
      '<button class="btn btn-primary" data-action="new-source">' + Proto.icon('plus', 15) + ' 新建数据源</button></div></div>' +
      '<div class="source-stats">' + tiles + '</div>' +
      '<div class="card"><div class="card-head"><h3 class="card-title">数据源列表</h3><span class="tiny muted">共 ' + state.sources.length + ' 个</span></div><div class="card-body">' +
      '<div class="filter-bar"><input class="input search" id="sourceSearch" placeholder="搜索名称 / 地址 / 区域…" value="' + esc(state.sourceQuery) + '">' +
      select('sourceTypeFilter', protocols.map(function (p) { return { v: p, t: p === 'all' ? '全部类型' : p }; }), state.sourceType).replace('class="select"', 'class="select" id="sourceTypeFilter"') +
      '<span class="tiny muted" id="sourceCount"></span></div>' +
      '<div style="overflow:auto"><table class="table"><thead><tr><th>名称</th><th>类别</th><th>协议</th><th>连接地址 / 点位</th><th>区域</th><th>采集频率</th><th>状态 / 最近采集</th><th>操作</th></tr></thead><tbody id="sourceTableBody">' + sourceRowsHTML() + '</tbody></table></div></div></div>';
    document.getElementById('sourceSearch').addEventListener('input', function (e) { state.sourceQuery = e.target.value; refreshSourceTable(); });
    document.getElementById('sourceTypeFilter').addEventListener('change', function (e) { state.sourceType = e.target.value; refreshSourceTable(); });
    refreshSourceTable();
  }
  function refreshSourceTable() {
    var body = document.getElementById('sourceTableBody');
    if (!body) return;
    body.innerHTML = sourceRowsHTML();
    var c = document.getElementById('sourceCount');
    if (c) c.textContent = '显示 ' + filteredSources().length + ' 条';
  }
  function sourceForm(s) {
    s = s || {};
    var protos = Object.keys(Proto.SOURCE_PROTOCOL_FIELDS);
    return '<input type="hidden" name="id" value="' + esc(s.id || '') + '">' +
      field('数据源名称', input('name', s.name, '例如：OPC UA-反应釜温度')) +
      field('协议类型', select('protocol', protos, s.protocol || 'OPC UA')) +
      '<div id="sourceExtra"></div>' +
      field('连接地址', input('address', s.address, 'opc.tcp://10.20.3.11:4840')) +
      field('所属区域', input('area', s.area, '车间A')) +
      field('采集频率', input('freq', s.freq, '1s')) +
      field('状态', select('status', [{ v: 'online', t: '在线' }, { v: 'offline', t: '离线' }, { v: 'error', t: '异常' }], s.status || 'online')) +
      field('备注', '<textarea class="textarea" name="detail" placeholder="点位 / 主题 / 寄存器说明">' + esc(s.detail || '') + '</textarea>');
  }
  function refreshSourceExtra(protocol, values) {
    var box = document.getElementById('sourceExtra');
    if (!box) return;
    var fields = Proto.SOURCE_PROTOCOL_FIELDS[protocol] || [];
    box.innerHTML = fields.map(function (f, i) {
      return field(f, input('extra' + i, values && values[i], ''));
    }).join('');
  }
  function openSourceDrawer(s) {
    var isEdit = !!s;
    openDrawer({
      title: isEdit ? '编辑数据源' : '新建数据源', sub: 'OT / IT 统一接入配置',
      body: sourceForm(s),
      footer: '<button class="btn" data-action="close-drawer">取消</button><button class="btn" data-action="test-source-form">' + Proto.icon('bolt', 14) + ' 测试连接</button>' +
        '<button class="btn btn-primary" data-action="save-source" data-id="' + esc(s ? s.id : '') + '">' + Proto.icon('save', 14) + ' 保存</button>'
    });
    var sel = document.querySelector('#appDrawerOverlay [name="protocol"]');
    refreshSourceExtra(sel.value, s ? [s.detail] : []);
    sel.addEventListener('change', function () { refreshSourceExtra(sel.value); });
  }

  /* ---------- 流程编排 ---------- */
  function installedGroup() {
    var g = Proto.NODE_CATALOG.filter(function (x) { return x.group === '已安装节点'; })[0];
    if (!g) { g = { group: '已安装节点', items: [] }; Proto.NODE_CATALOG.push(g); }
    return g;
  }
  function registerNode(pkg, installed) {
    var key = pkg.type + ':' + pkg.subtype;
    if (Proto.NODE_SCHEMAS[key]) { toast('节点 ' + pkg.title + ' 已存在，无法重复安装', 'error'); return false; }
    var g = installedGroup();
    g.items.push({ type: pkg.type, subtype: pkg.subtype, title: pkg.title, icon: pkg.icon || 'package',
      color: pkg.color || 'green', installed: true, pkgKey: pkg.key });
    var schema = clone(pkg.schema || { title: pkg.title, note: '', fields: [] });
    schema.fields = (schema.fields || []).filter(function (fd) { return fd && fd.key && fd.label; }).map(function (fd) {
      var allowed = ['text', 'textarea', 'select', 'number', 'secret', 'code', 'datasource', 'credential'];
      var out = { key: String(fd.key), label: String(fd.label), type: allowed.indexOf(fd.type) >= 0 ? fd.type : 'text' };
      if (fd.placeholder !== undefined) out.placeholder = String(fd.placeholder);
      if (fd.default !== undefined) out.default = fd.default;
      if (fd.hint !== undefined) out.hint = String(fd.hint);
      if (fd.rows !== undefined) out.rows = Number(fd.rows) || 3;
      if (fd.required) out.required = true;
      if (Array.isArray(fd.options)) out.options = fd.options.map(String);
      if (fd.showWhen && Array.isArray(fd.showWhen.in)) out.showWhen = { key: String(fd.showWhen.key), in: fd.showWhen.in.map(String) };
      return out;
    });
    Proto.NODE_SCHEMAS[key] = { title: schema.title || pkg.title, note: schema.note || '', fields: schema.fields };
    if (installed) state.installedNodes.push(pkg.key);
    return true;
  }
  function installNode(key) {
    var pkg = Proto.NODE_MARKET.filter(function (x) { return x.key === key; })[0];
    if (!pkg) return;
    if (state.installedNodes.indexOf(key) >= 0) { toast('该节点已安装', 'info'); return; }
    if (!registerNode(pkg, true)) return;
    if (pageName() === 'flows') renderFlows(document.getElementById('page'));
    openNodeManager();
    toast('节点「' + pkg.title + '」安装成功，已加入节点库', 'success');
  }
  function uninstallNode(key) {
    var g = installedGroup();
    g.items = g.items.filter(function (it) { return it.pkgKey !== key; });
    state.installedNodes = state.installedNodes.filter(function (k) { return k !== key; });
    if (pageName() === 'flows') renderFlows(document.getElementById('page'));
    openNodeManager();
    toast('节点已卸载；已有流程中的该节点仍可编辑', 'success');
  }
  function installNodeFromFile() {
    importJSON(function (data) {
      var pkg = data.node || data;
      if (!pkg || !pkg.type || !pkg.subtype || !pkg.title || !pkg.schema || !Array.isArray(pkg.schema.fields)) {
        toast('安装失败：节点包缺少 type / subtype / title / schema.fields', 'error'); return;
      }
      var key = 'pkg-custom-' + Date.now();
      pkg.key = key;
      pkg.type = String(pkg.type); pkg.subtype = String(pkg.subtype); pkg.title = String(pkg.title);
      if (!registerNode(pkg, true)) return;
      if (pageName() === 'flows') renderFlows(document.getElementById('page'));
      openNodeManager();
      toast('自定义节点「' + pkg.title + '」安装成功', 'success');
    });
  }
  function openNodeManager() {
    var installed = state.installedNodes;
    var rows = Proto.NODE_MARKET.map(function (pkg) {
      var on = installed.indexOf(pkg.key) >= 0;
      return '<div class="market-item"><div class="market-icon">' + Proto.icon(pkg.icon || 'package', 20) + '</div>' +
        '<div class="market-info"><div class="strong">' + esc(pkg.title) + ' <span class="tiny muted">v' + esc(pkg.version) + '</span></div>' +
        '<div class="tiny muted" style="margin:3px 0">' + esc(pkg.desc) + '</div>' +
        '<div class="tiny muted">' + esc(pkg.author) + '</div></div>' +
        '<button class="btn ' + (on ? '' : 'btn-primary') + ' btn-sm" data-action="' + (on ? 'uninstall-node' : 'install-node') + '" data-id="' + esc(pkg.key) + '">' +
        (on ? '卸载' : '安装') + '</button></div>';
    }).join('');
    openDrawer({
      title: '节点管理', sub: '内置节点开箱即用，扩展节点可单独安装 / 卸载',
      body: '<div class="info-box" style="margin-bottom:12px">' + Proto.icon('info', 14) +
        ' 内置节点 ' + Proto.NODE_CATALOG.reduce(function (sum, g) {
          return sum + g.items.filter(function (it) { return !it.installed; }).length;
        }, 0) + ' 个 · 已安装扩展节点 ' + installed.length +
        ' 个。节点以独立包形式注册，安装后即可出现在节点库中。</div>' +
        '<div class="market-title">节点市场（模拟）</div>' + rows +
        '<div class="market-title">自定义节点包</div>' +
        '<div class="market-item"><div class="market-icon">' + Proto.icon('upload', 20) + '</div>' +
        '<div class="market-info"><div class="strong">从本地 JSON 安装</div>' +
        '<div class="tiny muted" style="margin-top:3px">节点包定义 type / subtype / title / schema.fields，安装后即可拖拽使用</div></div>' +
        '<button class="btn btn-sm" data-action="install-node-file">' + Proto.icon('plus', 13) + ' 选择文件</button></div>',
      footer: '<button class="btn btn-primary" data-action="close-drawer">完成</button>',
      wide: true
    });
  }
  function paletteHTML() {
    return Proto.NODE_CATALOG.map(function (g) {
      return '<div class="palette-title">' + esc(g.group) + '</div><div class="palette-grid">' + g.items.map(function (it) {
        var installed = it.installed ? '<span class="pkg-tag">已装</span>' : '';
        return '<button class="palette-item" draggable="true" data-type="' + esc(it.type) + '" data-subtype="' + esc(it.subtype) + '" title="' + esc(it.title) + ' · 拖到画布或点击添加">' +
          '<span style="color:' + (Proto.NODE_THEME[it.type] || { color: '#64748B' }).color + '">' + Proto.icon(it.icon, 14) + '</span><span class="ellipsis">' + esc(it.title) + '</span>' + installed + '</button>';
      }).join('') + '</div>';
    }).join('');
  }
  function renderFlows(page) {
    var flow = currentFlow();
    var list = flowsOfProject(state.currentProjectId);
    var flowList = list.map(function (f) {
      return '<div class="flow-item ' + (f.id === (flow && flow.id) ? 'active' : '') + '" data-action="select-flow" data-id="' + esc(f.id) + '">' +
        '<div class="flow-name">' + Proto.icon('workflow', 13) + '<span class="ellipsis">' + esc(f.name) + '</span></div>' +
        '<div class="flow-meta"><span>' + f.nodes.length + ' 节点 · ' + f.connections.length + ' 连线</span><span>' + esc(f.lastRun) + '</span></div></div>';
    }).join('');
    if (!list.length) {
      flowList = '<div class="flow-empty">当前项目还没有流程<br><button class="btn btn-primary btn-xs" style="margin-top:10px" data-action="new-flow">' + Proto.icon('plus', 12) + ' 新建流程</button></div>';
    }
    var sidebar = '<aside class="flow-side">' +
      '<div class="flow-side-head"><div><div class="strong">流程库</div><div class="tiny muted" style="margin-top:2px">已按所选项目过滤</div></div>' +
      '<div style="display:flex;gap:4px"><button class="btn btn-xs" data-action="new-project" title="新建项目">' + Proto.icon('layers', 13) + '</button>' +
      '<button class="btn btn-primary btn-xs" data-action="new-flow" title="在所选项目下新建流程">' + Proto.icon('plus', 13) + ' 流程</button></div></div>' +
      '<div class="project-selector"><select id="currentProject" class="select" style="height:28px;font-size:12px">' +
      state.projects.map(function (p) {
        return '<option value="' + esc(p.id) + '"' + (p.id === state.currentProjectId ? ' selected' : '') + '>' +
          esc(p.name) + '（' + flowsOfProject(p.id).length + ' 个流程）</option>';
      }).join('') +
      '</select></div>' +
      '<div class="project-list-title">流程 · ' + list.length + '</div>' +
      '<div class="flow-list">' + flowList + '</div>' +
      '<div class="node-palette"><div class="palette-head"><span class="palette-title">节点库 · 拖拽到画布</span>' +
      '<button class="btn btn-xs" data-action="node-manager" title="安装 / 管理节点">' + Proto.icon('package', 12) + ' 节点管理</button></div>' + paletteHTML() + '</div></aside>';

    if (!flow) {
      page.innerHTML = '<div class="flow-layout">' + sidebar + '<div class="flow-main"><div class="canvas-toolbar">' +
        '<span class="canvas-title">当前项目暂无流程</span><span class="tiny muted">' + esc(projectName(state.currentProjectId)) + '</span></div>' +
        '<div class="dag-viewport"><div class="canvas-empty"><div class="empty-icon">' + Proto.icon('workflow', 30) + '</div>' +
        '<div class="strong" style="font-size:15px;margin-top:12px">该项目还没有流程</div>' +
        '<div class="tiny muted" style="margin-top:6px">先在此项目下新建流程，再从节点库拖入节点</div>' +
        '<button class="btn btn-primary btn-sm" style="margin-top:16px" data-action="new-flow">' + Proto.icon('plus', 14) + ' 新建流程</button></div></div></div></div>';
      document.getElementById('currentProject').addEventListener('change', function (e) {
        state.currentProjectId = e.target.value;
        state.currentFlowId = (flowsOfProject(state.currentProjectId)[0] || {}).id || '';
        renderFlows(document.getElementById('page'));
      });
      return;
    }

    page.innerHTML = '<div class="flow-layout">' + sidebar +
      '<div class="flow-main"><div class="canvas-toolbar">' +
      '<span class="canvas-title">' + esc(flow.name) + '</span><span class="tiny muted">' + esc(projectName(flow.projectId)) + '</span>' + flowStatus(flow.status) +
      '<span class="sep"></span>' +
      '<button class="btn btn-primary btn-sm" data-action="flow-run">' + Proto.icon('play', 14) + ' 运行</button>' +
      '<button class="btn btn-sm" data-action="flow-save">' + Proto.icon('save', 14) + ' 保存</button>' +
      '<button class="btn btn-sm" data-action="export-flow">' + Proto.icon('download', 14) + ' 导出</button>' +
      '<button class="btn btn-sm" data-action="import-flow">' + Proto.icon('upload', 14) + ' 导入</button>' +
      '<span class="sep"></span>' +
      '<button class="btn btn-ghost btn-sm btn-icon" data-action="zoom-out" title="缩小">' + Proto.icon('zoomOut', 16) + '</button>' +
      '<span class="tiny muted" id="zoomLabel">100%</span>' +
      '<button class="btn btn-ghost btn-sm btn-icon" data-action="zoom-in" title="放大">' + Proto.icon('zoomIn', 16) + '</button>' +
      '<button class="btn btn-ghost btn-sm btn-icon" data-action="fit-view" title="适配视图">' + Proto.icon('fit', 16) + '</button>' +
      '<span class="sep"></span>' +
      '<button class="btn btn-sm" style="color:var(--purple);border-color:#DDD6FE" data-action="ai-generate">' + Proto.icon('sparkles', 14) + ' AI 生成流程</button>' +
      '</div><div id="dagViewport" class="dag-viewport"></div>' +
      '<div class="canvas-status"><span>节点 <strong id="statNodes">' + flow.nodes.length + '</strong></span><span>连线 <strong id="statEdges">' + flow.connections.length + '</strong></span>' +
      '<span>缩放 <strong id="statZoom">100%</strong></span><span>网格吸附 20px</span><span>单击拖动 · 双击配置属性</span><span style="margin-left:auto" id="saveHint">上次保存：' + esc(nowTime()) + '</span></div></div></div>';

    document.getElementById('currentProject').addEventListener('change', function (e) {
      state.currentProjectId = e.target.value;
      state.currentFlowId = (flowsOfProject(state.currentProjectId)[0] || {}).id || '';
      renderFlows(document.getElementById('page'));
    });
    var vp = document.getElementById('dagViewport');
    state.dag = Proto.createDagEditor(vp, {
      onSelect: function () { /* 单击仅选中，属性面板由双击打开 */ },
      onOpenConfig: function (n) { openNodeConfig(n); },
      onChange: function () {
        var f = currentFlow();
        if (document.getElementById('statNodes')) {
          document.getElementById('statNodes').textContent = f.nodes.length;
          document.getElementById('statEdges').textContent = f.connections.length;
          document.getElementById('saveHint').textContent = '未保存的更改';
        }
      },
      onZoom: function (z) {
        var a = document.getElementById('zoomLabel'), b = document.getElementById('statZoom');
        if (a) a.textContent = z + '%'; if (b) b.textContent = z + '%';
      },
      onDrop: function (type, subtype, x, y) { addNode(type, subtype, x, y); }
    });
    state.dag.setFlow(flow, false);
    state.dag.fit();
    document.querySelectorAll('.palette-item').forEach(function (btn) {
      btn.addEventListener('dragstart', function (e) {
        e.dataTransfer.setData('text/plain', btn.dataset.type + '|' + btn.dataset.subtype);
      });
      btn.addEventListener('click', function () {
        var vpRect = vp.getBoundingClientRect();
        var pt = { x: (vpRect.width / 2 - 24) / state.dag.getScale(), y: (vpRect.height / 2 - 12) / state.dag.getScale() };
        addNode(btn.dataset.type, btn.dataset.subtype, Math.max(20, Math.round(pt.x / 20) * 20), Math.max(20, Math.round(pt.y / 20) * 20));
      });
    });
    if (state.pendingNodeTitle) {
      var target = flow.nodes.filter(function (n) { return n.title === state.pendingNodeTitle; })[0];
      if (target) state.dag.selectNode(target.id);
      state.pendingNodeTitle = '';
    }
  }
  function addNode(type, subtype, x, y) {
    var item = null;
    Proto.NODE_CATALOG.forEach(function (g) { g.items.forEach(function (it) { if (it.type === type && it.subtype === subtype) item = it; }); });
    if (!item) return;
    var schema = Proto.schemaFor({ type: type, subtype: subtype });
    var config = {};
    schema.fields.forEach(function (fd) { if (fd.default !== undefined) config[fd.key] = fd.default; });
    if (type === 'code') config.code = CODE_SAMPLES[subtype];
    var n = { id: 'n-' + Date.now(), type: type, subtype: subtype, title: item.title, subtitle: schema.title, x: x, y: y, config: config };
    state.dag.addNode(n);
    toast('已添加节点：' + item.title, 'success');
  }
  function renderSchemaField(fd, n) {
    var val = n.config[fd.key] !== undefined ? n.config[fd.key] : (fd.default !== undefined ? fd.default : '');
    var attrs = (fd.showWhen ? ' data-show-key="' + esc(fd.showWhen.key) + '" data-show-in="' + esc(fd.showWhen.in.join(',')) + '"' : '');
    var inner = '';
    if (fd.type === 'select') {
      var opts = (fd.options || []).map(function (o) { return fd.labels && fd.labels[o] ? { v: o, t: fd.labels[o] } : o; });
      inner = select(fd.key, opts, val);
    } else if (fd.type === 'datasource' || fd.type === 'credential') {
      var isDs = fd.type === 'datasource';
      var protocols = isDs ? datasourceProtocols(n) : null;
      var items = isDs ? (protocols ? state.sources.filter(function (s) { return protocols.indexOf(s.protocol) >= 0; }) : state.sources.slice()) : state.credentials.slice();
      var selected = refValue(n, fd);
      /* 兼容历史配置：若当前选中项不在过滤结果中，保留该项并标注协议不匹配 */
      var chosen = null;
      if (isDs && selected) chosen = state.sources.filter(function (s) { return s.id === selected; })[0];
      if (chosen && !items.some(function (s) { return s.id === chosen.id; })) items.push(chosen);
      var options = '<option value="">— 请选择已创建的' + (isDs ? '数据源' : '凭据') + ' —</option>' + items.map(function (it) {
        var mismatch = chosen && it.id === chosen.id && protocols && protocols.indexOf(it.protocol) < 0;
        var text = isDs ? (it.name + ' · ' + it.protocol + '（' + it.id + '）' + (mismatch ? ' · 协议不匹配' : ''))
          : (it.name + ' · ' + it.type + '（' + it.id + '）');
        return '<option value="' + esc(it.id) + '"' + (it.id === selected ? ' selected' : '') + '>' + esc(text) + '</option>';
      }).join('');
      inner = '<select class="select" name="' + esc(fd.key) + '">' + options + '</select>' +
        (protocols ? '<div class="field-hint">已按节点类型过滤：仅显示 ' + esc(protocols.join(' / ')) + ' 数据源' +
          (items.length ? '' : ' · 暂无匹配项，请先到数据源管理创建') + '</div>' : '') +
        '<div style="margin-top:5px"><button type="button" class="btn btn-ghost btn-xs" data-action="' + (isDs ? 'goto-sources-from-drawer' : 'goto-credentials-from-drawer') + '">' +
        Proto.icon('plus', 12) + (isDs ? ' 去新建/管理数据源' : ' 去新建/管理凭据') + '</button></div>';
    } else if (fd.type === 'textarea') {
      inner = '<textarea class="textarea" name="' + esc(fd.key) + '" rows="' + (fd.rows || 3) + '" placeholder="' + esc(fd.placeholder || '') + '">' + esc(val) + '</textarea>';
    } else if (fd.type === 'secret') {
      inner = '<input class="input" type="password" name="' + esc(fd.key) + '" value="' + esc(val) + '" placeholder="' + esc(fd.placeholder || '') + '">';
    } else if (fd.type === 'code') {
      inner = '<div class="code-tabs"><button class="code-tab' + (n.subtype === 'nodejs' ? ' active' : '') + '" data-action="code-tab" data-lang="nodejs">Node.js</button>' +
        '<button class="code-tab' + (n.subtype === 'python' ? ' active' : '') + '" data-action="code-tab" data-lang="python">Python</button></div>' +
        '<input type="hidden" name="codeLang" value="' + esc(n.subtype) + '">' +
        '<div class="code-editor"><div class="code-editor-head"><span>' + esc(n.subtype === 'python' ? 'script.py' : 'transform.js') + '</span><span>可编辑 · 原型不真实执行</span></div>' +
        '<textarea class="textarea" name="code" id="codeEditor">' + esc(val || CODE_SAMPLES[n.subtype]) + '</textarea></div>' +
        '<div id="codeOutputBox"></div>';
    } else {
      inner = input(fd.key, val, fd.placeholder);
    }
    return '<div class="field" data-field-key="' + esc(fd.key) + '"' + attrs + '><label>' + esc(fd.label) + (fd.required ? ' <span style="color:var(--danger)">*</span>' : '') + '</label>' + inner +
      (fd.hint ? '<div class="field-hint">' + esc(fd.hint) + '</div>' : '') + '</div>';
  }
  function applyFieldVisibility() {
    var drawer = document.getElementById('appDrawerOverlay');
    if (!drawer) return;
    drawer.querySelectorAll('[data-show-key]').forEach(function (wrap) {
      var sel = drawer.querySelector('[name="' + wrap.getAttribute('data-show-key') + '"]');
      var allowed = wrap.getAttribute('data-show-in').split(',');
      wrap.style.display = sel && allowed.indexOf(sel.value) >= 0 ? '' : 'none';
    });
  }
  function openNodeConfig(n) {
    var schema = Proto.schemaFor(n);
    var body = '<div class="info-box" style="margin-bottom:14px">' + Proto.icon('info', 14) + ' ' + esc(schema.note || '请配置节点属性。') + '</div>' +
      field('节点名称', input('nodeTitle', n.title));
    if (n.type === 'code') body += '<div class="info-box warning">' + Proto.icon('shield', 14) + ' 生产环境将在独立进程/容器中执行用户代码，并启用模块白名单、资源上限与超时；原型仅演示界面。</div>';
    body += schema.fields.map(function (fd) { return renderSchemaField(fd, n); }).join('');
    body += '<div class="info-box" style="margin-top:14px">' + Proto.icon('info', 14) + ' 节点 ID：<span class="mono">' + esc(n.id) + '</span> · 单击选中后可拖动，按 Delete 删除</div>';

    var footer = '<button class="btn btn-danger" data-action="delete-node">' + Proto.icon('trash', 14) + ' 删除节点</button>' +
      (n.type === 'code' ? '<button class="btn" data-action="code-test">' + Proto.icon('play', 14) + ' 测试运行</button>' : '') +
      '<button class="btn btn-primary" data-action="save-node" data-id="' + esc(n.id) + '">' + Proto.icon('save', 14) + ' 保存配置</button>';
    openDrawer({ title: schema.title, sub: n.title, body: body, footer: footer, wide: n.type === 'code' || schema.fields.length > 6 });
    applyFieldVisibility();
    document.querySelectorAll('#appDrawerOverlay select').forEach(function (s) {
      s.addEventListener('change', applyFieldVisibility);
    });
  }
  function nodeSubtitle(n) {
    var c = n.config || {};
    if (n.type === 'trigger' && n.subtype === 'schedule') {
      if (c.mode === 'seconds') return '每 ' + (c.everySeconds || '30') + ' 秒';
      if (c.mode === 'cron') return 'Cron · ' + (c.cron || '未设置');
      return '每 ' + (c.everyMinutes || (c.cron ? '5' : '5')) + ' 分钟';
    }
    if (n.type === 'trigger') return '按需执行';
    if (n.type === 'code') return (n.subtype === 'python' ? 'Python' : 'Node.js') + ' · 已配置';
    if (n.type === 'logic') return c.expression || c.strategy || n.subtype.toUpperCase();
    var ref = c.datasource || c.connection;
    var refText = ref ? sourceLabel(ref) : '';
    if (n.subtype === 'feishu_bitable') return (c.operation || '新增记录') + ' · ' + (c.tableId || '多维表格');
    if (n.type === 'output') return refText || c.table || c.url || c.webhook || n.subtype.toUpperCase();
    return c.nodeId || refText || c.table || c.topic || c.collection || c.startAddress || c.url || n.subtype.toUpperCase();
  }

  /* ---------- 变量配置 ---------- */
  function varRows() {
    var list = state.varTab === 'variables' ? state.variables : state.credentials;
    list = list.filter(function (v) { return state.varScope === 'all' || v.scope === state.varScope || (state.varScope === 'global' && v.scope === 'global'); });
    if (!list.length) return '<tr><td colspan="7" class="empty">没有匹配的记录</td></tr>';
    return list.map(function (v) {
      var cred = state.varTab === 'credentials';
      var shown = state.revealed[v.id];
      var val = cred ? (shown ? esc(v.value) : '••••••••••••') : esc(v.value);
      return '<tr><td class="strong mono">' + esc(v.name) + '</td><td class="mono">' + val +
        (cred ? ' <button class="btn btn-ghost btn-xs" data-action="toggle-secret" data-id="' + esc(v.id) + '">' + Proto.icon(shown ? 'eyeOff' : 'eye', 13) + '</button>' : '') + '</td>' +
        '<td>' + (cred ? esc(v.type) : badge(v.scope === 'global' ? 'purple' : v.scope === 'prod' ? 'info' : 'gray', v.scope)) + '</td>' +
        '<td class="tiny muted">' + esc(v.desc) + '</td>' +
        '<td>' + (cred ? badge('success', 'AES-256 加密') : '—') + '</td>' +
        '<td class="tiny muted">' + esc(v.updated) + '</td>' +
        '<td><div class="row-actions"><button class="btn btn-xs btn-ghost" data-action="edit-var" data-id="' + esc(v.id) + '">' + Proto.icon('edit', 12) + '</button>' +
        '<button class="btn btn-xs btn-ghost" data-action="delete-var" data-id="' + esc(v.id) + '">' + Proto.icon('trash', 12) + '</button></div></td></tr>';
    }).join('');
  }
  function renderVariables(page) {
    page.innerHTML = '<div class="page-head"><div><h1 class="page-title">变量配置</h1><p class="page-desc">集中管理环境变量与敏感凭据，支持按环境作用域隔离</p></div>' +
      '<div class="page-actions"><span class="badge badge-success">' + Proto.icon('shield', 13) + ' 凭据 AES-256 加密存储（原型示意）</span>' +
      '<button class="btn btn-primary" data-action="add-var">' + Proto.icon('plus', 15) + ' 新建' + (state.varTab === 'variables' ? '变量' : '凭据') + '</button></div></div>' +
      '<div class="card"><div class="card-body"><div class="tabs">' +
      '<button class="tab ' + (state.varTab === 'variables' ? 'active' : '') + '" data-action="switch-vartab" data-tab="variables">变量 (' + state.variables.length + ')</button>' +
      '<button class="tab ' + (state.varTab === 'credentials' ? 'active' : '') + '" data-action="switch-vartab" data-tab="credentials">凭据 (' + state.credentials.length + ')</button></div>' +
      '<div class="filter-bar"><span class="tiny muted">作用域</span>' +
      select('varScope', [{ v: 'all', t: '全部' }, { v: 'global', t: '全局' }, { v: 'prod', t: '生产 (prod)' }, { v: 'test', t: '测试 (test)' }], state.varScope).replace('class="select"', 'class="select" id="varScope"') +
      '<span class="tiny muted">变量在流程中通过 <code class="mono">$vars.NAME</code> 或 <code class="mono">$env.NAME</code> 引用</span></div>' +
      '<table class="table"><thead><tr><th>名称</th><th>值</th><th>' + (state.varTab === 'variables' ? '作用域' : '类型') + '</th><th>描述</th><th>安全</th><th>更新时间</th><th>操作</th></tr></thead><tbody id="varTableBody">' + varRows() + '</tbody></table></div></div>';
    document.getElementById('varScope').addEventListener('change', function (e) { state.varScope = e.target.value; document.getElementById('varTableBody').innerHTML = varRows(); });
  }
  function openVarDrawer(v) {
    var cred = state.varTab === 'credentials', isEdit = !!v;
    var body = '<input type="hidden" name="id" value="' + esc(v ? v.id : '') + '">' +
      field('名称', input('name', v && v.name, 'TEMP_THRESHOLD')) +
      field('值', input('value', v && v.value, cred ? '••••••••' : '85')) +
      field('作用域', select('scope', [{ v: 'global', t: '全局' }, { v: 'prod', t: '生产 (prod)' }, { v: 'test', t: '测试 (test)' }], v ? v.scope : 'global')) +
      (cred ? field('凭据类型', select('ctype', ['Bearer Token', '数据库密码', 'API Key', 'Scram-SHA-256', '其他'], v && v.type)) : '') +
      field('描述', input('desc', v && v.desc));
    if (cred) body += '<div class="info-box">' + Proto.icon('shield', 14) + ' 保存后值将显示为打码状态，并标记 AES-256 加密（原型不执行真实加密）。</div>';
    openDrawer({
      title: (isEdit ? '编辑' : '新建') + (cred ? '凭据' : '变量'),
      body: body,
      footer: '<button class="btn" data-action="close-drawer">取消</button><button class="btn btn-primary" data-action="save-var" data-id="' + esc(v ? v.id : '') + '">保存</button>'
    });
  }

  /* ---------- 执行日志 ---------- */
  function filteredExecs() {
    var f = state.execFilter, q = f.q.toLowerCase();
    return state.executions.filter(function (e) {
      return (f.flow === 'all' || e.flowId === f.flow) && (f.status === 'all' || e.status === f.status) &&
        (f.trigger === 'all' || e.trigger === f.trigger) && (!q || (e.short + e.flowName + e.id).toLowerCase().indexOf(q) >= 0);
    });
  }
  function execRowsHTML() {
    var list = filteredExecs();
    if (!list.length) return '<div class="empty">没有匹配的执行记录</div>';
    return list.map(function (e) {
      return '<div class="exec-row ' + (state.selectedExecId === e.id ? 'active' : '') + '" data-action="select-exec" data-id="' + esc(e.id) + '">' +
        '<div class="exec-row-top"><span class="exec-id">' + esc(e.short) + '</span>' + execStatus(e.status) + '</div>' +
        '<div class="exec-flow"><span>' + esc(e.flowName) + '</span>' + badge(e.trigger === '定时' ? 'info' : 'gray', e.trigger) + '</div>' +
        '<div class="exec-meta"><span>' + esc(e.start) + '</span><span>耗时 ' + esc(e.duration) + '</span></div>' +
        (e.status === 'error' ? '<button class="btn btn-xs" style="margin-top:8px;color:var(--purple);border-color:#DDD6FE" data-action="ai-diagnose" data-id="' + esc(e.id) + '">' + Proto.icon('sparkles', 12) + ' AI 诊断</button>' : '') +
        '</div>';
    }).join('');
  }
  function execDetailHTML() {
    var e = state.executions.filter(function (x) { return x.id === state.selectedExecId; })[0];
    if (!e) return '<div class="card"><div class="card-body empty">选择一条执行记录查看详情</div></div>';
    var kv = [['运行 ID', e.id], ['触发方式', e.trigger], ['开始时间', e.start], ['结束时间', e.end], ['总耗时', e.duration], ['采集条数', e.records ? e.records.toLocaleString() + ' 条' : '—']]
      .map(function (p) { return '<div class="detail-kv"><div class="k">' + p[0] + '</div><div class="v">' + esc(p[1]) + '</div></div>'; }).join('');
    var timeline = e.nodes.map(function (n, i) {
      var icon = n.status === 'success' ? 'ok' : n.status === 'error' ? 'fail' : n.status === 'running' ? 'running' : '';
      var st = { success: badge('success', '成功'), error: badge('danger', '失败'), running: badge('warning', '运行中'), waiting: badge('gray', '等待'), skipped: badge('gray', '跳过') }[n.status];
      var key = e.id + '-' + i;
      var io = '';
      if (state.ioOpen[key]) {
        io = '<div class="io-block"><div class="muted" style="margin-bottom:4px">输入</div>' + esc(JSON.stringify(n.input || {}, null, 2)) +
          '</div><div class="io-block"><div class="muted" style="margin-bottom:4px">输出</div>' + esc(n.output ? JSON.stringify(n.output, null, 2) : '（无输出）') + '</div>' +
          (n.status === 'error' && e.error ? '<div class="error-box"><div class="error-title">' + Proto.icon('alert', 13) + ' ' + esc(e.errorType || 'Runtime Error') + '</div><div class="error-text">' + esc(e.error) + '</div></div>' : '');
      }
      return '<div class="tl-item"><span class="tl-dot ' + icon + '"></span>' +
        '<div class="tl-head">' + esc(n.title) + st + '<span class="tiny muted" style="margin-left:auto">' + esc(n.duration) + '</span></div>' +
        '<div class="tl-sub">' + (n.status === 'error' ? '失败节点：' + esc(n.title) : '节点执行详情') + '</div>' +
        '<button class="io-toggle" data-action="toggle-io" data-key="' + esc(key) + '">' + (state.ioOpen[key] ? '收起' : '查看输入 / 输出') + ' ' + Proto.icon(state.ioOpen[key] ? 'chevronDown' : 'chevronRight', 12) + '</button>' + io + '</div>';
    }).join('');
    return '<div class="card exec-detail"><div class="card-head"><h3 class="card-title">运行详情 · ' + esc(e.short) + '</h3><div>' +
      (e.status === 'error' ? '<button class="btn btn-sm" style="color:var(--purple);border-color:#DDD6FE" data-action="ai-diagnose" data-id="' + esc(e.id) + '">' + Proto.icon('sparkles', 14) + ' AI 智能诊断</button>' : '') +
      '<button class="btn btn-sm" data-action="goto-flow" data-flow-id="' + esc(e.flowId) + '">查看流程</button></div></div>' +
      '<div class="detail-grid">' + kv + '</div>' +
      (e.status === 'error' ? '<div style="padding:12px 16px 0"><div class="error-box" style="margin:0"><div class="error-title">' + Proto.icon('alert', 13) + ' 失败信息 · ' + esc(e.failedNode || '') + '</div><div class="error-text">' + esc(e.error) + '</div></div></div>' : '') +
      '<div class="card-head" style="margin-top:6px"><h3 class="card-title">节点执行时间线</h3><span class="tiny muted">展开节点查看输入 / 输出 JSON</span></div>' +
      '<div class="timeline">' + timeline + '</div></div>';
  }
  function renderExecutions(page) {
    var f = state.execFilter;
    page.innerHTML = '<div class="page-head"><div><h1 class="page-title">执行日志</h1><p class="page-desc">手动与定时执行的完整日志，支持按节点排查与 AI 诊断</p></div>' +
      '<div class="page-actions"><button class="btn" data-action="export-exec">' + Proto.icon('download', 15) + ' 导出日志</button></div></div>' +
      '<div class="card"><div class="card-body"><div class="filter-bar">' +
      select('execFlow', [{ v: 'all', t: '全部流程' }].concat(state.flows.map(function (x) { return { v: x.id, t: x.name }; })), f.flow).replace('class="select"', 'class="select" id="execFlow"') +
      select('execStatus', [{ v: 'all', t: '全部状态' }, { v: 'success', t: '成功' }, { v: 'error', t: '失败' }, { v: 'running', t: '运行中' }], f.status).replace('class="select"', 'class="select" id="execStatus"') +
      select('execTrigger', [{ v: 'all', t: '全部触发器' }, { v: 'manual', t: '手动' }, { v: 'schedule', t: '定时' }], f.trigger).replace('class="select"', 'class="select" id="execTrigger"') +
      '<input class="input search" id="execSearch" placeholder="搜索运行 ID / 流程名" value="' + esc(f.q) + '">' +
      '<button class="btn btn-primary btn-sm" data-action="filter-exec">' + Proto.icon('search', 14) + ' 查询</button>' +
      '<button class="btn btn-sm" data-action="reset-exec">重置</button></div></div></div>' +
      '<div class="exec-layout mt14"><div class="card"><div class="card-head"><h3 class="card-title">执行记录</h3><span class="tiny muted" id="execCount"></span></div>' +
      '<div class="exec-list" id="execList">' + execRowsHTML() + '</div></div><div id="execDetail">' + execDetailHTML() + '</div></div>';
    ['execFlow', 'execStatus', 'execTrigger'].forEach(function (id) {
      document.getElementById(id).addEventListener('change', function (e) {
        var key = { execFlow: 'flow', execStatus: 'status', execTrigger: 'trigger' }[id];
        var v = e.target.value;
        if (key === 'trigger' && v !== 'all') v = v === 'manual' ? '手动' : '定时';
        state.execFilter[key] = v; refreshExecList();
      });
    });
    document.getElementById('execSearch').addEventListener('input', function (e) { state.execFilter.q = e.target.value; refreshExecList(); });
    refreshExecList();
  }
  function refreshExecList() {
    var l = document.getElementById('execList');
    if (l) l.innerHTML = execRowsHTML();
    var c = document.getElementById('execCount');
    if (c) c.textContent = filteredExecs().length + ' 条记录';
  }
  function refreshExecDetail() {
    var d = document.getElementById('execDetail');
    if (d) d.innerHTML = execDetailHTML();
  }

  /* ---------- AI 助手页 ---------- */
  function renderAI(page) {
    var sessions = [
      { t: '温度采集流程生成', s: '刚刚 · 流程编排上下文' },
      { t: 'OEE 日报失败诊断', s: '今天 08:12 · 执行日志上下文' },
      { t: '传感器异常分析', s: '今天 14:25 · 数据看板上下文' }
    ].map(function (x, i) {
      return '<div class="ai-session ' + (i === 0 ? 'active' : '') + '"><div class="ai-session-title">' + esc(x.t) + '</div><div class="ai-session-sub">' + esc(x.s) + '</div></div>';
    }).join('');
    if (!state.aiPageMessages.length) state.aiPageMessages.push({ role: 'ai', html: Proto.AI.greeting({ kind: 'global' }) });
    var msgs = state.aiPageMessages.map(function (m) { return Proto.AI.messageHTML(m.role, m.html); }).join('');
    page.innerHTML = '<div class="page-head"><div><h1 class="page-title">AI 助手</h1><p class="page-desc">自然语言生成流程、诊断执行失败、分析数据异常（原型为模拟交互）</p></div>' +
      '<div class="page-actions">' + badge('purple', 'LLM 接入将在架构阶段讨论') + '</div></div>' +
      '<div class="ai-layout"><div class="card ai-sessions"><div class="palette-title" style="margin:6px 6px 10px">历史会话</div>' + sessions +
      '<div class="info-box" style="margin:10px 4px;font-size:11px">' + Proto.icon('info', 13) + ' 当前为关键词 + 预置话术模拟，不调用真实模型。</div></div>' +
      '<div class="card ai-chat"><div class="card-head"><h3 class="card-title">对话</h3><span class="tiny muted">TongLink Copilot · 模拟</span></div>' +
      '<div class="ai-messages" id="aiMessages">' + msgs + '</div>' +
      '<div class="ai-compose"><div class="ai-chips">' +
      '<button class="chip" data-ai-chip="生成一个温度采集流程">生成一个温度采集流程</button>' +
      '<button class="chip" data-ai-chip="诊断最近的失败">诊断最近的失败</button>' +
      '<button class="chip" data-ai-chip="为什么温度突降">为什么温度突降</button></div>' +
      '<div class="ai-input-row"><input class="input" id="aiPageInput" placeholder="输入你的问题…"><button class="btn btn-primary" data-action="ai-page-send">' + Proto.icon('send', 15) + ' 发送</button></div></div></div></div>';
    document.getElementById('aiPageInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') aiPageSend(); });
    document.getElementById('aiMessages').scrollTop = 99999;
  }
  function aiPageSend(preset) {
    var input = document.getElementById('aiPageInput'), list = document.getElementById('aiMessages');
    if (!list) return;
    var text = preset || (input && input.value.trim());
    if (!text) return;
    if (input) input.value = '';
    state.aiPageMessages.push({ role: 'user', html: text });
    list.insertAdjacentHTML('beforeend', Proto.AI.messageHTML('user', text));
    var thinking = document.createElement('div');
    thinking.className = 'msg ai';
    thinking.innerHTML = '<div class="msg-avatar ai">' + Proto.icon('sparkles', 17) + '</div><div class="msg-body"><div class="thinking">正在分析 <i></i><i></i><i></i></div></div>';
    list.appendChild(thinking); list.scrollTop = list.scrollHeight;
    setTimeout(function () {
      if (thinking.parentNode) thinking.parentNode.removeChild(thinking);
      if (!document.getElementById('aiMessages')) return;
      var html = Proto.AI.reply(text, { kind: 'global' });
      state.aiPageMessages.push({ role: 'ai', html: html });
      list.insertAdjacentHTML('beforeend', Proto.AI.messageHTML('ai', html));
      list.scrollTop = list.scrollHeight;
    }, 700);
  }

  /* ---------- 动作处理 ---------- */
  function saveSource() {
    var id = getVal('id'), s = id ? state.sources.filter(function (x) { return x.id === id; })[0] : null;
    var protocol = getVal('protocol');
    var detail = getVal('extra0') || getVal('detail');
    var data = { name: getVal('name'), protocol: protocol, address: getVal('address'), detail: detail, area: getVal('area'), freq: getVal('freq'), status: getVal('status') };
    if (!data.name || !data.address) { toast('请填写数据源名称与连接地址', 'error'); return; }
    if (s) Object.keys(data).forEach(function (k) { s[k] = data[k]; });
    else {
      data.id = 'DS-' + String(state.sources.length + 1).padStart(3, '0');
      data.category = ['OPC UA', 'Modbus TCP', 'MQTT', 'S7comm'].indexOf(protocol) >= 0 ? 'OT' : 'IT';
      data.last = '—'; state.sources.unshift(data);
    }
    closeDrawer(); renderSources(document.getElementById('page'));
    toast(s ? '数据源已更新' : '数据源已创建', 'success');
  }
  function saveNode(id) {
    var f = currentFlow(), n = f.nodes.filter(function (x) { return x.id === id; })[0];
    if (!n) return;
    var schema = Proto.schemaFor(n);
    schema.fields.forEach(function (fd) {
      var el = document.querySelector('#appDrawerOverlay [name="' + fd.key + '"]');
      if (el) n.config[fd.key] = el.value.trim();
    });
    if (n.type === 'code') n.subtype = getVal('codeLang') || n.subtype;
    state.dag.updateNode(id, { title: getVal('nodeTitle') || n.title, subtitle: nodeSubtitle(n) });
    closeDrawer(); toast('节点配置已保存', 'success');
  }
  function saveVar() {
    var id = getVal('id'), cred = state.varTab === 'credentials';
    var list = cred ? state.credentials : state.variables;
    var v = id ? list.filter(function (x) { return x.id === id; })[0] : null;
    var data = { name: getVal('name'), value: getVal('value'), scope: getVal('scope'), desc: getVal('desc') };
    if (cred) data.type = getVal('ctype');
    if (!data.name) { toast('请填写名称', 'error'); return; }
    if (v) Object.keys(data).forEach(function (k) { v[k] = data[k]; });
    else {
      data.id = (cred ? 'C-' : 'V-') + String(list.length + 1).padStart(3, '0');
      data.updated = '2026-11-21 ' + nowTime().slice(0, 5);
      list.unshift(data);
    }
    closeDrawer(); renderVariables(document.getElementById('page'));
    toast((cred ? '凭据' : '变量') + '已保存', 'success');
  }
  function runFlow() {
    var f = currentFlow();
    if (!f || !f.nodes.length) { toast('当前流程没有节点', 'error'); return; }
    state.dag.runAnimation(function () {
      var seq = ++state.runSeq;
      var id = 'EXP-20261121-00' + seq;
      var ex = {
        id: id, short: '#EXP-00' + seq, flowId: f.id, flowName: f.name, trigger: '手动', status: 'success',
        start: '2026-11-21 ' + nowTime(), end: nowTime(), duration: '1.1s', records: 128 + (seq * 7) % 300,
        nodes: f.nodes.map(function (n) {
          return { title: n.title, status: 'success', duration: (0.05 + Math.random() * .5).toFixed(2) + 's', input: { json: { node: n.title } }, output: { json: { ok: true, count: 128 } } };
        })
      };
      state.executions.unshift(ex);
      var fs = state.flowStatus.filter(function (x) { return x.id === f.id; })[0];
      if (fs) { fs.records += ex.records; fs.lastRun = nowTime().slice(0, 5); }
      toast('执行成功，已生成日志 ' + ex.short + ' · 可在执行日志中查看', 'success');
    });
  }
  function loadAIFlow(key) {
    var baseId = { temp: 'flow-1', oee: 'flow-2', alert: 'flow-3', default: 'flow-1' }[key] || 'flow-1';
    var base = state.flows.filter(function (f) { return f.id === baseId; })[0];
    var copy = clone(base);
    copy.id = 'flow-ai-' + Date.now();
    copy.projectId = state.currentProjectId;
    copy.name = base.name + '（AI 生成）';
    copy.lastRun = '—'; copy.records = 0; copy.status = 'paused'; copy.nextRun = '—';
    state.flows.unshift(copy);
    state.flowStatus.unshift({ id: copy.id, projectId: copy.projectId, name: copy.name, status: 'paused', nextRun: '—', records: 0, successRate: '—', lastRun: '—' });
    state.currentFlowId = copy.id;
    Proto.AI.closeDrawer();
    navigate('flows'); render();
    if (state.dag) state.dag.setFlow(currentFlow(), true);
    toast('AI 已生成流程：' + copy.name, 'success');
  }

  Proto.onAIAction = function (action, data) {
    if (action === 'loadFlow') { loadAIFlow(data.template); return; }
    if (action === 'gotoNode') {
      state.currentFlowId = data.flowId || state.currentFlowId;
      state.pendingNodeTitle = data.nodeTitle || '';
      Proto.AI.closeDrawer(); navigate('flows'); render(); return;
    }
    if (action === 'gotoExec') {
      state.selectedExecId = data.execId;
      Proto.AI.closeDrawer(); navigate('executions'); render(); return;
    }
    if (action === 'gotoDashboard') { Proto.AI.closeDrawer(); navigate('dashboard'); return; }
  };

  /* ---------- 全局事件委托 ---------- */
  document.addEventListener('click', function (e) {
    var chip = e.target.closest('[data-ai-chip]');
    if (chip) {
      var t = chip.getAttribute('data-ai-chip');
      if (document.getElementById('aiPageInput')) aiPageSend(t);
      else { var inp = document.getElementById('aiDrawerInput'); if (inp) { inp.value = t; Proto.AI.send('drawer', { kind: 'flows' }); } }
      return;
    }
    var el = e.target.closest('[data-action]');
    if (!el) return;
    var a = el.getAttribute('data-action'), id = el.getAttribute('data-id');

    switch (a) {
      case 'do-login': doLogin(); break;
      case 'fill-user': {
        var u = el.getAttribute('data-username');
        document.getElementById('loginUser').value = u;
        document.getElementById('loginPass').value = (USERS.filter(function (x) { return x.username === u; })[0] || {}).password || '';
        document.getElementById('loginError').textContent = '';
        break;
      }
      case 'skip-login': state.user = USERS[0]; state.loggedIn = true; navigate('dashboard'); break;
      case 'logout':
        state.user = null; state.loggedIn = false;
        Proto.AI.closeDrawer(); closeDrawer();
        navigate('login'); toast('已退出登录', 'info'); break;

      case 'ai-open': Proto.AI.openDrawer({ kind: pageName() }); break;
      case 'ai-close': Proto.AI.closeDrawer(); break;
      case 'ai-send': Proto.AI.send(el.getAttribute('data-target') || 'drawer', { kind: pageName() }); break;
      case 'ai-page-send': aiPageSend(); break;
      case 'ai-generate': Proto.AI.openDrawer({ kind: 'flows', flowId: state.currentFlowId }); break;
      case 'ai-diagnose':
        state.selectedExecId = id;
        refreshExecList(); refreshExecDetail();
        Proto.AI.openDrawer({ kind: 'executions', execId: id });
        setTimeout(function () {
          var inp = document.getElementById('aiDrawerInput');
          if (inp) { inp.value = '诊断这次失败'; Proto.AI.send('drawer', { kind: 'executions', execId: id }); }
        }, 120);
        break;
      case 'anomaly':
        Proto.AI.openDrawer({ kind: 'dashboard' });
        setTimeout(function () {
          var inp = document.getElementById('aiDrawerInput');
          if (inp) { inp.value = '为什么温度突降'; Proto.AI.send('drawer', { kind: 'dashboard' }); }
        }, 120);
        break;

      case 'close-drawer': closeDrawer(); break;
      case 'goto-sources-from-drawer': closeDrawer(); navigate('sources'); break;
      case 'goto-credentials-from-drawer': state.varTab = 'credentials'; closeDrawer(); navigate('variables'); break;
      case 'confirm-cancel': { var m = document.getElementById('confirmModal'); if (m && m.parentNode) m.parentNode.removeChild(m); break; }
      case 'confirm-ok': {
        var m2 = document.getElementById('confirmModal');
        if (m2 && m2._onOk) m2._onOk();
        if (m2 && m2.parentNode) m2.parentNode.removeChild(m2);
        break;
      }

      case 'new-source': openSourceDrawer(null); break;
      case 'edit-source': openSourceDrawer(state.sources.filter(function (x) { return x.id === id; })[0]); break;
      case 'save-source': saveSource(); break;
      case 'test-source': toast('测试连接：' + state.sources.filter(function (x) { return x.id === id; })[0].name + ' → 连接正常（模拟）', 'success'); break;
      case 'test-source-form': toast('连接测试通过（原型模拟，不会真实访问设备）', 'success'); break;
      case 'delete-source':
        confirmModal('删除数据源', '删除后该数据源将无法被流引用，确认继续？', function () {
          state.sources = state.sources.filter(function (x) { return x.id !== id; });
          renderSources(document.getElementById('page')); toast('已删除数据源', 'success');
        });
        break;
      case 'export-sources':
        exportJSON('数据源-' + new Date().toISOString().slice(0, 10) + '.json', { kind: 'tonglink-sources', version: 1, items: state.sources });
        break;
      case 'export-source': {
        var es = state.sources.filter(function (x) { return x.id === id; })[0];
        if (!es) break;
        exportJSON(safeFileName(es.name, '数据源') + '.json', { kind: 'tonglink-source', version: 1, item: es });
        break;
      }
      case 'import-sources':
        importJSON(function (data) {
          var items = asArray(data);
          if (!items || !items.length || !items[0].name || !items[0].protocol) { toast('导入失败：不是有效的数据源配置', 'error'); return; }
          var added = 0;
          items.forEach(function (s) {
            var copy = clone(s);
            copy.id = 'DS-' + String(state.sources.length + 1).padStart(3, '0');
            copy.status = copy.status || 'offline'; copy.last = copy.last || '—';
            state.sources.unshift(copy); added++;
          });
          renderSources(document.getElementById('page')); toast('成功导入 ' + added + ' 个数据源', 'success');
        });
        break;

      case 'select-project':
        state.currentProjectId = id;
        state.currentFlowId = (flowsOfProject(id)[0] || {}).id || '';
        renderFlows(document.getElementById('page')); break;
      case 'new-project':
        openDrawer({
          title: '新建项目', sub: '流程按项目分类管理',
          body: field('项目名称', input('pname', '', '例如：车间B · 设备监控')) +
            field('项目说明', input('pdesc', '', '该项目的采集范围')) +
            field('标识色', select('pcolor', [
              { v: '#2563EB', t: '蓝色' }, { v: '#0F766E', t: '青色' }, { v: '#7C3AED', t: '紫色' }, { v: '#D97706', t: '橙色' }
            ], '#2563EB')),
          footer: '<button class="btn" data-action="close-drawer">取消</button><button class="btn btn-primary" data-action="save-project">创建项目</button>'
        });
        break;
      case 'save-project': {
        var pname = getVal('pname');
        if (!pname) { toast('请填写项目名称', 'error'); return; }
        var project = { id: 'P-' + String(state.projects.length + 1).padStart(3, '0'), name: pname, desc: getVal('pdesc'), color: getVal('pcolor') };
        state.projects.push(project); state.currentProjectId = project.id; state.currentFlowId = '';
        closeDrawer(); renderFlows(document.getElementById('page'));
        toast('项目已创建，接下来可在该项目下新建流程', 'success'); break;
      }
      case 'select-flow': {
        var sf = state.flows.filter(function (x) { return x.id === id; })[0];
        if (sf) state.currentProjectId = sf.projectId || state.currentProjectId;
        state.currentFlowId = id; closeDrawer(); renderFlows(document.getElementById('page')); break;
      }
      case 'new-flow': {
        if (!state.currentProjectId) { toast('请先新建或选择一个项目', 'warning'); break; }
        var f = { id: 'flow-' + Date.now(), projectId: state.currentProjectId, name: '未命名流程', desc: '新建流程', status: 'paused', nextRun: '—', records: 0, successRate: '—', lastRun: '—', nodes: [], connections: [] };
        state.flows.unshift(f); state.flowStatus.unshift({ id: f.id, projectId: f.projectId, name: f.name, status: 'paused', nextRun: '—', records: 0, successRate: '—', lastRun: '—' });
        state.currentFlowId = f.id; renderFlows(document.getElementById('page'));
        toast('已在项目「' + projectName(f.projectId) + '」下创建空白流程', 'success'); break;
      }
      case 'export-flow': {
        var ef = currentFlow();
        if (!ef) { toast('当前项目还没有流程可导出', 'warning'); break; }
        exportJSON(safeFileName(ef.name, '流程') + '.json', { kind: 'tonglink-flow', version: 1, project: state.projects.filter(function (p) { return p.id === ef.projectId; })[0] || null, flow: ef });
        break;
      }
      case 'import-flow':
        importJSON(function (data) {
          var f = data.flow || data;
          if (!f || !f.name || !Array.isArray(f.nodes) || !Array.isArray(f.connections)) { toast('导入失败：不是有效的流程配置', 'error'); return; }
          var copy = clone(f);
          copy.id = 'flow-' + Date.now();
          copy.projectId = data.project && data.project.id && state.projects.some(function (p) { return p.id === data.project.id; }) ? data.project.id : state.currentProjectId;
          copy.name = copy.name + '（导入）';
          copy.status = 'paused'; copy.lastRun = '—';
          var map = {}, stamp = Date.now();
          copy.nodes.forEach(function (n) { var old = n.id; n.id = n.id + '-' + stamp; map[old] = n.id; });
          copy.connections.forEach(function (c) { c.id = 'e-' + stamp + '-' + c.id; c.from.nodeId = map[c.from.nodeId] || c.from.nodeId; c.to.nodeId = map[c.to.nodeId] || c.to.nodeId; });
          state.flows.unshift(copy);
          state.flowStatus.unshift({ id: copy.id, projectId: copy.projectId, name: copy.name, status: 'paused', nextRun: '—', records: 0, successRate: '—', lastRun: '—' });
          state.currentFlowId = copy.id; state.currentProjectId = copy.projectId;
          renderFlows(document.getElementById('page'));
          toast('流程已导入到项目「' + projectName(copy.projectId) + '」', 'success');
        });
        break;
      case 'node-manager': openNodeManager(); break;
      case 'install-node': installNode(id); break;
      case 'uninstall-node': uninstallNode(id); break;
      case 'install-node-file': installNodeFromFile(); break;

      case 'flow-run': runFlow(); break;
      case 'flow-save': {
        var f2 = currentFlow(); if (!f2) { toast('当前项目还没有流程', 'warning'); break; } state.dirty = false;
        var hint = document.getElementById('saveHint'); if (hint) hint.textContent = '上次保存：' + nowTime();
        toast('流程「' + f2.name + '」已保存（原型内存态）', 'success'); break;
      }
      case 'zoom-in': if (state.dag) state.dag.zoom(1.15); break;
      case 'zoom-out': if (state.dag) state.dag.zoom(1 / 1.15); break;
      case 'fit-view': if (state.dag) state.dag.fit(); break;
      case 'delete-node':
        if (state.dag && state.dag.removeSelected()) { closeDrawer(); toast('节点已删除', 'success'); }
        else toast('请先在画布中选中节点', 'warning');
        break;
      case 'save-node': saveNode(el.getAttribute('data-id')); break;
      case 'code-tab': {
        var lang = el.getAttribute('data-lang');
        document.querySelectorAll('.code-tab').forEach(function (b) { b.classList.toggle('active', b === el); });
        var hidden = document.querySelector('#appDrawerOverlay [name="codeLang"]'); if (hidden) hidden.value = lang;
        var ta = document.getElementById('codeEditor'); if (ta) ta.value = CODE_SAMPLES[lang];
        break;
      }
      case 'code-test': {
        var box = document.getElementById('codeOutputBox');
        if (box) box.innerHTML = '<div class="code-output">// 模拟执行输出（3 items，耗时 42ms）\n[\n  { "json": { "tag": "PT101", "value": 82.36, "ts": "2026-11-21T15:20:03.812Z" } },\n  { "json": { "tag": "PT102", "value": 78.12, "ts": "2026-11-21T15:20:03.813Z" } },\n  { "json": { "tag": "PT103", "value": 81.05, "ts": "2026-11-21T15:20:03.814Z" } }\n]</div>';
        toast('代码节点测试运行完成（模拟输出）', 'success'); break;
      }

      case 'switch-vartab': state.varTab = el.getAttribute('data-tab'); renderVariables(document.getElementById('page')); break;
      case 'add-var': openVarDrawer(null); break;
      case 'edit-var': {
        var list = state.varTab === 'variables' ? state.variables : state.credentials;
        openVarDrawer(list.filter(function (x) { return x.id === id; })[0]); break;
      }
      case 'save-var': saveVar(); break;
      case 'toggle-secret': state.revealed[id] = !state.revealed[id]; document.getElementById('varTableBody').innerHTML = varRows(); break;
      case 'delete-var':
        confirmModal('删除配置', '确认删除该配置项？引用它的流程可能执行失败。', function () {
          if (state.varTab === 'variables') state.variables = state.variables.filter(function (x) { return x.id !== id; });
          else state.credentials = state.credentials.filter(function (x) { return x.id !== id; });
          renderVariables(document.getElementById('page')); toast('已删除', 'success');
        });
        break;

      case 'set-range': state.range = el.getAttribute('data-range'); renderDashboard(document.getElementById('page')); break;
      case 'refresh-dashboard': renderDashboard(document.getElementById('page')); toast('看板数据已刷新', 'success'); break;
      case 'goto-flow': {
        var gf = state.flows.filter(function (x) { return x.id === el.getAttribute('data-flow-id'); })[0];
        state.currentFlowId = el.getAttribute('data-flow-id');
        if (gf && gf.projectId) state.currentProjectId = gf.projectId;
        navigate('flows'); break;
      }
      case 'goto-dashboard': navigate('dashboard'); break;

      case 'select-exec': state.selectedExecId = id; refreshExecList(); refreshExecDetail(); break;
      case 'toggle-io': {
        var k = el.getAttribute('data-key'); state.ioOpen[k] = !state.ioOpen[k]; refreshExecDetail(); break;
      }
      case 'filter-exec': refreshExecList(); toast('查询完成', 'success'); break;
      case 'reset-exec':
        state.execFilter = { flow: 'all', status: 'all', trigger: 'all', q: '' };
        renderExecutions(document.getElementById('page')); break;
      case 'export-exec': toast('已导出执行日志（原型模拟）', 'success'); break;
    }
  });

  document.getElementById('globalSearch').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && this.value.trim()) { toast('搜索「' + this.value.trim() + '」— 原型演示，暂未接入检索', 'info'); }
  });
  document.getElementById('envSelect').addEventListener('change', function () {
    toast('已切换到' + this.options[this.selectedIndex].text + '（原型模拟）', 'info');
  });
  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); document.getElementById('globalSearch').focus(); }
  });

  window.addEventListener('hashchange', render);
  Proto.initIcons();
  if (!location.hash) location.hash = '#/login';
  render();
  document.getElementById('navFlowCount').textContent = state.flows.length;
})();
