# 07 CRM 与客户门户（1.9.0）

> 对应：《功能扩展方案》第 7 节；《企业级演进规划》S-06、A-05、A-06。
> 新增包：`crm`、`portal`；AI 客服的模型部分见 04。

---

## 1. 范围

| 模块 | 功能 |
|---|---|
| 线索 | 录入、导入、分配、跟进、转化为客户、无效 |
| 客户 | 负责人、团队成员、成交状态、级别、来源、行业、地区、下次联系时间、锁定 |
| 公海 | 自动回收规则、领取、分配、拥有数与锁定数限制 |
| 联系人 | 复用往来单位联系人，增加 CRM 字段 |
| 商机 | 阶段组与赢率、预计金额与日期、产品明细、赢单 / 输单 / 无效 |
| 合同 | 产品明细、金额、起止日期、工作流审批、生成销售订单 |
| 回款 | 回款计划、回款登记（关联收款单） |
| 跟进记录 | 多种方式、附件、下次联系、@ 同事 |
| 业绩目标 | 按员工 / 部门 / 年月 设置合同额、回款额目标 |
| 统计 | 客户总量与新增、销售漏斗、业绩完成、客户画像、产品销量、员工排行、跟进统计 |
| 价格 | 价格表、客户协议价 |
| 客户门户 | 客户联系人登录，查订单、物流、对账、发票，在线提问与工单 |
| 客服工单 | AI 客服转人工、门户提交、坐席处理 |
| 支付（可选） | 门户在线付款 |

## 2. 主数据关系

```text
business_partner（往来单位，已有）
   ├─ crm_customer_profile（1:1，CRM 扩展信息）
   ├─ partner_contact（已有）── crm_contact_profile（1:1，CRM 扩展字段）
   ├─ crm_customer_team（负责人 + 团队成员）
   └─ portal_user（门户账号，绑定某个联系人）
crm_clue ──转化──► business_partner（新建，角色 customer）+ crm_customer_profile
```

- **客户只有一份主数据**：CRM 不建独立客户表。线索转化时新建往来单位（角色“客户”），已存在同名或同税号的往来单位时提示合并。
- 往来单位在 ERP 中被禁用时，CRM 中同步显示为“已停用”。

## 3. 数据模型

| 表 | 关键字段 |
|---|---|
| `crm_clue` | `id`、`tenant_id`、`company_id`、`name`（公司名）、`contact_name`、`mobile_cipher`、`mobile_hash`（查重用）、`email`、`source`（字典）、`industry`（字典）、`area_id`、`owner_user_id`、`status`（new / following / converted / invalid）、`converted_partner_id`、`next_contact_at`、`last_follow_at`、`ext_json`、`version` |
| `crm_customer_profile` | `partner_id`（主键）、`tenant_id`、`company_id`、`owner_user_id`（0 表示在公海）、`level`（字典）、`source`、`industry`、`area_id`、`deal_status`（unsold / sold）、`locked`、`next_contact_at`、`last_follow_at`、`pool_entered_at`、`ext_json`、`version` |
| `crm_customer_team` | `partner_id`、`user_id`、`permission`（read / write）、`expires_at` |
| `crm_pool_rule` | `tenant_id`、`company_id`、`enabled`、`no_follow_days`、`no_deal_days`、`notify_before_days`、`apply_to_levels` |
| `crm_owner_limit` | `tenant_id`、`company_id`、`target_type`（user / department）、`target_id`、`max_owned`、`max_locked`、`include_sold` |
| `crm_contact_profile` | `contact_id`（主键）、`position`、`is_decision_maker`、`birthday`、`wechat`、`remark` |
| `crm_business_stage_group` | `id`、`tenant_id`、`name`、`apply_departments` |
| `crm_business_stage` | `group_id`、`name`、`win_rate`、`sort` |
| `crm_business` | `id`、`tenant_id`、`company_id`、`name`、`partner_id`、`stage_group_id`、`stage_id`、`expected_amount`、`expected_date`、`owner_user_id`、`result`（open / won / lost / invalid）、`lost_reason`、`ext_json`、`version` |
| `crm_business_line` | `business_id`、`sku_id`、`qty`、`unit_price`、`discount_rate`、`amount` |
| `crm_contract` | `id`、`tenant_id`、`company_id`、`no`、`name`、`partner_id`、`business_id`、`signer_contact_id`、`owner_user_id`、`sign_date`、`start_date`、`end_date`、`total_amount`、`status`（draft / pending / approved / rejected / cancelled / finished）、`wf_instance_id`、`sales_order_id`、`version` |
| `crm_contract_line` | `contract_id`、`sku_id`、`unit_id`、`qty`、`unit_price`、`tax_rate`、`discount_rate`、`amount` |
| `crm_receivable_plan` | `id`、`contract_id`、`period_no`、`plan_date`、`plan_amount`、`remind_days`、`received_amount`、`status`（pending / partial / finished / overdue） |
| `crm_receivable` | `id`、`tenant_id`、`company_id`、`contract_id`、`plan_id`、`receipt_id`（现有收款单）、`amount`、`received_date` |
| `crm_follow_up` | `id`、`tenant_id`、`company_id`、`biz_type`（clue / customer / business / contract）、`biz_id`、`method`（字典：电话 / 拜访 / 微信 / 邮件）、`content`、`next_contact_at`、`mentioned_user_ids`、`created_by`、`created_at` |
| `crm_performance_target` | `tenant_id`、`company_id`、`target_type`（user / department）、`target_id`、`year`、`month`、`kind`（contract / receivable）、`amount` |
| `price_list` | `id`、`tenant_id`、`company_id`、`name`、`currency`、`price_includes_tax`、`valid_from`、`valid_to`、`priority`、`status` |
| `price_list_item` | `price_list_id`、`sku_id`、`unit_id`、`min_qty`、`unit_price` |
| `price_list_target` | `price_list_id`、`target_type`（all / customer_level / customer）、`target_value` |

