# 柒衡 ERP / Qiheng ERP

[中文](#中文) · [English](#english)

## 中文

柒衡 ERP 是面向商贸和库存管理团队的进销存与轻量业财系统。采购、销售、仓库和财务人员在同一套单据流程里协作，从期初开账开始，跟踪订单履约、库存成本、应收应付和账户余额。

### 业务功能

| 模块 | 支持的操作 |
| --- | --- |
| 基础资料 | 维护公司、仓库、客户、供应商、商品 SKU、计量单位、价格和结算账户。 |
| 销售 | 建单、审批、发货、签收、退货、退款和开票登记。签收确认应收后，可登记收款并核销。 |
| 采购 | 从请购转采购订单，经审批后收货入库并确认应付。支持采购退货、供应商退款、付款和核销。 |
| 库存 | 查询在库、可用、预留和冻结数量。支持调拨、盘点、库存调整及移动加权成本核算。 |
| 往来与资金 | 导入或录入期初数据，管理应收应付、收付款、资金账户和内部转账。支持单据冲销、八类对账及期间关账、重开。 |
| 财务总账（候选） | 公司独立人民币账套、两套准则样例、七维辅助、手工及业务凭证、三表指标、期末结转；支持信用、催收和坏账，见[M12使用说明](docs/M12财务总账使用说明.md)。 |
| 内部 AI 助手（候选） | 按企业、角色和部门检索内部资料，回答附可核对的引用。知识维护人员可导入资料、调整片段和审核 Wiki，管理员可配置模型、只读工具和用量。见 [M13 使用与验收说明](docs/M13人工智能使用与验收.md)。 |
| 平台二期（候选） | 企业单点登录、按角色强制 MFA、动态字段、编码规则、签名 API 与 Webhook、通用审批和动态表单。见 [M14 使用与验收](docs/M14平台二期使用与验收.md)。 |
| 报表与文件 | 提供库存收发存、销售采购汇总、账龄、资金日记账、毛利等十类报表。支持后台 CSV 导入导出、错误行下载、单据打印预览和私有附件。 |
| 权限与审计 | 按企业、公司和仓库划分数据范围，按角色与操作授权。关键审核支持经办与复核分离，并记录操作审计。管理员可以按企业配置短信、邮箱验证码和微信扫码登录。 |

库存期初 CSV 导入生成草稿，须经审核并过账后才计入库存和成本。各业务状态、金额口径和异常处理见[业务规则](docs/业务规则.md)及[用户使用手册](docs/用户使用手册.md)。

M10 本机迭代增加默认收付款账户、工作台金额与十二个月趋势，以及报表只读备库配置。工作台金额按权限和仓库范围统计，最多缓存五分钟，并显示数据截止时间。实现、验收结果及目标环境事项见 [M10 实施说明](docs/M10功能优化实施说明.md)和[本机验收报告](docs/M10本机验收报告.md)。这些变更尚未正式发布。

### 技术栈与目录

M11 的 Y-01 增加公司部门树、企业共享岗位及按公司保存的用户任职。每家公司独立维护主部门和版本；组织配置不增加业务访问范围，撤销公司权限会同步清理任职及负责人。当前为未发布功能，部署包含新增 V36，操作说明见[用户手册](docs/用户使用手册.md#m11-部门与岗位未发布)，验证范围见[M11 本机验收报告](docs/M11部门岗位本机验收报告.md)。

M11 的 Y-02 继续增加按公司配置的五种角色部门范围，与现有公司、仓库及操作权限取交集。17类单据冻结归属，列表计数、报表、打印附件、旧命令结果及后台文件任务统一校验；撤权在下一请求生效。库存当前余额仍为授权仓库的共享实物快照，报表说明与范围内事实的区别。本轮新增V37，仍未发布，配置步骤和验收见[用户手册](docs/用户使用手册.md#m11-部门数据范围未发布)及[Y-02本机验收报告](docs/M11部门数据范围本机验收报告.md)。

M11 前半的其余功能继续加入业务员归属及报表汇总、数据字典、官方省市区、公告与消息中心、短信邮件模板及日志、企业微信机器人、在线会话强退、系统参数、定时任务和API日志。工作台待办与消息中心共用权限过滤，WebSocket通过Redis跨实例广播摘要。本轮追加V38—V43，仍未正式发布；配置及权限详见[M11使用说明](docs/M11组织消息与运维使用说明.md)，测试与升级证据见[本机验收报告](docs/M11组织消息与运维本机验收报告.md)。

M11 后半加入 Flowable 8.0.0 工作流：SIMPLE设计发布、组织选人、会签/或签/依次、分支、转委加减签、超时提醒和个人审批中心，接入销售/采购订单、退款、冲销及库存调整；X-03最低售价支持提示、人工价格审批或禁止。先通过真实同库事务门槛，保持原状态及服务端硬规则；未匹配流程保留原单级审批。追加V44—V46，目标1.3.0未发布；见[工作流使用说明](docs/M11工作流使用说明.md)和[详细设计](docs/详细设计/02-工作流.md)。

M12候选增量增加公司独立人民币账套、企业/小企业准则样例、七维辅助、期初试算、双人复核凭证、运营自动凭证、六类账簿、公式三表、财务指标和统一结账；同时增加账龄、信用审批与临时额度、催收和坏账。CSV/XLSX导出复用现有后台文件任务，配置及输出均校验公司完整范围。追加V47—V51，目标1.4.0未发布；[使用说明](docs/M12财务总账使用说明.md)解释启用步骤及财务确认边界，[详细设计](docs/详细设计/03-财务总账.md)说明接口与规则。

前端使用 Vue 3、TypeScript、Vite 和 Element Plus。后端使用 Java 21、Spring Boot 和 Maven，提供 REST API。MySQL 8.0.46 保存业务数据，Flyway 负责数据库迁移，Redis 保存会话。后台 Worker 处理导入、导出等异步任务，生产附件和文件任务使用私有 S3 兼容对象仓。

| 路径 | 内容 |
| --- | --- |
| [`erp-web/`](erp-web/) | 管理端页面、API 调用、浏览器和组件测试 |
| [`erp-server/`](erp-server/) | 应用 API、业务服务、Worker、数据库迁移与集成测试 |
| [`erp-ai/`](erp-ai/) | 可选的模型网关、知识库和 Wiki 服务，使用独立的 MySQL 库 |
| [`deploy/`](deploy/) | Nginx、Compose、API 与 Worker 服务及生产环境配置模板 |
| [`scripts/`](scripts/) | 本机初始化、构建、打包、备份和恢复脚本 |
| [`docs/`](docs/) | 部署、运维、接口、数据库、业务规则和验收记录 |

### 在本机启动

以下步骤对应 Windows 开发环境。准备 Git、PowerShell 7、JDK 21、Maven、Node.js 24、npm 和 Redis for Windows。启动 MySQL 8.0.46 的本机 3306 端口，并将 `mysql` 命令加入 PATH。

```powershell
git clone https://github.com/qingyu6688/qiheng-erp.git
cd qiheng-erp
```

在项目根目录执行以下命令，并替换本机的 JDK 和 Redis 目录。首次初始化时，在当前终端准备具有建库与授权权限的 `DB_USERNAME` 和 `DB_PASSWORD`。脚本会建立独立的开发库、测试库并生成本机 `.env`。已有项目数据库时，参考 [`.env.example`](.env.example)填写连接信息。

```powershell
pwsh -NoProfile -File ./scripts/prepare-local.ps1 -JavaHome 'C:\tools\jdk-21' -RedisDirectory 'C:\tools\redis'
npm --prefix erp-web ci
```

在项目根目录开两个终端。第一个启动 API，第二个启动前端。

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 server
```

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 web
```

打开[本机工作台](http://127.0.0.1:5173)。默认开发企业为 `DEMO`，用户名为 `admin`，初始口令取自私有 `.env` 的 `ERP_DEV_ADMIN_PASSWORD`。默认 API 端口为 8080，本机准备脚本使用 Redis 6380。更多配置见[部署说明](docs/部署说明.md)。

### 测试与发布进度

在项目根目录执行后端验证、前端检查和构建。

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 verify
npm --prefix erp-web run lint
npm --prefix erp-web test
npm --prefix erp-web run build
```

准备 Playwright 浏览器后，使用 `pwsh -NoProfile -File ./scripts/project.ps1 e2e` 在独立测试库和随机测试企业中运行浏览器回归。[测试报告](docs/测试报告.md)

`1.0.0-rc.1` 已完成本机空库迁移、旧库升级、期初和购销试运行，以及备份恢复演练。正式环境验收和试运行按上线清单执行，后续组织模型、审批流、消息中心和财务扩展列入开发计划。[本机验收报告](docs/1.0.0候选版本本机上线演练报告.md) · [上线准备清单](docs/上线准备清单.md) · [开发计划](docs/开发计划.md)

- 开发参考见[接口设计](docs/接口设计.md)和[数据库设计](docs/数据库设计.md)。
- 部署和运营见[运维手册](docs/运维手册.md)和[多方式登录配置](docs/多方式登录配置.md)。

M10 已开始接入运行监控。独立管理端口提供 Prometheus 指标，外置 OpenTelemetry Java Agent 采集链路，部署模板包含 Grafana 看板、Tempo、Loki、Alloy 和 Alertmanager。Windows 可运行本机链路和 Outbox 告警演练，配置、命令与验证范围见[运维手册](docs/运维手册.md#m10-运行监控)。

### 许可

柒衡 ERP 项目代码采用 [GNU Affero 通用公共许可证第 3 版](LICENSE)，SPDX 标识为 `AGPL-3.0-only`。第三方依赖适用各自的许可条款。

## English

The unreleased M12 increment adds an independent CNY ledger per company, enterprise/small-enterprise sample charts, seven auxiliary dimensions, opening trials, independently reviewed manual vouchers, automatic business vouchers, six ledger views, configurable statements, seven financial indicators, and integrated period closing/reopening. Receivables gain company-specific aging, concurrent credit controls with mandatory manual approvals, temporary increases, collection reminders, and standard-specific bad-debt treatment. CSV/XLSX exports reuse the existing private background file pipeline. Company, warehouse, department, cost, and operation permissions are enforced on the server, including saved results and downloads.

Migrations V47–V51 are required. These are local candidate changes; target 1.4.0 has not been released. Production charts, auxiliary rules, opening figures, mappings, and formulas require financial review before activation. Samples do not claim to cover every regulated industry or import format. Mid-year cash-flow year-to-date figures remain unavailable without pre-activation classifications. See the [usage guide (Chinese)](docs/M12财务总账使用说明.md), [technical design (Chinese)](docs/详细设计/03-财务总账.md), and [acceptance report (Chinese)](docs/M12财务总账本机验收报告.md).

Qiheng ERP is an inventory and business finance system for trading and warehouse teams. Purchasing, sales, warehouse, and finance staff work with the same transactions to track order fulfillment, inventory costs, receivables, payables, and account balances.

M11 workflow work adds Flowable 8.0.0 with a SIMPLE designer, immutable model releases, organization-based assignees, countersign/any-one/sequential review, conditional/parallel branches, transfer/delegation/additional signers, timeout reminders, and a personal approval center. Five existing document types retain their business states and server-side rules. Minimum sale prices support warnings, mandatory manual price approval, or blocking. Real database transaction tests gate the synchronous integration; V44–V46 are pending-release migrations. Target 1.3.0 is not released. See the [workflow guide (Chinese)](docs/M11工作流使用说明.md) and [implementation design (Chinese)](docs/详细设计/02-工作流.md).

### Business modules

| Module | Supported operations |
| --- | --- |
| Master data | Manage companies, warehouses, customers, suppliers, SKUs, units of measure, prices, and settlement accounts. |
| Sales | Create and approve orders, ship goods, record delivery confirmation, process returns and refunds, and register invoices. Delivery confirmation establishes the receivable, which can then be settled against a payment. |
| Purchasing | Convert requisitions into purchase orders, approve orders, and post receipts to inventory and accounts payable. Process returns, vendor refunds, supplier payments, and settlement. |
| Inventory | View on-hand, available, reserved, and blocked quantities. Record transfers, stock counts, and adjustments. The system calculates costs using a moving weighted average. |
| Finance | Enter or import opening balances, manage receivables and payables, and record receipts, payments, and transfers between accounts. Reverse transactions, run eight reconciliation checks, and close or reopen accounting periods. |
| Internal AI assistant (candidate) | Ask questions with verifiable citations scoped to the tenant, roles, and departments. Maintain document segments and review Wiki revisions. Admins can set model routes, read-only tools, and usage limits. See the [M13 guide and acceptance checklist (Chinese)](docs/M13人工智能使用与验收.md). |
| Platform phase two (candidate) | Enterprise SSO, role-enforced MFA, custom fields, numbering rules, signed API and webhook integrations, and general approvals with dynamic forms. See the [M14 guide and acceptance checklist (Chinese)](docs/M14平台二期使用与验收.md). |
| Reports and files | Use ten report types, including inventory movements, sales and purchase summaries, aging, cash journals, and gross margin. Run background CSV imports and exports, download rejected rows, preview printed documents, and manage private attachments. |
| Access and audit | Scope data by tenant, company, and warehouse, and grant permissions by role and action. Key transactions can require a separate reviewer and record an audit trail. Administrators can configure SMS codes, email codes, and WeChat sign-in per tenant. |

The unreleased M10 iteration adds default receipt and payment accounts, permission-scoped dashboard amounts with twelve-month trends, and an optional read-only reporting replica. Dashboard results are cached for up to five minutes and show their data cutoff time. See the [implementation notes (Chinese)](docs/M10功能优化实施说明.md) and [local acceptance report (Chinese)](docs/M10本机验收报告.md) for validation and remaining deployment work.

An opening-stock CSV import creates drafts for review and posting before the balances and costs are recorded. See the [business rules (Chinese)](docs/业务规则.md) and [user guide (Chinese)](docs/用户使用手册.md) for the full workflows.

### Stack and repository layout

The unreleased M11 Y-01 work adds company department trees, tenant-wide job definitions, and company-specific user assignments. Primary departments and organization versions remain independent between companies. Assignments do not grant business access; revoking company access also clears its assignments and department leaders. Deployment includes migration V36. See the [user guide (Chinese)](docs/用户使用手册.md) and [M11 local acceptance report (Chinese)](docs/M11部门岗位本机验收报告.md).

The unreleased M11 Y-02 work adds five company-specific department scopes for roles, intersected with company, warehouse, and operation permissions. Ownership is frozen on 17 document types. Lists, totals, reports, printing, attachments, saved command results, and background file jobs enforce the scope; revocations apply on the next request. Current inventory remains a shared snapshot of authorized warehouses, with report labels distinguishing it from scoped document facts. Migration V37 is required. See the [configuration guide (Chinese)](docs/用户使用手册.md) and [Y-02 local acceptance report (Chinese)](docs/M11部门数据范围本机验收报告.md).

The rest of the unreleased M11 organization and messaging work adds sales/buyer ownership and reporting, data dictionaries, official administrative areas, notices and inboxes, SMS/email templates and delivery logs, a WeCom bot, session revocation, system settings, managed jobs, and API logs. Workbench todos use the permission-checked inbox; WebSocket summaries are broadcast through Redis across API instances. Migrations V38–V43 are required. See the [configuration and operations guide (Chinese)](docs/M11组织消息与运维使用说明.md) and [local acceptance report (Chinese)](docs/M11组织消息与运维本机验收报告.md). This remains a local candidate increment, not a published 1.2.0 release.

The web app uses Vue 3, TypeScript, Vite, and Element Plus. The REST API uses Java 21, Spring Boot, and Maven. MySQL 8.0.46 stores business data, Flyway manages schema migrations, and Redis stores sessions. A worker runs background jobs such as imports and exports. Production file jobs and attachments use private S3-compatible storage.

| Path | Contents |
| --- | --- |
| [`erp-web/`](erp-web/) | Management UI, API client, and browser and component tests |
| [`erp-server/`](erp-server/) | API, business services, worker, database migrations, and integration tests |
| [`erp-ai/`](erp-ai/) | Optional model gateway, knowledge base, and Wiki with a separate MySQL schema |
| [`deploy/`](deploy/) | Nginx, Compose, API and worker services, and production configuration templates |
| [`scripts/`](scripts/) | Local setup, build, packaging, backup, and restore scripts |
| [`docs/`](docs/) | Deployment, operations, API, database, business rules, and acceptance records |

### Run locally

These steps use Windows. Install Git, PowerShell 7, JDK 21, Maven, Node.js 24, npm, and Redis for Windows. Run MySQL 8.0.46 locally on port 3306 and put the `mysql` command on PATH.

```powershell
git clone https://github.com/qingyu6688/qiheng-erp.git
cd qiheng-erp
```

Run the following commands from the repository root with your own JDK and Redis paths. For first-time setup, provide `DB_USERNAME` and `DB_PASSWORD` in the current shell for an account that can create databases and grant privileges. The setup script creates separate development and test databases and generates a local `.env` file. For existing project databases, use [`.env.example`](.env.example) as a configuration reference.

```powershell
pwsh -NoProfile -File ./scripts/prepare-local.ps1 -JavaHome 'C:\tools\jdk-21' -RedisDirectory 'C:\tools\redis'
npm --prefix erp-web ci
```

Open two terminals at the repository root. Start the API in the first terminal and the web app in the second.

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 server
```

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 web
```

Open the [local workbench](http://127.0.0.1:5173). The default development tenant and username are `DEMO` and `admin`. Use the `ERP_DEV_ADMIN_PASSWORD` value in your private `.env` file. The default API port is 8080, and the setup script uses Redis port 6380. See the [deployment guide (Chinese)](docs/部署说明.md) for configuration details.

### Testing and release status

Run these checks from the repository root.

```powershell
pwsh -NoProfile -File ./scripts/project.ps1 verify
npm --prefix erp-web run lint
npm --prefix erp-web test
npm --prefix erp-web run build
```

Once a Playwright browser is available, run `pwsh -NoProfile -File ./scripts/project.ps1 e2e` to test with a separate database and a generated test tenant. [Test report (Chinese)](docs/测试报告.md)

`1.0.0-rc.1` passed local trials for fresh installation, database upgrades, opening balances, purchasing and sales, and backup and restore. Production acceptance and the business trial follow the launch checklist. The development plan covers organizational structure, approval workflows, notifications, and deeper financial functionality. [Local acceptance report (Chinese)](docs/1.0.0候选版本本机上线演练报告.md) · [Launch checklist (Chinese)](docs/上线准备清单.md) · [Development plan (Chinese)](docs/开发计划.md)

- For development, see the [API design](docs/接口设计.md) and [database design](docs/数据库设计.md) in Chinese.
- For deployment and operations, see the [operations manual](docs/运维手册.md) and [sign-in configuration guide](docs/多方式登录配置.md) in Chinese.

M10 adds operational monitoring through a separate Prometheus management endpoint and an external OpenTelemetry Java Agent. Deployment templates include a Grafana dashboard, Tempo, Loki, Alloy, and Alertmanager. A Windows drill verifies traces and Outbox alert delivery. Configuration, commands, and validation scope are documented in the [operations manual (Chinese)](docs/运维手册.md#m10-运行监控).

### License

Qiheng ERP's project code is licensed under the [GNU Affero General Public License, version 3](LICENSE), with the SPDX identifier `AGPL-3.0-only`. Third-party dependencies are governed by their respective licenses.
