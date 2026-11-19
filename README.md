# 柒衡 ERP 2.0 / Qiheng ERP 2.0

**面向商贸、批发与轻制造团队的开源进销存和业财管理系统**

柒衡 ERP 2.0 把采购入库、仓库拣货、销售签收和财务核销串在一起。采购、销售、仓库和财务人员可以在同一套系统里查库存、订单进度和待收待付金额。

[![预发布版 2.0.0-rc.1](https://img.shields.io/badge/Prerelease-2.0.0--rc.1-2563eb)](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1)
![Java 21](https://img.shields.io/badge/Java-21-b45309)
![Vue 3](https://img.shields.io/badge/Vue-3-16845b)
![MySQL 8.0.46](https://img.shields.io/badge/MySQL-8.0.46-276b9a)
[![AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-475569)](LICENSE)

[已实现的功能](#功能概览) · [业务流程](#业务流程) · [技术架构](#技术架构) · [快速开始](#快速开始) · [使用入门](#使用入门) · [项目文档](#项目文档) · [English](#english)

## 功能概览

下表列出 2.0 候选版已经实现的功能。仓储作业可按仓启用，AI 助手需要配置独立服务。

| 模块 | 主要功能 |
| --- | --- |
| **基础资料** | 管理企业、公司、部门、仓库、库区库位、客户、供应商、商品 SKU、计量单位、价格和结算账户。 |
| **销售管理** | 销售订单审批、库存预留、发货签收、销售退货、退款和开票登记。订单详情按权限展示履约进度、本单已核销金额和未清余额。 |
| **采购管理** | 从请购转采购订单，完成审批、来料质检、收货及入库。支持采购退货、供应商退款、付款和应付核销。 |
| **库存与追溯** | 按仓库查看在库、可用、预留和冻结数量。支持调拨、盘点、库存调整、按批次或序列号追溯，以及保质期与过期冻结。 |
| **仓储作业（WMS）** | 按仓启用上架、波次拣货、复核装箱、移库、补货和库位盘点。支持标签打印和 PDA 扫码，扫错任务指定的库位或批次时可在原步骤重扫。 |
| **往来与资金** | 导入期初，管理应收应付、收付款、核销和账户转账，查询账龄、资金流水及未清金额。支持信用额度、催收及坏账处理。 |
| **财务总账** | 按公司维护人民币账套。支持凭证生成、双人复核、记账、辅助核算、账簿、三张财务报表、期间结转和关账。 |
| **生产管理** | 管理多版本 BOM、替代料和生产工单，记录领料、退料、分批完工入库及短交复核。月末核算期末在制、费用分摊与生产成本差异。 |
| **客户关系与门户** | 维护线索、商机、合同和跟进记录，支持从合同分批下销售订单。客户可在独立门户查看所属订单、物流、发票和对账单。 |
| **审批与平台** | 支持流程设计、多级审批、会签、条件分支、转办、超时提醒、公告和站内信。按企业、公司、仓库、部门与角色校验数据访问，提供 MFA、单点登录、操作审计和签名开放接口。 |
| **报表、打印与预警** | 查看购销、库存、资金、财务与生产指标，支持后台导入导出、自定义打印模板和批量 PDF。按配置扫描逾期应收、库存异常等业务预警。 |
| **AI 助手（可选）** | 在授权范围内查业务数据、检索内部知识并显示来源，可以解释发货或关账被阻挡的原因。业务查询工具采用只读授权。 |

[用户使用手册](docs/产品/用户使用手册.md) · [业务规则](docs/产品/业务规则.md) · [分模块详细设计](docs/设计/详细设计/README.md)

## 业务流程

- **采购到付款**。请购 → 采购审批 → 质检与收货 → 入库与确认应付 → 付款核销。
- **销售到收款**。销售审批 → 拣货发货 → 签收与确认应收 → 收款核销。
- **仓储与生产协作**。收货可生成上架任务，销售发货可走波次拣货和装箱复核。生产工单按 BOM 领料，完工品入库后参与库存和月末成本核算。

部署后，员工从管理工作台 `/` 办理单据，客户从 `/portal.html` 登录查单，仓库人员从 `/mobile/` 启动 H5 扫码作业。

## 技术架构

Nginx 提供 HTTPS 入口、静态资源与 API 代理。ERP 采用模块化单体，API 与后台 Worker 可按部署规模分开运行。AI 服务按需独立部署。

| 层级 | 技术及用途 | 源码或配置 |
| --- | --- | --- |
| **管理端与门户** | Vue 3、TypeScript、Vite 和 Element Plus 构建管理端。Vue Router、Pinia 和 Axios 负责路由、状态与 API 调用，ECharts 展示工作台和经营图表。 | [`erp-web/`](erp-web/) |
| **移动端** | uni-app、Vue 3、TypeScript 和 Wot Design Uni 提供 PDA 作业、扫码与移动审批界面，支持 H5 与 Android PDA 构建。 | [`erp-mobile/`](erp-mobile/) |
| **业务 API 与后台任务** | Java 21、Spring Boot 4.1.2 和 Maven。对外提供 REST API，Worker 处理导入导出、通知、定时任务及通过 Outbox 投递的异步业务事件。 | [`erp-server/`](erp-server/) |
| **流程引擎** | Flowable 8.0.0 执行审批流程，前端通过 bpmn-js 设计 BPMN 流程，FormCreate 渲染动态审批表单。业务单据保留自己的状态规则和服务端权限校验。 | [工作流设计](docs/设计/详细设计/02-工作流.md) |
| **数据库与会话** | MySQL 8.0.46 保存单据、库存流水和财务账本。Flyway 管理数据库迁移。Redis 支持会话、缓存及跨实例 WebSocket 事件广播。 | [数据库设计](docs/设计/数据库设计.md) |
| **文件与集成** | 私有附件、导出与 PDF 支持 S3 兼容对象仓。开放接口和 Webhook 使用签名，后台任务记录执行状态。 | [接口设计](docs/设计/接口设计.md) |
| **AI 服务（可选）** | 独立服务使用 Spring AI 2.0.1 做模型接入、用 Apache Tika 解析文档，提供知识检索与 Wiki。可按需使用 OpenSearch，业务查询调用 ERP 的只读工具。 | [`erp-ai/`](erp-ai/) · [AI 设计](docs/设计/详细设计/04-AI.md) |
| **部署与可观测性** | 提供 Nginx、Docker Compose、systemd 和生产配置模板。可选监控组件包含 Prometheus、OpenTelemetry、Grafana、Tempo 和 Loki。 | [`deploy/`](deploy/) · [运维手册](docs/运维/运维手册.md) |
| **测试与交付** | 后端使用 JUnit，前端与移动端使用 Vitest，Playwright 验证真实浏览器业务流程。GitHub Actions 执行检查、构建及回归。 | [测试报告](docs/测试/测试报告.md) · [构建与运维脚本](scripts/) |

## 快速开始

### 安装 2.0 候选版

从 [2.0.0-rc.1 发布页](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1) 下载 2.0 部署包和 SHA-256 校验文件。校验并解压后，按 [部署说明](docs/运维/部署说明.md) 配置 JDK 21、MySQL 8.0.46、Redis 及入口代理，再初始化企业和管理员。

### 在 Windows 从源码运行

准备 Git、PowerShell 7、JDK 21、Maven、Node.js 24、npm 和 Redis，启动 MySQL 8.0.46。首次安装请按 [本机环境说明](docs/运维/环境检查.md) 准备建库与授权账号，在当前终端设置 `DB_USERNAME` 和 `DB_PASSWORD`。以下命令在 PowerShell 中运行，JDK 和 Redis 路径请按本机安装位置填写。

```powershell
git clone --branch v2.0.0-rc.1 --single-branch https://github.com/qingyu6688/qiheng-erp.git
cd qiheng-erp
pwsh -NoProfile -File ./scripts/prepare-local.ps1 -JavaHome 'D:/tools/jdk-21' -RedisDirectory 'D:/tools/redis'
npm --prefix erp-web ci
```

在项目根目录的两个终端分别执行 `pwsh -File ./scripts/project.ps1 server` 和 `pwsh -File ./scripts/project.ps1 web`。打开 [本机工作台](http://127.0.0.1:5173)，默认开发企业编码为 `DEMO`，用户名为 `admin`，初始密码见本机私有 `.env` 的 `ERP_DEV_ADMIN_PASSWORD`。

## 使用入门

1. **选择公司并准备资料**。在顶栏切换到要操作的公司，维护仓库、客户、供应商、商品和结算账户，确认业务日期处于开放的会计期间。
2. **按需导入期初**。已有库存或往来余额时，先导入或录入期初，由有权限的人员复核并过账，再开始做单。
3. **完成一笔采购**。新建并审批采购订单，收货后过账。到库存查看入库数量，到财务查看应付。登记付款后，把实际付款分配给对应应付并核销。
4. **完成一笔销售**。新建并审批销售订单，从授权仓发货并登记签收。财务确认收到款项后，将收款与对应应收核销。回到订单详情核对履约、已核销和未清金额。
5. **按岗位核对结果**。仓库人员查看库存及收发记录，财务人员查看应收应付和资金报表。启用仓储作业后，可用 `/mobile/` 领取任务并扫码。

[完整操作手册](docs/产品/用户使用手册.md) · [移动端操作](erp-mobile/移动端使用说明.md)

## 项目文档

[文档目录](docs/README.md)

| 想了解什么 | 入口 |
| --- | --- |
| 日常操作、业务口径和移动作业 | [用户使用手册](docs/产品/用户使用手册.md) · [业务规则](docs/产品/业务规则.md) · [移动端说明](erp-mobile/移动端使用说明.md) |
| 安装与运维 | [部署说明](docs/运维/部署说明.md) · [运维手册](docs/运维/运维手册.md) |
| 二次开发 | [详细设计](docs/设计/详细设计/README.md) · [接口设计](docs/设计/接口设计.md) · [数据库设计](docs/设计/数据库设计.md) |
| 2.0 发布与验证 | [候选版发布说明](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1) · [构建与回归记录](https://github.com/qingyu6688/qiheng-erp/actions/runs/44252853580) · [更新日志](docs/更新日志.md) |

项目代码采用 [AGPL-3.0-only](LICENSE)，第三方依赖按[各自的许可条款](docs/产品/第三方依赖许可.md)分发。

## English

**Open-source inventory and business finance software for trading, wholesale, and light manufacturing teams**

Qiheng ERP 2.0 connects purchasing, warehouse work, sales fulfillment, and settlement so teams can check stock, order progress, receivables, and payables in one place.

[Implemented features](#features) · [Workflows](#workflows) · [Architecture](#architecture) · [Quick start](#quick-start) · [First transactions](#first-transactions) · [Documentation](#documentation) · [返回中文](#功能概览)

### Features

The modules below are implemented in the 2.0 release candidate. Warehouse tasks can be enabled per warehouse. The optional AI assistant requires a separate service.

| Module | What it covers |
| --- | --- |
| **Master data** | Maintain tenants, companies, departments, warehouses, bins, customers, suppliers, SKUs, units of measure, prices, and settlement accounts. |
| **Sales** | Approve orders, reserve stock, ship goods, record delivery, and handle returns, refunds, and invoice registration. See fulfillment progress, receipt allocations, and outstanding balances on each order. |
| **Purchasing** | Convert requisitions into purchase orders, approve purchases, inspect inbound goods, and post receipts. Handle returns, supplier refunds, payments, and payable allocations. |
| **Inventory and traceability** | View on-hand, available, reserved, and blocked quantities by warehouse. Perform transfers, counts, and adjustments. Track lots, serial numbers, and expiration holds. |
| **Warehouse management (WMS)** | Enable putaway, wave picking, packing checks, bin moves, replenishment, and bin counts by warehouse. Print labels and use PDA scanning. An incorrect assigned location or batch can be rescanned in the same step. |
| **Receivables, payables, and cash** | Import opening balances and manage receipts, payments, allocations, and account transfers. Review aging, cash activity, and outstanding amounts. Track credit limits, collections, and bad debts. |
| **General ledger** | Maintain separate CNY ledgers by company. Generate, review, and post vouchers, track auxiliary dimensions, and run ledgers, the three financial statements, and period closing. |
| **Manufacturing** | Manage BOM versions, substitutes, and work orders. Record material issues and returns, partial completions, and reviewed short closure. Calculate WIP, expense allocations, and month-end cost variances. |
| **CRM and customer portal** | Manage leads, opportunities, contracts, and follow-ups. Create sales orders from a contract in batches. Customers can view their own orders, shipments, invoices, and statements. |
| **Approvals and platform** | Design approval flows with multiple stages, joint review, conditional branches, reassignment, and reminders. Includes announcements, inbox, scoped access, MFA, SSO, audit logs, and signed integration APIs. |
| **Reports, printing, and alerts** | View sales, purchasing, inventory, cash, finance, and production metrics. Run background imports and exports, use custom print templates, and generate batch PDFs. Configure alerts for overdue receivables and stock exceptions. |
| **AI assistant (optional)** | Query authorized business data, search internal documents with citations, and explain why a shipment or period close is blocked. ERP query tools have read-only access. |

### Workflows

- **Purchase to payment.** Requisition → purchase approval → inspection and receipt → stock and payable posting → payment allocation.
- **Order to cash.** Sales approval → pick and ship → delivery confirmation and receivable → receipt allocation.
- **Warehouse and production.** Receipts can create putaway tasks. Shipments can go through wave picking and packing checks. Work orders issue materials from the BOM and post finished goods to inventory for month-end costing.

After deployment, staff work in the admin workspace at `/`, customers sign in at `/portal.html`, and warehouse operators scan items through the mobile web app at `/mobile/`.

### Architecture

Nginx serves static assets over HTTPS and proxies API requests. The ERP is a modular monolith with API and Worker processes that can run separately. The optional AI service is deployed independently.

| Layer | Technologies and responsibilities | Source or configuration |
| --- | --- | --- |
| **Admin web and portal** | Vue 3, TypeScript, Vite, and Element Plus. Vue Router, Pinia, and Axios handle navigation, state, and API requests. ECharts powers dashboards. | [`erp-web/`](erp-web/) |
| **Mobile** | uni-app, Vue 3, TypeScript, and Wot Design Uni power warehouse tasks, scanning, and mobile approvals. Builds target H5 and Android PDA assets. | [`erp-mobile/`](erp-mobile/) |
| **Business API and Worker** | Java 21, Spring Boot 4.1.2, and Maven. The REST API handles business requests. Workers process imports, exports, notifications, scheduled jobs, and asynchronous events delivered through the Outbox. | [`erp-server/`](erp-server/) |
| **Workflow engine** | Flowable 8.0.0 executes approvals. The UI uses bpmn-js for BPMN editing and FormCreate for dynamic forms. Business documents retain their own state rules and server-side access checks. | [Workflow design](docs/设计/详细设计/02-工作流.md) |
| **Database and sessions** | MySQL 8.0.46 stores documents, inventory movements, and ledgers. Flyway manages schema changes. Redis supports sessions, caching, and cross-instance WebSocket broadcast. | [Database design](docs/设计/数据库设计.md) |
| **Files and integrations** | Private attachments, exports, and PDFs support S3-compatible object storage. Open APIs and webhooks use signatures, and background jobs record execution status. | [API design](docs/设计/接口设计.md) |
| **AI service (optional)** | A separate service uses Spring AI 2.0.1 for model integration and Apache Tika to parse documents. It supports retrieval, Wiki, and optional OpenSearch. ERP queries use read-only tools. | [`erp-ai/`](erp-ai/) · [AI design](docs/设计/详细设计/04-AI.md) |
| **Deployment and observability** | Nginx, Docker Compose, systemd, and production configuration templates. Optional monitoring components include Prometheus, OpenTelemetry, Grafana, Tempo, and Loki. | [`deploy/`](deploy/) · [Operations manual](docs/运维/运维手册.md) |
| **Testing and delivery** | JUnit covers the backend, Vitest covers web and mobile code, and Playwright exercises browser workflows. GitHub Actions runs checks, builds, and regression tests. | [Test report](docs/测试/测试报告.md) · [Build and operations scripts](scripts/) |

### Quick start

#### Install the 2.0 release candidate

Download the 2.0 deployment ZIP and its SHA-256 file from the [2.0.0-rc.1 release page](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1). Verify the archive before extracting it. Use the [deployment guide](docs/运维/部署说明.md) to configure JDK 21, MySQL 8.0.46, Redis, and the reverse proxy, then initialize a tenant and administrator.

#### Run from source on Windows

Install Git, PowerShell 7, JDK 21, Maven, Node.js 24, npm, and Redis, then start MySQL 8.0.46. For a first installation, follow the [environment guide](docs/运维/环境检查.md) to prepare an account with database creation and grant privileges. Set `DB_USERNAME` and `DB_PASSWORD` in the current shell. Run the commands in PowerShell and substitute your JDK and Redis paths.

```powershell
git clone --branch v2.0.0-rc.1 --single-branch https://github.com/qingyu6688/qiheng-erp.git
cd qiheng-erp
pwsh -NoProfile -File ./scripts/prepare-local.ps1 -JavaHome 'D:/tools/jdk-21' -RedisDirectory 'D:/tools/redis'
npm --prefix erp-web ci
```

From the repository root, run `pwsh -File ./scripts/project.ps1 server` and `pwsh -File ./scripts/project.ps1 web` in separate terminals. Open the [local workspace](http://127.0.0.1:5173). The default development tenant is `DEMO` and the username is `admin`. Use the initial password stored under `ERP_DEV_ADMIN_PASSWORD` in your local private `.env`.

### First transactions

1. **Select a company and set up master data.** Switch companies in the header. Set up a warehouse, customers, suppliers, products, and settlement accounts. Make sure the transaction date falls in an open accounting period.
2. **Enter opening balances if applicable.** Import or enter existing inventory and outstanding balances. Have an authorized user review and post the balances before creating orders.
3. **Complete a purchase.** Create and approve a purchase order, then receive the goods and post the receipt. Check the stock received and the payable. Record the supplier payment and allocate it to that payable.
4. **Complete a sale.** Create and approve a sales order, ship from an authorized warehouse, and record delivery confirmation. After recording the customer receipt, allocate the funds against the receivable. Check fulfillment, allocations, and the outstanding balance on the order.
5. **Check the results.** Review stock and movement history, receivables and payables, and cash activity. Where warehouse tasks are enabled, operators can claim and scan tasks at `/mobile/`.

[Full user guide](docs/产品/用户使用手册.md) · [Mobile guide](erp-mobile/移动端使用说明.md)

### Documentation

[Documentation index](docs/README.md)

The detailed guides are in Chinese.

| Topic | Links |
| --- | --- |
| Daily operations | [User guide](docs/产品/用户使用手册.md) · [Business rules](docs/产品/业务规则.md) · [Mobile guide](erp-mobile/移动端使用说明.md) |
| Deployment and operations | [Deployment guide](docs/运维/部署说明.md) · [Operations manual](docs/运维/运维手册.md) |
| Development | [Technical design](docs/设计/详细设计/README.md) · [API design](docs/设计/接口设计.md) · [Database design](docs/设计/数据库设计.md) |
| 2.0 release and validation | [Release notes](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1) · [Build and regression results](https://github.com/qingyu6688/qiheng-erp/actions/runs/44252853580) · [Changelog](docs/更新日志.md) |

Qiheng ERP is licensed under [AGPL-3.0-only](LICENSE). Third-party dependencies are distributed under [their respective licenses](docs/产品/第三方依赖许可.md).