- 手机号加密存储，另存哈希用于查重；列表默认掩码，需要 `partner_contact:view` 才显示完整号码（沿用现有字段权限）。

## 4. 关键规则

### 4.1 数据权限

- 客户可见：负责人、团队成员、负责人的上级（按部门数据范围）、有“查看全部客户”权限的人；公海客户对所有有 `crm_pool:view` 权限的人可见。
- 写权限：负责人、团队成员（write）、上级。
- 实现：在 01 的 `OrgScope` 基础上，CRM 查询再加“负责人或团队成员”条件，统一由 `CrmScope.appendFilter` 拼接。
- 商机、合同、跟进记录继承所属客户的可见性。

### 4.2 公海

- 每日任务：对开启规则的公司，`last_follow_at` 超过 `no_follow_days`、或 `deal_status=unsold` 且创建超过 `no_deal_days` 的非锁定客户，回收到公海（`owner_user_id=0`），回收前 `notify_before_days` 天提醒负责人。
- 领取：检查 `crm_owner_limit`（拥有数），并发领取同一客户只有一人成功（条件更新 `WHERE owner_user_id=0`）。
- 锁定：锁定的客户不会被回收，受锁定数限制。

### 4.3 合同与订单

- 合同提交走工作流（`business_type=crm_contract`，条件字段：金额、折扣率、客户级别、是否低于最低售价）。
- 审批通过后，“生成销售订单”：带出客户、明细、价格、税率，订单记 `source_type=crm_contract`、`source_id=合同 ID`（这两列在 1.8.0 随 AI 录单新增，见 04 第 7.3 节）；一个合同可以分多次生成订单，累计数量不超过合同数量。
- 合同金额统计取合同金额；回款统计取关联收款单的**已确认**金额。

### 4.4 回款

- 回款登记不产生资金流水，只是把现有收款单（已登记或已确认）关联到合同和回款计划。
- 也可以在收款单上直接选择“关联合同”，两边同步。
- 回款计划到期前 `remind_days` 天提醒负责人；逾期标记 `overdue`。

### 4.5 价格表

- 订单录入取价顺序：客户协议价 → 客户级别价格表 → 通用价格表 → SKU 参考售价；同级别按 `priority`。
- 价格表只决定默认单价，订单保存的仍是当时的价格快照（与现有规则一致）。
- 价格表也适用于现有销售订单录入，不只是 CRM 合同。

## 5. 统计

| 统计 | 口径 |
|---|---|
| 客户总量分析 | 按负责人 / 部门 / 时间：新增客户数、成交客户数、跟进次数 |
| 销售漏斗 | 按阶段的商机数和预计金额；阶段转化率 |
| 业绩目标完成 | 合同额、回款额 vs 目标，按月 / 季 / 年 |
| 客户画像 | 地区、行业、级别、来源分布 |
| 产品销量 | 合同产品销量排行（订单口径的销售报表已有） |
| 员工排行 | 合同额、回款额、新增客户、跟进次数 |

