/*
 * 设计稿公共外壳：左侧导航 + 顶栏。
 * 页面在 <body> 上声明 data-nav（当前菜单）和 data-crumb（面包屑），
 * 内容写在 <main id="page"> 中，本脚本负责把它放进外壳。
 * 导航分组即《优化文档》O-12 建议的新结构。
 */
(function () {
  var groups = [
    { title: '', items: [['workbench', '工作台', '01-workbench.html']] },
    { title: '销售', items: [
      ['sales-orders', '销售订单', '10-document-list.html'],
      ['shipments', '发货与签收', '13-shipment-sign.html', '3'],
      ['sales-returns', '销售退货', '10-document-list.html']
    ] },
    { title: '采购', items: [
      ['purchase-requisitions', '请购管理', '10-document-list.html'],
      ['purchase-orders', '采购订单', '11-order-editor.html'],
      ['goods-receipts', '采购收货', '10-document-list.html'],
      ['purchase-returns', '采购退货', '10-document-list.html']
    ] },
    { title: '库存', items: [
      ['inventory', '实时库存', '30-inventory.html'],
      ['stock-transfers', '库存调拨', '31-stock-operation.html'],
      ['stock-counts', '库存盘点', '31-stock-operation.html'],
      ['stock-adjustments', '库存调整', '31-stock-operation.html']
    ] },
    { title: '资金与往来', items: [
      ['receipts', '收款登记', '20-money-documents.html'],
      ['payments', '付款登记', '20-money-documents.html'],
      ['refund-requests', '退款申请', '20-money-documents.html'],
      ['account-transfers', '账户转账', '20-money-documents.html'],
      ['settlements', '核销工作台', '21-settlement.html'],
      ['invoice-registrations', '开票登记', '10-document-list.html'],
      ['opening-entries', '往来与资金期初', '20-money-documents.html']
    ] },
    { title: '财务期间', items: [
      ['document-reversals', '单据冲销', '14-command-dialogs.html'],
      ['accounting-periods', '会计期间与关账', '32-period-close.html']
    ] },
    { title: '报表与数据', items: [
      ['reports', '业务报表', '40-report.html'],
      ['file-jobs', '导入与导出', '41-file-jobs.html']
    ] },
    { title: '基础资料', items: [
      ['partners', '往来单位', '50-resource.html'],
      ['products', '商品与 SKU', '53-sku.html'],
      ['product-categories', '商品分类', '50-resource.html'],
      ['units', '计量单位', '50-resource.html'],
      ['warehouses', '仓库管理', '50-resource.html'],
      ['cash-accounts', '结算账户', '50-resource.html'],
      ['income-expense-items', '收支项目', '50-resource.html']
    ] },
    { title: '系统管理', items: [
      ['companies', '公司与参数', '52-company-policy.html'],
      ['users', '用户与角色', '51-users-roles.html'],
      ['audit', '操作审计', '42-audit.html']
    ] }
  ];

  var body = document.body;
  var current = body.getAttribute('data-nav') || '';
  var crumb = body.getAttribute('data-crumb') || '';
  var page = document.getElementById('page');
  if (!page) { return; }

  function el(tag, attrs, text) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) { node.setAttribute(key, attrs[key]); });
    if (text) { node.textContent = text; }
    return node;
  }

  var app = el('div', { 'class': 'app' });
  var sidebar = el('aside', { 'class': 'sidebar' });
  var brand = el('div', { 'class': 'brand' });
  brand.appendChild(el('div', { 'class': 'brand-mark', 'aria-hidden': 'true' }, 'ERP'));
  var brandText = el('div');
  brandText.appendChild(el('strong', {}, '进销存管理'));
  brandText.appendChild(el('div', { 'class': 'small muted' }, '华东商贸 · C01'));
  brand.appendChild(brandText);
  sidebar.appendChild(brand);

  var nav = el('nav', { 'aria-label': '主导航' });
  groups.forEach(function (group) {
    var holder;
    var hasCurrent = group.items.some(function (item) { return item[0] === current; });
    if (group.title) {
      holder = el('details', { 'class': 'nav-group' });
      if (hasCurrent || group.title === '销售') { holder.setAttribute('open', 'open'); }
      holder.appendChild(el('summary', {}, group.title));
    } else {
      holder = el('div', { 'class': 'nav-group' });
    }
    group.items.forEach(function (item) {
      var link = el('a', { 'class': 'nav-link', href: item[2] });
      link.appendChild(el('span', {}, item[1]));
      if (item[3]) { link.appendChild(el('span', { 'class': 'nav-count', 'aria-label': item[3] + ' 张签收超期' }, item[3])); }
      if (item[0] === current) { link.setAttribute('aria-current', 'page'); }
      holder.appendChild(link);
    });
    nav.appendChild(holder);
  });
  sidebar.appendChild(nav);

  var workspace = el('div', { 'class': 'workspace' });
  var top = el('header', { 'class': 'topbar' });
  var crumbNav = el('nav', { 'class': 'crumb', 'aria-label': '面包屑' });
  var parts = crumb.split(' / ');
  parts.forEach(function (part, index) {
    if (index > 0) { crumbNav.appendChild(document.createTextNode(' / ')); }
    crumbNav.appendChild(index === parts.length - 1 ? el('b', {}, part) : document.createTextNode(part));
  });
  top.appendChild(crumbNav);
  var actions = el('div', { 'class': 'topbar-actions' });
  var company = el('button', { 'class': 'btn btn-sm', type: 'button', 'aria-label': '切换公司，当前为华东商贸 C01' }, '华东商贸 · C01 ▾');
  var bell = el('button', { 'class': 'icon-btn', type: 'button', 'aria-label': '待办提醒，5 条' });
  bell.innerHTML = '<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/></svg><span class="dot"></span>';
  var account = el('button', { 'class': 'btn btn-sm btn-text', type: 'button', 'aria-label': '账户菜单：修改密码、退出登录' });
  account.innerHTML = '<span class="avatar" aria-hidden="true">李</span><span style="color:var(--text)">李娜</span>';
  actions.appendChild(company);
  actions.appendChild(bell);
  actions.appendChild(account);
  top.appendChild(actions);

  workspace.appendChild(top);
  page.parentNode.removeChild(page);
  page.classList.add('page');
  workspace.appendChild(page);
  app.appendChild(sidebar);
  app.appendChild(workspace);
  body.insertBefore(app, body.firstChild);
})();
