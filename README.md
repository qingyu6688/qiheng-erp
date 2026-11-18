# 柒衡 ERP / Qiheng ERP

**面向商贸、批发与轻制造团队的开源进销存和业财管理系统**

把采购入库、仓库拣货、销售签收和财务核销串在一起，在同一套系统里查库存、订单进度和待收待付金额。

[![预发布版 2.0.0-rc.1](https://img.shields.io/badge/Prerelease-2.0.0--rc.1-2563eb)](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1)
![Java 21](https://img.shields.io/badge/Java-21-b45309)
![Vue 3](https://img.shields.io/badge/Vue-3-16845b)
![MySQL 8.0.46](https://img.shields.io/badge/MySQL-8.0.46-276b9a)
[![AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-475569)](LICENSE)

[功能概览](#功能概览) · [业务流程](#业务流程) · [快速开始](#快速开始) · [项目文档](#项目文档) · [English](#english)

## 功能概览

| 模块 | 可以做什么 |
| --- | --- |
| **采购与销售** | 从请购、采购、收货入库，到销售下单、发货签收。支持退货、退款、开票登记和订单履约跟踪。 |
| **库存与仓储** | 查在库、可用和预留数量，管理调拨、盘点、批次与序列号。按仓启用上架、波次拣货、复核装箱和标签打印。 |
| **往来与总账** | 导入期初，管理应收、应付、收付款及核销。按公司维护账套、处理凭证、对账和期间关账。 |
| **生产** | 管理多版本 BOM 和生产工单，记录领退料、分批完工入库与短交关闭，核算月末生产成本。 |
| **客户与经营** | 跟进线索、商机和合同。客户可在门户查看订单、物流与对账单。内部团队可使用经营驾驶舱、打印模板和预警中心。 |
| **审批与权限** | 按企业、公司、仓库、部门和角色控制访问，支持按单据配置审批、经办与复核分离及操作审计。 |

按需启用 AI 助手，可在授权范围内查业务数据、检索内部知识并查看引用，或了解单据被阻挡的原因。

## 业务流程

- **采购到付款**　请购 → 采购审批 → 收货入库 → 确认应付 → 付款核销。
- **销售到收款**　销售审批 → 拣货发货 → 签收确认应收 → 收款核销。订单详情按权限展示履约进度、本单已核销金额和未清余额。

部署后，员工从管理工作台 `/` 办理单据，客户从 `/portal.html` 登录查单，仓库人员可在 `/mobile/` 使用 H5 扫码作业。

## 快速开始

- **体验候选版**。从 [GitHub Releases](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1) 下载部署包和 SHA-256 校验文件，按[部署说明](docs/部署说明.md)准备 JDK 21、MySQL 8.0.46 与 Redis。
- **从源码运行**。按[本机环境说明](docs/环境检查.md)准备开发库和测试库。在项目根目录运行 `npm --prefix erp-web ci`，再用两个终端分别执行 `pwsh -File ./scripts/project.ps1 server` 和 `pwsh -File ./scripts/project.ps1 web`。
- **升级现有 1.x 实例**。先在备份副本上演练升级与回退，再按[升级指南](docs/1.x升级到2.0指南.md)安排停写窗口。

管理端使用 Vue 3、TypeScript、Vite 和 Element Plus，移动端使用 uni-app。后端使用 Java 21、Spring Boot 和 MySQL，Flyway 管理数据库迁移，Redis 支持会话和消息推送。

## 项目文档

| 想了解什么 | 入口 |
| --- | --- |
| 日常操作与业务口径 | [用户使用手册](docs/用户使用手册.md) · [业务规则](docs/业务规则.md) · [移动端说明](erp-mobile/移动端使用说明.md) |
| 安装、升级与运维 | [部署说明](docs/部署说明.md) · [1.x 升级与回退](docs/1.x升级到2.0指南.md) · [运维手册](docs/运维手册.md) |
| 二次开发 | [详细设计](docs/详细设计/README.md) · [接口设计](docs/接口设计.md) · [数据库设计](docs/数据库设计.md) |
| 版本与验证 | [更新日志](docs/更新日志.md) · [测试报告](docs/测试报告.md) · [开发计划](docs/开发计划.md) |

项目代码采用 [AGPL-3.0-only](LICENSE)，第三方依赖按[各自的许可条款](docs/第三方依赖许可.md)分发。

## English

**Open-source inventory and business finance software for trading, wholesale, and light manufacturing teams**

Qiheng ERP connects purchasing, warehouse work, sales fulfillment, and settlement so teams can check stock, order progress, receivables, and payables in one place.

[Features](#features) · [Workflows](#workflows) · [Quick start](#quick-start) · [Documentation](#documentation) · [返回中文](#功能概览)

### Features

| Module | What it covers |
| --- | --- |
| **Purchasing and sales** | Handle requisitions, purchase orders, receipts, sales orders, shipping, and delivery confirmations. Track fulfillment and record returns, refunds, and invoices. |
| **Inventory and warehousing** | Track on-hand, available, and reserved stock. Manage transfers, counts, lots, and serial numbers. Enable putaway, wave picking, packing checks, and label printing by warehouse. |
| **Settlement and accounting** | Import opening balances, track receivables and payables, and allocate receipts and payments. Maintain separate company ledgers, post vouchers, reconcile accounts, and close periods. |
| **Manufacturing** | Maintain BOM versions and work orders. Record material issues and returns, partial completions, and reviewed short closure. Calculate month-end production costs. |
| **Customers and operations** | Track leads, opportunities, and contracts. Customers can access orders, shipment updates, and statements in the portal. Staff can use dashboards, print templates, and alerts. |
| **Approvals and access** | Apply tenant, company, warehouse, department, and role permissions. Configure document approvals, require a separate reviewer for key actions, and keep an audit trail. |

An optional AI assistant can query authorized business data, answer questions with citations from internal documents, and explain why a document is blocked.

### Workflows

- **Purchase to payment** · Requisition → purchase approval → goods receipt → payable → payment allocation.
- **Order to cash** · Sales approval → pick and ship → delivery confirmation → receivable → receipt allocation. Order details show fulfillment progress, allocations, and outstanding balances within the user’s access scope.

After deployment, staff work in the admin workspace at `/`, customers sign in at `/portal.html`, and warehouse operators scan items through the mobile web app at `/mobile/`.

### Quick start

- **Try the release candidate.** Download the package and SHA-256 file from [GitHub Releases](https://github.com/qingyu6688/qiheng-erp/releases/tag/v2.0.0-rc.1). The [deployment guide](docs/部署说明.md) covers setup with JDK 21, MySQL 8.0.46, and Redis.
- **Run from source.** Prepare development and test databases using the [environment guide](docs/环境检查.md). From the repository root, run `npm --prefix erp-web ci`, then start `pwsh -File ./scripts/project.ps1 server` and `pwsh -File ./scripts/project.ps1 web` in separate terminals.
- **Upgrade from 1.x.** Rehearse upgrade and rollback on a restored backup before scheduling the cutover. See the [upgrade guide](docs/1.x升级到2.0指南.md).

The admin UI uses Vue 3, TypeScript, Vite, and Element Plus. The mobile app uses uni-app. The backend uses Java 21, Spring Boot, and MySQL, with Flyway for schema migrations and Redis for sessions and notifications.

### Documentation

The detailed guides are in Chinese.

| Topic | Links |
| --- | --- |
| Daily operations | [User guide](docs/用户使用手册.md) · [Business rules](docs/业务规则.md) · [Mobile guide](erp-mobile/移动端使用说明.md) |
| Deployment and operations | [Deployment guide](docs/部署说明.md) · [Upgrade and rollback](docs/1.x升级到2.0指南.md) · [Operations manual](docs/运维手册.md) |
| Development | [Technical design](docs/详细设计/README.md) · [API design](docs/接口设计.md) · [Database design](docs/数据库设计.md) |
| Releases and testing | [Changelog](docs/更新日志.md) · [Test report](docs/测试报告.md) · [Development plan](docs/开发计划.md) |

Qiheng ERP is licensed under [AGPL-3.0-only](LICENSE). Third-party dependencies are distributed under [their respective licenses](docs/第三方依赖许可.md).