- 统计查询走只读备库；按 CRM 数据权限过滤。
- 接口：`GET /api/v1/crm/statistics/{kind}?dateFrom=&dateTo=&userIds=&deptId=`。

## 6. 客户门户

### 6.1 账号

| 表 | 关键字段 |
|---|---|
| `portal_user` | `id`、`tenant_id`、`partner_id`、`contact_id`、`login_name`（手机号或邮箱）、`password_hash`、`status`、`last_login_at`、`version` |
| `portal_session` | Spring Session 独立命名空间 `erp:portal-session` |

- 门户账号由内部人员为客户联系人开通（或客户扫码申请、内部审核），登录支持密码和短信验证码。
- 门户使用独立入口（如 `portal.example.com`）和独立会话 Cookie 名，与内部会话完全隔离。
- 门户接口 `/api/v1/portal/**`：独立的 Security 过滤链；每个接口从会话中取 `partner_id`，**不接受客户端传入的往来单位参数**。

### 6.2 功能

| 页面 | 数据来源 |
|---|---|
| 首页 | 待收货订单数、未付款金额、最近发货 |
| 我的订单 | 销售订单（只读）：状态、履约进度 |
| 发货与物流 | 发货单、签收状态、物流单号（如有） |
| 对账 | 往来对账单（现有报表口径），可下载 PDF，可在线“确认对账” |
| 发票 | 已开具发票（数电发票文件下载） |
| 在线付款 | 可选：选择未结应收付款，跳转支付，回调生成待确认收款单 |
| 在线客服 | AI 客服 + 转人工 |
| 工单 | 提交问题、查看处理进度 |

### 6.3 前端

- 门户是 `erp-web` 中的独立入口（Vite 多页面：`portal.html`），共用组件库和设计令牌，独立路由和布局，打包为单独产物。
- 适配手机浏览器（客户常用手机查看）。

## 7. 客服工单

| 表 | 关键字段 |
|---|---|
| `cs_ticket` | `id`、`tenant_id`、`company_id`、`no`、`partner_id`、`portal_user_id`、`channel`（portal / mp / wecom / phone）、`category`（字典：订单、物流、发票、质量、投诉、其他）、`priority`、`title`、`status`（open / assigned / processing / waiting_customer / resolved / closed）、`assignee_id`、`ai_conversation_id`、`sla_due_at`、`resolved_at`、`satisfaction` |
| `cs_ticket_reply` | `ticket_id`、`author_type`（customer / agent / system）、`author_id`、`content`、`attachments`、`internal`（内部备注，客户不可见） |

- 分配：按类别和客户负责人自动分配，或进入待领取池。
- SLA：按优先级设置首次响应和解决时限，超时提醒（站内信）并在工单列表标红。
- 坐席辅助（A-06）：工单详情右侧显示客户信息、近期订单、未结款、AI 建议回复（只能复制）。
- 关闭后邀请客户评价满意度。

## 8. 支付（可选）

- 只在客户门户启用“在线付款”时实施。
- 表：`pay_channel_config`（微信支付 / 支付宝商户配置，密钥加密）、`pay_order`（门户付款单：金额、关联未结项、状态、渠道订单号）、`pay_notify_log`。
- 支付成功回调：验签 → 幂等更新 `pay_order` → 生成**已登记、待确认**的收款单并关联未结项；财务确认后按现有规则核销。不自动确认，保持经办复核分离。
- 退款走现有退款申请流程，退款到原支付渠道需要另行对接，本版不做。

## 9. 接口汇总

