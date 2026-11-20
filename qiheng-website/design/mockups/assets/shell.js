/*
 * 设计稿 v2 外壳：模块栏 + 菜单栏 + 顶栏 + 页签栏。
 * 页面在 <body> 上声明：
 *   data-module  当前模块编码
 *   data-menu    当前二级菜单编码
 *   data-tabs    页签，格式“标题|链接|标记”用分号分隔；标记 current 表示当前页，unsaved 表示未保存
 * 页面内容写在 <main id="page"> 中，本脚本负责把它放进外壳的内容区。
 * 模块与菜单即《UI 设计规范》2.0.0 第 4.2 节。
 */
(function () {
  var icons = {
    home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
    sales: '<path d="M3 4h2l2.4 11.2a1 1 0 0 0 1 .8h8.9a1 1 0 0 0 1-.8L20 8H6.2"/><circle cx="9" cy="20" r="1.3"/><circle cx="17" cy="20" r="1.3"/>',
    buy: '<path d="M4 7h16l-1.5 12.5a1 1 0 0 1-1 .9H6.5a1 1 0 0 1-1-.9z"/><path d="M9 7V5.5a3 3 0 0 1 6 0V7"/>',
    stock: '<path d="M3 8 12 3l9 5-9 5z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/>',
    wms: '<path d="M3 21V9l9-5 9 5v12"/><path d="M7 21v-8h10v8"/><path d="M7 17h10"/>',
    mfg: '<path d="M3 21V11l6 3V11l6 3V7l6 3v11z"/><path d="M7 18h2M12 18h2M17 18h1"/>',
    finance: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 10h18"/><path d="M7 15h4"/>',
    crm: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7"/><path d="M18 14a6 6 0 0 1 3.5 6"/>',
    approve: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
    analysis: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6"/><path d="M12 17h.01"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>',
    fold: '<path d="M4 6h16M4 12h10M4 18h16"/><path d="m19 9-3 3 3 3"/>',
    down: '<path d="m6 9 6 6 6-6"/>',
    building: '<rect x="4" y="3" width="16" height="18" rx="1"/><path d="M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2"/>'
  };
  function svg(name, cls) { return '<svg class="i' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true">' + (icons[name] || '') + '</svg>'; }

  var modules = [
    { key: 'home', name: '工作台', icon: 'home', menus: [['工作台', 'workbench', '01-workbench.html'], ['经营驾驶舱', 'dashboard', '#']] },
    { key: 'sales', name: '销售', icon: 'sales', menus: [['销售订单', 'sales-orders', '02-document-list.html'], ['发货与签收', 'shipments', '#', '3'], ['销售退货', 'sales-returns', '#'], ['价格表', 'price-lists', '#']] },
    { key: 'buy', name: '采购', icon: 'buy', menus: [['请购', 'requisitions', '#'], ['采购订单', 'purchase-orders', '#'], ['采购收货', 'goods-receipts', '#'], ['采购退货', 'purchase-returns', '#']] },
    { key: 'stock', name: '库存', icon: 'stock', menus: [['实时库存', 'inventory', '#'], ['批次与序列号', 'batches', '#'], ['调拨', 'transfers', '#'], ['盘点', 'counts', '#'], ['库存调整', 'adjustments', '#'], ['其它出入库', 'misc', '#'], ['来料检验', 'qc', '#']] },
    { key: 'wms', name: '仓储', icon: 'wms', menus: [['作业任务', 'wms-tasks', '#'], ['波次拣货', 'waves', '#'], ['复核装箱', 'packs', '#'], ['移库', 'moves', '#'], ['库位', 'locations', '#'], ['标签打印', 'labels', '#']] },
    { key: 'mfg', name: '生产', icon: 'mfg', menus: [['物料清单', 'boms', '#'], ['生产工单', 'work-orders', '#'], ['领料退料', 'issues', '#'], ['完工入库', 'mfg-receipts', '#'], ['成本计算', 'mfg-cost', '#']] },
    { key: 'finance', name: '财务', icon: 'finance', groups: [
      ['资金', [['收款', 'receipts', '#'], ['付款', 'payments', '#'], ['退款', 'refunds', '#'], ['账户转账', 'transfers-cash', '#']]],
      ['往来', [['核销', 'settlements', '#'], ['开票与数电发票', 'invoices', '#'], ['往来期初', 'openings', '#'], ['信用与催收', 'credit', '#']]],
      ['总账', [['凭证', 'vouchers', '#'], ['账簿', 'ledgers', '#'], ['财务报表', 'statements', '#']]],
      ['期间', [['会计期间与关账', 'periods', '#'], ['单据冲销', 'reversals', '#']]]
    ] },
    { key: 'crm', name: '客户', icon: 'crm', menus: [['线索', 'clues', '#'], ['客户', 'customers', '#'], ['公海', 'pool', '#'], ['商机', 'businesses', '#'], ['合同', 'contracts', '#'], ['回款', 'crm-receivables', '#'], ['客服工单', 'tickets', '#'], ['门户账号', 'portal-users', '#']] },
    { key: 'approve', name: '审批', icon: 'approve', badge: '5', menus: [['待办', 'todo', '05-approval-center.html', '5'], ['已办', 'done', '#'], ['我的申请', 'mine', '#'], ['抄送我的', 'cc', '#'], ['流程设计', 'models', '#']] },
    { key: 'analysis', name: '分析', icon: 'analysis', menus: [['业务报表', 'reports', '#'], ['导入导出', 'file-jobs', '#'], ['预警中心', 'alerts', '#', '2'], ['AI 助手', 'ai', '#'], ['知识库', 'kb', '#']] },
    { key: 'settings', name: '设置', icon: 'settings', groups: [
      ['基础资料', [['往来单位', 'partners', '#'], ['商品与 SKU', 'skus', '#'], ['仓库', 'warehouses', '#'], ['结算账户', 'cash-accounts', '#']]],
      ['组织与权限', [['部门与岗位', 'departments', '06-master-data.html'], ['用户与角色', 'users', '#'], ['数据范围', 'scopes', '#']]],
      ['系统', [['公司与参数', 'companies', '#'], ['编码规则', 'code-rules', '#'], ['自定义字段', 'custom-fields', '#'], ['打印模板', 'print', '#'], ['登录与安全', 'security', '#'], ['开放平台', 'open', '#'], ['系统运维', 'ops', '#']]]
    ] }
  ];

  var body = document.body;
  var moduleKey = body.getAttribute('data-module') || 'home';
  var menuKey = body.getAttribute('data-menu') || '';
  var tabsSpec = body.getAttribute('data-tabs') || '工作台|01-workbench.html|current';
  var page = document.getElementById('page');
  if (!page) { return; }

  function el(tag, attrs, html) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) { node.setAttribute(key, attrs[key]); });
    if (html != null) { node.innerHTML = html; }
    return node;
  }

  var app = el('div', { 'class': 'app' });

  // 模块栏
  var rail = el('nav', { 'class': 'rail', 'aria-label': '模块' });
  rail.appendChild(el('div', { 'class': 'logo', 'aria-label': '柒衡 ERP' }, '<img src="assets/logo.svg" alt="" width="36" height="36">'));
  modules.forEach(function (m) {
    var first = m.menus ? m.menus[0][2] : m.groups[0][1][0][2];
    var link = el('a', { 'class': 'rail-item', href: first, 'aria-current': m.key === moduleKey ? 'true' : 'false', title: m.name });
    link.innerHTML = (m.badge ? '<span class="dot-badge" data-count="' + m.badge + '" style="display:inline-flex">' + svg(m.icon) + '</span>' : svg(m.icon)) + '<span>' + m.name + '</span>';
    rail.appendChild(link);
  });
  rail.appendChild(el('div', { 'class': 'spacer' }));
  rail.appendChild(el('a', { 'class': 'rail-item', href: '#', title: '帮助中心' }, svg('help') + '<span>帮助</span>'));
  app.appendChild(rail);

  // 菜单栏
  var current = modules.filter(function (m) { return m.key === moduleKey; })[0] || modules[0];
  var menu = el('nav', { 'class': 'menu', id: 'module-menu', 'aria-label': current.name + '菜单' });
  menu.appendChild(el('div', { 'class': 'menu-title' }, current.name));
  function addLinks(items) {
    items.forEach(function (item) {
      var a = el('a', { href: item[2] }, '<span>' + item[0] + '</span>' + (item[3] ? '<span class="badge">' + item[3] + '</span>' : ''));
      if (item[1] === menuKey) { a.setAttribute('aria-current', 'page'); }
      menu.appendChild(a);
    });
  }
  if (current.groups) {
    current.groups.forEach(function (g) { menu.appendChild(el('div', { 'class': 'menu-group' }, g[0])); addLinks(g[1]); });
  } else { addLinks(current.menus); }
  app.appendChild(menu);

  // 主区
  var main = el('div', { 'class': 'main' });
  var top = el('header', { 'class': 'topbar' });
  top.innerHTML =
    '<button class="btn btn-icon" type="button" data-fold>' + svg('fold') + '</button>' +
    '<div class="search" role="button" tabindex="0" aria-label="全局搜索，快捷键 Ctrl K">' + svg('search') + '<span>搜索菜单、单号、客户、商品</span><kbd>Ctrl K</kbd></div>' +
    '<div class="right">' +
      '<button class="company" type="button" aria-label="切换公司，当前华东商贸">' + svg('building') + '<span>华东商贸 · C01</span>' + svg('down') + '</button>' +
      '<button class="btn btn-icon dot-badge" data-count="5" type="button" aria-label="消息通知，5 条未读">' + svg('bell') + '</button>' +
      '<button class="btn btn-icon" type="button" aria-label="帮助">' + svg('help') + '</button>' +
      '<button class="user" type="button" aria-label="用户菜单：李娜"><span class="avatar" aria-hidden="true">李</span><span>李娜</span>' + svg('down') + '</button>' +
    '</div>';
  main.appendChild(top);

  var tabs = el('nav', { 'class': 'tabsbar', 'aria-label': '已打开的页面' });
  tabsSpec.split(';').forEach(function (spec, index) {
    var parts = spec.split('|');
    var a = el('a', { href: parts[1] || '#' });
    var flags = (parts[2] || '').split(',');
    if (flags.indexOf('current') >= 0) { a.setAttribute('aria-current', 'page'); }
    a.innerHTML = '<span' + (flags.indexOf('unsaved') >= 0 ? ' class="unsaved" style="display:inline-flex;align-items:center;gap:6px"' : '') + '>' + parts[0] + '</span>' + (index > 0 ? '<span class="close" aria-label="关闭">✕</span>' : '');
    tabs.appendChild(a);
  });
  main.appendChild(tabs);

  var content = el('div', { 'class': 'content' + (page.querySelector('.footer-bar') ? ' has-footer' : '') });
  while (page.firstChild) { content.appendChild(page.firstChild); }
  main.appendChild(content);
  app.appendChild(main);

  page.parentNode.replaceChild(app, page);
  var fold = top.querySelector('[data-fold]');
  var narrow = window.matchMedia('(max-width: 1199px)');
  var mobile = window.matchMedia('(max-width: 767px)');
  var drawer = el('dialog', { 'class': 'nav-drawer', id: 'nav-drawer', 'aria-labelledby': 'nav-title' },
    '<header><strong id="nav-title">导航</strong><button type="button" class="btn btn-text" autofocus>关闭导航</button></header>');
  var drawerBody = el('div', { 'class': 'nav-drawer-body' });
  drawer.appendChild(drawerBody);
  app.appendChild(drawer);

  function syncToggle() {
    var expanded = narrow.matches ? drawer.open : !app.classList.contains('menu-collapsed');
    fold.setAttribute('aria-expanded', String(expanded));
    fold.setAttribute('aria-controls', narrow.matches ? drawer.id : menu.id);
    fold.setAttribute('aria-label', narrow.matches ? '打开导航' : (expanded ? '收起菜单栏' : '展开菜单栏'));
  }

  function syncLayout() {
    if (!narrow.matches && drawer.open) { drawer.close(); }
    if (mobile.matches) { drawerBody.prepend(rail); }
    else { app.prepend(rail); }
    if (narrow.matches) { drawerBody.appendChild(menu); }
    else { app.insertBefore(menu, main); }
    syncToggle();
  }

  function toggleNavigation() {
    if (narrow.matches) {
      if (drawer.open) { drawer.close(); }
      else { drawer.showModal(); }
    } else { app.classList.toggle('menu-collapsed'); }
    syncToggle();
  }

  drawer.querySelector('button').addEventListener('click', function () { drawer.close(); });
  drawer.addEventListener('close', syncToggle);
  drawer.addEventListener('click', function (event) {
    if (event.target === drawer) { drawer.close(); }
  });
  fold.addEventListener('click', toggleNavigation);
  narrow.addEventListener('change', syncLayout);
  mobile.addEventListener('change', syncLayout);
  document.addEventListener('keydown', function (event) {
    if (event.key === '[' && !event.ctrlKey && !event.altKey && !event.metaKey && !event.repeat &&
        !event.target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) {
      event.preventDefault();
      toggleNavigation();
    }
  });
  syncLayout();
})();
