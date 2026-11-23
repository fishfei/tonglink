/* AI 模拟：关键词意图路由 + 预置话术 + 抽屉交互（不接真实模型） */
(function () {
  window.Proto = window.Proto || {};
  var panel = null;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function nl2br(s) { return s.replace(/\n/g, '<br>'); }

  Proto.AI = {
    intent: function (text) {
      if (/生成|创建|搭建|编排|做一个|新增.*流程|修改.*流程/.test(text)) return 'generate';
      if (/诊断|失败|报错|为什么失败|错误|排查/.test(text)) return 'diagnose';
      if (/异常|突降|告警|为什么下降|温度/.test(text)) return 'anomaly';
      return 'generic';
    },

    messageHTML: function (role, html) {
      var isUser = role === 'user';
      return '<div class="msg ' + (isUser ? 'user' : 'ai') + '">' +
        '<div class="msg-avatar ' + (isUser ? 'user' : 'ai') + '">' + Proto.icon(isUser ? 'robot' : 'sparkles', 17) + '</div>' +
        '<div class="msg-body">' + (isUser ? '<p>' + nl2br(esc(html)) + '</p>' : html) + '</div></div>';
    },

    /* 根据意图返回 AI 回复 HTML（可含 data-ai-action 按钮） */
    reply: function (text, ctx) {
      ctx = ctx || {};
      var intent = Proto.AI.intent(text);
      var ai = Proto.DATA.ai;

      if (intent === 'generate') {
        var key = 'default';
        if (/温度|opc|采集/.test(text)) key = 'temp';
        else if (/oee|日报|稼动|modbus/i.test(text)) key = 'oee';
        else if (/告警|阈值|mqtt/i.test(text)) key = 'alert';
        var answer = ai.generateAnswer[key];
        return '<p>' + answer.replace(/^<p>|<\/p>$/g, '') + '</p>' +
          '<p class="tiny muted">flows 模板已准备就绪，点击下方按钮将节点与连线加载到画布。</p>' +
          '<button class="btn btn-primary btn-sm ai-action" data-ai-action="loadFlow" data-template="' + key + '">' + Proto.icon('workflow', 14) + ' 加载到画布</button>';
      }

      if (intent === 'diagnose') {
        var execs = (Proto.state && Proto.state.executions) || Proto.DATA.executions;
        var ex = null;
        if (ctx.execId) ex = execs.filter(function (e) { return e.id === ctx.execId; })[0];
        if (!ex) ex = execs.filter(function (e) { return e.status === 'error'; })[0];
        var kind = ex && /N\/A|float/.test(ex.error || '') ? 'VAL_ERR' : ex && /ECONN/.test(ex.error || '') ? 'ECONN' : 'DEFAULT';
        var d = ai.diagnoses[kind];
        var evidence = d.evidence.map(function (x) { return '<li>' + x + '</li>'; }).join('');
        var steps = d.steps.map(function (x) { return '<li>' + x + '</li>'; }).join('');
        return '<div class="diag-card"><h5>' + esc(d.title) + '　<span class="badge badge-danger">' + esc(d.level) + '优先级</span></h5>' +
          '<p><strong>根因：</strong>' + d.cause + '</p></div>' +
          '<p><strong>证据：</strong></p><ul>' + evidence + '</ul>' +
          '<p><strong>建议修复步骤：</strong></p><ol>' + steps + '</ol>' +
          '<button class="btn btn-primary btn-sm ai-action" data-ai-action="gotoNode" data-flow-id="' + esc(d.flowId) + '" data-node-title="' + esc(d.node) + '">' + Proto.icon('workflow', 14) + ' 定位到问题节点</button>' +
          '<button class="btn btn-sm ai-action" data-ai-action="gotoExec" data-exec-id="' + esc(ex ? ex.id : '') + '">' + Proto.icon('terminal', 14) + ' 查看完整日志</button>';
      }

      if (intent === 'anomaly') {
        var list = ai.anomalyAnswer;
        var hit = list[0];
        var lower = text.toLowerCase();
        for (var i = 0; i < list.length; i++) {
          var matched = list[i].keys.some(function (k) { return lower.indexOf(k.toLowerCase()) >= 0; });
          if (matched) { hit = list[i]; break; }
        }
        return hit.html + '<button class="btn btn-sm ai-action" data-ai-action="gotoDashboard">' + Proto.icon('chart', 14) + ' 回到看板查看</button>';
      }

      return ai.generic;
    },

    greeting: function (ctx) {
      ctx = ctx || {};
      if (ctx.kind === 'flows') return '<p>我已读取当前流程上下文。你可以说「生成一个温度采集流程」，或直接让我修改当前流程的节点。</p>';
      if (ctx.kind === 'executions') return '<p>我已读取当前执行日志上下文。点击「诊断这次失败」或描述现象，我会给出根因与修复建议。</p>';
      if (ctx.kind === 'dashboard') return '<p>我已读取看板数据。最近有一次明显的温度异常，可以问我「为什么温度突降」。</p>';
      return '<p>你好，我是 TongLink Studio 的 AI 助手。我可以帮你生成采集流程、诊断执行失败、分析数据异常。</p>';
    },

    /* 右侧抽屉形式的 AI 助手 */
    openDrawer: function (ctx) {
      Proto.AI.closeDrawer();
      ctx = ctx || { kind: 'global' };
      var overlay = document.createElement('div');
      overlay.className = 'overlay';
      overlay.id = 'aiDrawerOverlay';
      var label = { flows: '流程编排', executions: '执行日志', dashboard: '数据看板', global: '全局' }[ctx.kind] || '全局';
      overlay.innerHTML = '<div class="drawer wide ai-panel">' +
        '<div class="drawer-head"><div><div class="drawer-title">AI 助手</div><div class="drawer-sub">当前上下文：' + esc(label) + ' · 模拟对话</div></div>' +
        '<button class="btn btn-ghost btn-sm" data-action="ai-close">' + Proto.icon('close', 16) + '</button></div>' +
        '<div class="ai-messages" id="aiDrawerMessages">' + Proto.AI.messageHTML('ai', Proto.AI.greeting(ctx)) + '</div>' +
        '<div class="ai-compose"><div class="ai-chips">' +
        '<button class="chip" data-ai-chip="生成一个温度采集流程">生成一个温度采集流程</button>' +
        '<button class="chip" data-ai-chip="诊断最近的失败">诊断最近的失败</button>' +
        '<button class="chip" data-ai-chip="为什么温度突降">为什么温度突降</button></div>' +
        '<div class="ai-input-row"><input class="input" id="aiDrawerInput" placeholder="描述你的需求…"><button class="btn btn-primary" data-action="ai-send" data-target="drawer">' + Proto.icon('send', 15) + ' 发送</button></div></div>' +
        '</div>';
      document.getElementById('overlayRoot').appendChild(overlay);
      panel = overlay;

      overlay.addEventListener('click', function (e) {
        if (e.target === overlay) Proto.AI.closeDrawer();
        var chip = e.target.closest('[data-ai-chip]');
        if (chip) { document.getElementById('aiDrawerInput').value = chip.getAttribute('data-ai-chip'); Proto.AI.send('drawer', ctx); }
      });
      var input = document.getElementById('aiDrawerInput');
      input.addEventListener('keydown', function (e) { if (e.key === 'Enter') Proto.AI.send('drawer', ctx); });
      setTimeout(function () { input.focus(); }, 80);
      return overlay;
    },

    closeDrawer: function () {
      if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
      panel = null;
    },

    /* 发送消息：target = 'drawer' 或 'page' */
    send: function (target, ctx) {
      var inputId = target === 'drawer' ? 'aiDrawerInput' : 'aiPageInput';
      var listId = target === 'drawer' ? 'aiDrawerMessages' : 'aiMessages';
      var input = document.getElementById(inputId), list = document.getElementById(listId);
      if (!input || !list) return;
      var text = input.value.trim();
      if (!text) return;
      input.value = '';
      list.insertAdjacentHTML('beforeend', Proto.AI.messageHTML('user', text));
      var thinking = document.createElement('div');
      thinking.className = 'msg ai';
      thinking.innerHTML = '<div class="msg-avatar ai">' + Proto.icon('sparkles', 17) + '</div><div class="msg-body"><div class="thinking">正在分析 <i></i><i></i><i></i></div></div>';
      list.appendChild(thinking);
      list.scrollTop = list.scrollHeight;
      setTimeout(function () {
        if (thinking.parentNode) thinking.parentNode.removeChild(thinking);
        if (!document.getElementById(listId)) return;
        list.insertAdjacentHTML('beforeend', Proto.AI.messageHTML('ai', Proto.AI.reply(text, ctx)));
        list.scrollTop = list.scrollHeight;
      }, 700);
    }
  };

  /* AI 回复中的动作按钮统一交给 app.js 注册的处理器 */
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-ai-action]');
    if (btn && Proto.onAIAction) {
      e.preventDefault();
      Proto.onAIAction(btn.getAttribute('data-ai-action'), btn.dataset);
    }
  });
})();