| 资源 | 路径 | 权限 |
|---|---|---|
| 线索 | `/api/v1/crm/clues`、`/{id}/{assign\|convert\|invalidate}` | `crm_clue:*` |
| 客户 | `/api/v1/crm/customers`、`/{id}/{transfer\|lock\|unlock\|release}`、`/{id}/team` | `crm_customer:*` |
| 公海 | `/api/v1/crm/pool`、`/{id}/{claim\|assign}`、`/api/v1/crm/pool-rules`、`/owner-limits` | `crm_pool:*` |
| 商机 | `/api/v1/crm/businesses`、`/{id}/{advance\|win\|lose\|invalidate}`、`/stage-groups` | `crm_business:*` |
| 合同 | `/api/v1/crm/contracts`、`/{id}/{submit\|cancel\|create-sales-order}` | `crm_contract:*` |
| 回款 | `/api/v1/crm/receivable-plans`、`/api/v1/crm/receivables` | `crm_receivable:*` |
| 跟进 | `/api/v1/crm/follow-ups` | 随所属对象 |
| 目标 | `/api/v1/crm/performance-targets` | `crm_target:*` |
| 统计 | `/api/v1/crm/statistics/*` | `crm_statistics:view` |
| 价格表 | `/api/v1/price-lists`、`/api/v1/pricing/default-price?partnerId=&skuId=&unitId=&qty=` | `price_list:*` |
| 门户 | `/api/v1/portal/**` | 门户会话 |
| 门户账号管理 | `/api/v1/portal-users` | `portal_user:*` |
| 工单 | `/api/v1/cs/tickets`、`/{id}/{assign\|reply\|resolve\|close}` | `cs_ticket:*` |

## 10. 前端页面

| 菜单（客户关系） | 页面 | 要点 |
|---|---|---|
| 线索 | `pages/crm/ClueListPage.vue` | 列表 + 详情抽屉（跟进时间线）；批量分配、导入 |
| 客户 | `CustomerListPage.vue`、`CustomerDetailPage.vue` | 详情页签：基本信息、联系人、商机、合同、回款、跟进、订单（ERP 订单只读列表）、操作记录 |
| 公海 | `PoolPage.vue` | 领取、分配；显示回收原因 |
| 商机 | `BusinessListPage.vue`、看板视图 | 按阶段的看板，拖拽推进阶段 |
| 合同 | `ContractListPage.vue`、编辑页 | 编辑沿用订单编辑页模式；详情右侧审批进度 |
| 回款 | `ReceivablePage.vue` | 计划与实际对比 |
| 统计 | `StatisticsPage.vue` | ECharts：漏斗图、柱状图、地图（省份分布） |
| 设置 | 公海规则、拥有数限制、商机阶段、业绩目标、价格表 | — |
| 客服 → 工单 | `pages/cs/TicketPage.vue` | 列表 + 详情（对话式回复、坐席辅助侧栏） |

工作台增加 CRM 块：今日待跟进、即将回收的客户、待审批合同、本月业绩完成率。

## 11. 迁移规划

1. `crm_customer_profile`、`crm_customer_team`、`crm_contact_profile`（为现有客户角色的往来单位回填画像，负责人取最近一张订单的业务员，没有则为 0 进入公海——迁移时默认**不进公海**，`owner_user_id` 取创建人，避免上线即大量回收）
2. `crm_clue`、`crm_pool_rule`、`crm_owner_limit`
3. `crm_business_*`、`crm_contract*`、`crm_receivable*`、`crm_follow_up`、`crm_performance_target`
4. `price_list*`
5. `portal_user`、`cs_ticket*`；可选 `pay_*`
6. `receipt` 增加 `crm_contract_id BIGINT NOT NULL DEFAULT 0`；销售订单的来源字段已在 1.8.0 加好，本版只增加取值 `crm_contract`
7. 权限码、字典预置（客户来源、行业、级别、跟进方式、工单类别）

## 12. 测试要点

1. 数据权限：业务员看不到别人的客户；上级能看到下属的；团队成员只读不能编辑。
2. 公海：并发领取只有一人成功；超过拥有数不能领取；锁定客户不被回收。
3. 合同生成订单：累计数量不超过合同；订单后续流程与普通订单一致。
4. 回款：关联未确认的收款单时回款显示“待确认”，确认后计入业绩。
5. 价格表优先级正确；订单保存价格快照，改价格表不影响已有订单。
6. 门户：篡改请求中的单据 ID 访问其他客户的订单返回 404；门户会话不能访问 `/api/v1/**` 内部接口。
7. 支付回调重复通知只生成一张收款单。

## 13. 验收

线索 → 转化客户 → 商机推进到赢单 → 合同审批 → 生成销售订单 → 发货签收 → 收款确认并关联回款计划 → 业绩统计与收款一致；客户在门户中看到该订单、发货和对账单，在线提问由 AI 回答，提出投诉时自动生成工单并分配给负责人。
