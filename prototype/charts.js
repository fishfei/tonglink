/* 手写 SVG 图表：无 CDN、无构建、file:// 下可用 */
(function () {
  window.Proto = window.Proto || {};
  var uid = 0;

  Proto.fmt = function (n) {
    if (n >= 10000) return (n / 10000).toFixed(n >= 100000 ? 0 : 1) + '万';
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  };

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* KPI 卡右下角迷你趋势线 */
  Proto.charts = {
    sparkline: function (values, color) {
      var w = 96, h = 34, pad = 3;
      var min = Math.min.apply(null, values), max = Math.max.apply(null, values);
      var range = (max - min) || 1;
      var pts = values.map(function (v, i) {
        var x = pad + i * (w - pad * 2) / (values.length - 1);
        var y = h - pad - ((v - min) / range) * (h - pad * 2);
        return [x, y];
      });
      var line = pts.map(function (p, i) { return (i ? 'L' : 'M') + p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' ');
      var area = line + ' L' + pts[pts.length - 1][0].toFixed(1) + ',' + h + ' L' + pts[0][0].toFixed(1) + ',' + h + ' Z';
      var gid = 'sparkGrad' + (++uid);
      var c = color || '#2563EB';
      return '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none" aria-hidden="true">' +
        '<defs><linearGradient id="' + gid + '" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0%" stop-color="' + c + '" stop-opacity=".24"/><stop offset="100%" stop-color="' + c + '" stop-opacity="0"/>' +
        '</linearGradient></defs><path d="' + area + '" fill="url(#' + gid + ')"/>' +
        '<path d="' + line + '" fill="none" stroke="' + c + '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    },

    /* 双折线图（成功 / 失败），支持异常点标注 */
    lineChart: function (labels, series, opts) {
      opts = opts || {};
      var W = 760, H = 280, L = 46, R = 16, T = 16, B = 34;
      var pw = W - L - R, ph = H - T - B;
      var all = [];
      series.forEach(function (s) { all = all.concat(s.values); });
      var max = Math.max.apply(null, all) * 1.15 || 1;
      var n = labels.length;
      function X(i) { return L + (n === 1 ? 0 : i * pw / (n - 1)); }
      function Y(v) { return T + ph - (v / max) * ph; }

      var grid = '';
      for (var g = 0; g <= 4; g++) {
        var gy = T + g * ph / 4;
        var gv = Math.round(max * (1 - g / 4));
        grid += '<line x1="' + L + '" y1="' + gy + '" x2="' + (W - R) + '" y2="' + gy + '" stroke="#E5E7EB" stroke-width="1"/>' +
          '<text x="' + (L - 8) + '" y="' + (gy + 4) + '" text-anchor="end" font-size="11" fill="#9CA3AF">' + gv + '</text>';
      }
      var xlabels = labels.map(function (lb, i) {
        return '<text x="' + X(i) + '" y="' + (H - 10) + '" text-anchor="middle" font-size="11" fill="#9CA3AF">' + esc(lb) + '</text>';
      }).join('');

      var paths = series.map(function (s) {
        var d = s.values.map(function (v, i) { return (i ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1); }).join(' ');
        var dots = s.values.map(function (v, i) {
          return '<circle cx="' + X(i).toFixed(1) + '" cy="' + Y(v).toFixed(1) + '" r="3" fill="' + s.color + '"><title>' + esc(s.name + ' ' + labels[i] + '：' + v + ' 次') + '</title></circle>';
        }).join('');
        return '<path d="' + d + '" fill="none" stroke="' + s.color + '" stroke-width="2.2" stroke-linejoin="round"/>' + dots;
      }).join('');

      var anomaly = '';
      if (opts.anomaly && series[0]) {
        var ai = opts.anomaly.x, av = opts.anomaly.value;
        if (ai < n && av != null) {
          var ax = X(ai), ay = Y(av);
          anomaly = '<line x1="' + ax + '" y1="' + T + '" x2="' + ax + '" y2="' + (T + ph) + '" stroke="#DC2626" stroke-width="1" stroke-dasharray="4 4" opacity=".55"/>' +
            '<circle class="anomaly-point" data-action="anomaly" cx="' + ax + '" cy="' + ay + '" r="8" fill="none" stroke="#DC2626" stroke-width="2"><title>点击查看 AI 异常分析</title></circle>' +
            '<circle class="anomaly-point" data-action="anomaly" cx="' + ax + '" cy="' + ay + '" r="3.5" fill="#DC2626"/>';
        }
      }
      return '<div class="chart-wrap"><svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="执行趋势折线图">' +
        grid + paths + anomaly + xlabels + '</svg></div>';
    },

    /* 柱状图：各流程采集记录数 */
    barChart: function (items) {
      var W = 520, H = 280, L = 46, R = 16, T = 22, B = 46;
      var pw = W - L - R, ph = H - T - B;
      var max = Math.max.apply(null, items.map(function (i) { return i.value; })) * 1.18 || 1;
      var slot = pw / items.length, bw = Math.min(54, slot * .52);
      var grid = '';
      for (var g = 0; g <= 4; g++) {
        var gy = T + g * ph / 4;
        grid += '<line x1="' + L + '" y1="' + gy + '" x2="' + (W - R) + '" y2="' + gy + '" stroke="#E5E7EB"/>' +
          '<text x="' + (L - 8) + '" y="' + (gy + 4) + '" text-anchor="end" font-size="11" fill="#9CA3AF">' + Proto.fmt(Math.round(max * (1 - g / 4))) + '</text>';
      }
      var bars = items.map(function (it, i) {
        var h = Math.max(2, (it.value / max) * ph);
        var x = L + slot * i + (slot - bw) / 2, y = T + ph - h;
        var gid = 'barGrad' + (++uid);
        return '<defs><linearGradient id="' + gid + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#3B82F6"/><stop offset="100%" stop-color="#93C5FD"/></linearGradient></defs>' +
          '<rect class="chart-bar" x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + bw.toFixed(1) + '" height="' + h.toFixed(1) + '" rx="4" fill="url(#' + gid + ')"><title>' + esc(it.name + '：' + it.value.toLocaleString() + ' 条') + '</title></rect>' +
          '<text x="' + (x + bw / 2).toFixed(1) + '" y="' + (y - 6).toFixed(1) + '" text-anchor="middle" font-size="11" font-weight="600" fill="#334155">' + Proto.fmt(it.value) + '</text>' +
          '<text x="' + (x + bw / 2).toFixed(1) + '" y="' + (H - 14) + '" text-anchor="middle" font-size="11" fill="#6B7280">' + esc(it.name.length > 5 ? it.name.slice(0, 5) : it.name) + '</text>';
      }).join('');
      return '<div class="chart-wrap"><svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="各流程采集记录数柱状图">' + grid + bars + '</svg></div>';
    },

    /* 环形图：执行状态分布 */
    donutChart: function (segments) {
      var S = 200, cx = 100, cy = 100, r = 68, sw = 26;
      var total = segments.reduce(function (a, b) { return a + b.value; }, 0) || 1;
      var circ = 2 * Math.PI * r, acc = 0;
      var arcs = segments.map(function (s) {
        var len = s.value / total * circ;
        var el = '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="' + s.color + '" stroke-width="' + sw + '"' +
          ' stroke-dasharray="' + len.toFixed(2) + ' ' + (circ - len).toFixed(2) + '" stroke-dashoffset="' + (-acc).toFixed(2) + '"' +
          ' transform="rotate(-90 ' + cx + ' ' + cy + ')"><title>' + esc(s.name + '：' + s.value + ' 次') + '</title></circle>';
        acc += len;
        return el;
      }).join('');
      return '<div class="chart-wrap"><svg viewBox="0 0 ' + S + ' ' + S + '" role="img" aria-label="执行状态分布环形图">' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="#F1F5F9" stroke-width="' + sw + '"/>' + arcs +
        '<text x="' + cx + '" y="' + (cy - 2) + '" text-anchor="middle" font-size="26" font-weight="700" fill="#1F2937">' + total.toLocaleString() + '</text>' +
        '<text x="' + cx + '" y="' + (cy + 20) + '" text-anchor="middle" font-size="12" fill="#9CA3AF">总执行次数</text></svg></div>';
    }
  };
})();
