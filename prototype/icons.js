/* 内联 SVG 图标库：零依赖、file:// 下可用、currentColor 跟随文字颜色 */
(function () {
  window.Proto = window.Proto || {};

  var P = {
    dashboard: ['<rect x="3" y="3" width="7" height="9" rx="1.5"/>', '<rect x="14" y="3" width="7" height="5" rx="1.5"/>', '<rect x="14" y="12" width="7" height="9" rx="1.5"/>', '<rect x="3" y="16" width="7" height="5" rx="1.5"/>'],
    database: ['<ellipse cx="12" cy="5" rx="8" ry="3"/>', '<path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/>', '<path d="M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>'],
    workflow: ['<rect x="2.5" y="3" width="7" height="5" rx="1.2"/>', '<rect x="14.5" y="3" width="7" height="5" rx="1.2"/>', '<rect x="8.5" y="16" width="7" height="5" rx="1.2"/>', '<path d="M6 8v3.5a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V8"/>', '<path d="M12 13.5V16"/>'],
    terminal: ['<rect x="2.5" y="4" width="19" height="16" rx="2"/>', '<path d="m6.5 9 3 3-3 3"/>', '<path d="M12 15h5"/>'],
    key: ['<circle cx="8" cy="15" r="4"/>', '<path d="m11 12 8-8 2 2-2 2 1.5 1.5-2 2L16 9l-3 3"/>'],
    sparkles: ['<path d="m12 3 1.7 4.8L18.5 9.5l-4.8 1.7L12 16l-1.7-4.8L5.5 9.5l4.8-1.7L12 3Z"/>', '<path d="m18.5 15 .8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z"/>', '<path d="M4.5 16.5 5 18l1.5.5L5 19l-.5 1.5L4 19l-1.5-.5L4 18l.5-1.5Z"/>'],
    play: ['<path d="M7 4.5 19 12 7 19.5V4.5Z" stroke-linejoin="round"/>'],
    save: ['<path d="M5 3h11l3 3v15H5V3Z"/>', '<path d="M8 3v6h8V3"/>', '<rect x="8" y="14" width="8" height="4"/>'],
    plus: ['<path d="M12 5v14M5 12h14"/>'],
    search: ['<circle cx="10.5" cy="10.5" r="6.5"/>', '<path d="m15.5 15.5 5 5"/>'],
    edit: ['<path d="M4 20h4l11-11-4-4L4 16v4Z"/>', '<path d="m13.5 6.5 4 4"/>'],
    trash: ['<path d="M4 7h16"/>', '<path d="M9 7V4h6v3"/>', '<path d="M6 7l1 13h10l1-13"/>', '<path d="M10 11v5M14 11v5"/>'],
    check: ['<path d="m4 12 5 5L20 6"/>'],
    close: ['<path d="M6 6l12 12M18 6 6 18"/>'],
    clock: ['<circle cx="12" cy="12" r="9"/>', '<path d="M12 7v5l3.5 2"/>'],
    link: ['<path d="M10 13a5 5 0 0 0 7.5.5l2-2A5 5 0 0 0 12.5 4l-1.2 1.2"/>', '<path d="M14 11a5 5 0 0 0-7.5-.5l-2 2A5 5 0 0 0 11.5 20l1.2-1.2"/>'],
    code: ['<path d="m8 8-4 4 4 4"/>', '<path d="m16 8 4 4-4 4"/>', '<path d="m13.5 5-3 14"/>'],
    cpu: ['<rect x="6" y="6" width="12" height="12" rx="2"/>', '<rect x="9.5" y="9.5" width="5" height="5" rx="1"/>', '<path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>'],
    cloud: ['<path d="M7 18h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.6 1.5A3.3 3.3 0 0 0 7 18Z"/>'],
    gear: ['<circle cx="12" cy="12" r="3"/>', '<path d="M12 2.5v2.2M12 19.3v2.2M4.9 4.9l1.6 1.6M17.5 17.5l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.9 19.1l1.6-1.6M17.5 6.5l1.6-1.6"/>'],
    alert: ['<path d="M12 3 2.5 20h19L12 3Z" stroke-linejoin="round"/>', '<path d="M12 9v5"/>', '<circle cx="12" cy="17" r=".9" fill="currentColor" stroke="none"/>'],
    info: ['<circle cx="12" cy="12" r="9"/>', '<path d="M12 11v5"/>', '<circle cx="12" cy="7.8" r=".9" fill="currentColor" stroke="none"/>'],
    chart: ['<path d="M4 20h16"/>', '<path d="M6 20v-6M11 20V7M16 20v-9M21 20V4"/>'],
    filter: ['<path d="M3 5h18l-7 8v6l-4 2v-8L3 5Z"/>'],
    eye: ['<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/>', '<circle cx="12" cy="12" r="2.8"/>'],
    eyeOff: ['<path d="M4 4l16 16"/>', '<path d="M9.6 6.1A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-3 3.6"/>', '<path d="M6.3 8.2A16.7 16.7 0 0 0 2.5 12S6 18.5 12 18.5a9.4 9.4 0 0 0 3.4-.6"/>'],
    zoomIn: ['<circle cx="10.5" cy="10.5" r="6.5"/>', '<path d="M15.5 15.5 21 21M10.5 7.5v6M7.5 10.5h6"/>'],
    zoomOut: ['<circle cx="10.5" cy="10.5" r="6.5"/>', '<path d="M15.5 15.5 21 21M7.5 10.5h6"/>'],
    fit: ['<path d="M9 3H3v6M15 3h6v6M9 21H3v-6M15 21h6v-6"/>'],
    send: ['<path d="m3 11 18-8-8 18-2.5-7.5L3 11Z"/>'],
    refresh: ['<path d="M20 11a8 8 0 0 0-14.3-4.9L3 9"/>', '<path d="M3 4v5h5"/>', '<path d="M4 13a8 8 0 0 0 14.3 4.9L21 15"/>', '<path d="M21 20v-5h-5"/>'],
    chevronDown: ['<path d="m6 9 6 6 6-6"/>'],
    chevronRight: ['<path d="m9 6 6 6-6 6"/>'],
    bolt: ['<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"/>'],
    server: ['<rect x="3" y="3" width="18" height="7" rx="2"/>', '<rect x="3" y="14" width="18" height="7" rx="2"/>', '<path d="M7 6.5h.01M7 17.5h.01M11 6.5h6M11 17.5h6"/>'],
    api: ['<path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3"/>', '<rect x="7" y="8" width="10" height="8" rx="2"/>', '<path d="M10 12h4"/>'],
    arrowRight: ['<path d="M4 12h16M14 6l6 6-6 6"/>'],
    robot: ['<rect x="4" y="8" width="16" height="12" rx="3"/>', '<circle cx="9" cy="14" r="1.1" fill="currentColor" stroke="none"/>', '<circle cx="15" cy="14" r="1.1" fill="currentColor" stroke="none"/>', '<path d="M12 4v4M9 20v2M15 20v2"/>'],
    bulb: ['<path d="M9 18h6M10 21h4"/>', '<path d="M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1.1 2h5c.1-.8.5-1.5 1.1-2A6 6 0 0 0 12 3Z"/>'],
    shield: ['<path d="M12 2.5 20 6v6c0 5-3.4 8.4-8 9.5-4.6-1.1-8-4.5-8-9.5V6l8-3.5Z"/>', '<path d="m9 12 2 2 4-4"/>'],
    more: ['<circle cx="5" cy="12" r="1.3" fill="currentColor" stroke="none"/>', '<circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none"/>', '<circle cx="19" cy="12" r="1.3" fill="currentColor" stroke="none"/>'],
    layers: ['<path d="m12 3 9 5-9 5-9-5 9-5Z"/>', '<path d="m3 13 9 5 9-5"/>'],
    activity: ['<path d="M3 12h4l3-8 4 16 3-8h4"/>'],
    download: ['<path d="M12 3v12M7 10l5 5 5-5"/>', '<path d="M4 20h16"/>'],
    upload: ['<path d="M12 16V4M7 9l5-5 5 5"/>', '<path d="M4 20h16"/>'],
    stop: ['<rect x="6" y="6" width="12" height="12" rx="2"/>'],
    branch: ['<circle cx="6" cy="5" r="2"/>', '<circle cx="18" cy="7" r="2"/>', '<circle cx="18" cy="17" r="2"/>', '<path d="M6 7v6a4 4 0 0 0 4 4h6M8 7h6a4 4 0 0 1 4 4v4"/>'],
    webhook: ['<path d="M9 7a3 3 0 1 1 4.5 2.6L16 17"/>', '<circle cx="6.5" cy="17" r="3"/>', '<circle cx="17.5" cy="17" r="3"/>'],
    gauge: ['<path d="M4 18a9 9 0 1 1 16 0"/>', '<path d="m12 14 4-5"/>', '<circle cx="12" cy="14" r="1.4" fill="currentColor" stroke="none"/>'],
    table: ['<rect x="3" y="4" width="18" height="16" rx="2"/>', '<path d="M3 9h18M3 14.5h18M9 9v11M15 9v11"/>'],
    package: ['<path d="m12 3 9 5-9 5-9-5 9-5Z"/>', '<path d="M3 8v8l9 5 9-5V8"/>', '<path d="M12 13v8"/>']
  };

  Proto.icons = P;

  Proto.icon = function (name, size, cls) {
    var d = P[name] || P.info;
    var s = size || 18;
    return '<svg class="icon ' + (cls || '') + '" width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + d.join('') + '</svg>';
  };

  Proto.initIcons = function () {
    var mark = document.getElementById('brandMark');
    if (mark) mark.innerHTML = Proto.icon('layers', 22);
    ['topSearchIcon', 'envIcon', 'aiFabIcon'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.innerHTML = Proto.icon(id === 'topSearchIcon' ? 'search' : id === 'envIcon' ? 'cloud' : 'sparkles', id === 'aiFabIcon' ? 24 : 16);
    });
    document.querySelectorAll('[data-icon]').forEach(function (el) {
      el.innerHTML = Proto.icon(el.getAttribute('data-icon'), 19);
    });
  };
})();
