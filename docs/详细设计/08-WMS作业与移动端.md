# 08 WMS 作业与移动端（2.0.0）

> 对应：《功能扩展方案》第 9 节；《企业级演进规划》S-02。
> 前置：06 的库区库位、批次、序列号（1.7.0）。
> 包：`wms`；新增前端工程 `erp-mobile`。

当前实现从上架开始。仓库已有库位管理且默认收货位属于启用的收货区时，可以按仓启用 `optional`。只有已入收货区且可用的库存进入上架计划。任务继承入库单部门归属，按企业、公司、仓库和归属范围过滤。确认支持分批并保留原批次及序列号；仓级数量及成本保持不变。推荐优先选择可容纳剩余数量的同 SKU 同批次存储位，再按拣货顺序选择空位。固定库位、指定库区与补货上下限随库位规则后续接入。

---

## 1. 范围

| 功能 | 说明 |
|---|---|
| 上架 | 收货 / 销售退货过账后生成上架任务，按策略推荐库位，扫码确认 |
| 拣货 | 发货单提交后生成拣货任务，按库位拣货顺序排列，支持按单拣货和多单合并（波次）拣货 |
| 复核打包 | 拣货完成后扫码复核数量、批次、序列号，记录箱号 |
| 移库 | 同仓库位间移动，不影响成本 |
| 补货 | 拣货位低于下限时生成从存储位到拣货位的补货任务 |
| 盘点作业 | 现有盘点单增加按库位分配任务、PDA 扫码盘点 |
| 标签 | 商品、库位、批次、箱标签打印 |
| 移动端 | PDA 执行上述作业；手机审批 |

## 2. 作业与单据的关系

```text
采购收货单 ──过账（入收货库位，现有规则 + 1.7.0 库位）──► 生成上架任务
                                                  └─ 上架完成：收货库位 → 存储库位（移库记账）
发货单 ──提交（现有：占用批次）──► 生成拣货任务
                                └─ 拣货完成：存储库位 → 发货库位（移库记账）
                                └─ 复核完成 ──► 允许发货单过账（从发货库位出库）
```

- **任务不改变单据的财务事实**：收货过账、发货过账的数量和成本规则不变；任务只在库位之间移动，不改总数、不改成本。
- 仓库参数 `wms_task_mode`：`off`（不生成任务，与 1.7.0 行为相同）、`optional`（生成任务但不强制）、`required`（发货单必须复核完成才能过账）。

## 3. 数据模型

| 表 | 关键字段 |
|---|---|
| `wms_task` | `id`、`tenant_id`、`company_id`、`warehouse_id`、`no`、`type`（putaway / pick / replenish / move / count）、`source_type`、`source_id`、`wave_id`、`priority`、`assignee_id`、`status`（created / assigned / in_progress / done / cancelled）、`started_at`、`finished_at`、`version` |
| `wms_task_line` | `id`、`task_id`、`sku_id`、`batch_id`、`from_location_id`、`to_location_id`、`plan_qty`、`done_qty`、`source_line_id`、`status` |
| `wms_task_serial` | `task_line_id`、`serial_id` |
| `wms_wave` | `id`、`tenant_id`、`company_id`、`warehouse_id`、`no`、`rule`（按承运商、截单时间、客户）、`status`、`created_by` |
| `wms_pack` | `id`、`shipment_id`、`box_no`、`weight_kg`、`volume`、`checker_id`、`status` |
| `wms_pack_line` | `pack_id`、`sku_id`、`batch_id`、`qty`、`serial_ids` |
| `wms_location_rule` | `tenant_id`、`warehouse_id`、`sku_id` / `category_id`、`zone_id`、`fixed_location_id`、`min_qty`、`max_qty`（拣货位上下限，用于补货） |
| `wms_move_doc` / `wms_move_line` | 手工移库单 |

## 4. 关键规则

### 4.1 上架策略（按顺序取第一个可用库位）

1. SKU 的固定库位（`wms_location_rule.fixed_location_id`）
2. 已存放同 SKU 同批次、且未满的库位（允许混批时可放同 SKU 不同批次）
3. 规则指定库区内的空库位，按 `pick_sequence` 从小到大
4. 都没有：提示人工指定

### 4.2 拣货策略

- 批次：沿用发货单提交时占用的批次；没有指定时按 SKU 的 `issue_strategy`。
- 库位：同批次多库位时，先拣数量少的库位（腾空库位），再按 `pick_sequence`。
- 波次：多张发货单合并为一个拣货任务，拣货单按库位路径排序，页面按单号分格显示（播种）。

### 4.3 并发与加锁

- 任务完成时的库位移动在一个事务中：按“库存余额（源库位、目标库位按键排序）→ 序列号 → 任务行”加锁。
- 同一库位余额被多个任务同时扣减时，用条件更新 `on_hand_qty >= ?`，失败返回“库位库存不足，请刷新任务”。
- 任务行完成数量超过计划数量时拒绝；少拣时可以“短拣”结束，发货单数量需要人工调整（不自动改单）。

### 4.4 复核

- 扫商品条码或批次码逐件 / 逐箱确认；序列号 SKU 必须逐个扫描。
- 复核差异：多扫、少扫、扫错批次时阻止完成，提示具体差异。
- 复核完成后生成装箱记录，可打印箱标签和装箱清单。

## 5. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/wms/tasks?type=&status=&assignee=me` | 任务列表 |
| POST | `/api/v1/wms/tasks/{id}/claim` | 领取 |
| POST | `/api/v1/wms/tasks/{id}/lines/{lineId}/confirm` | 确认一行：`{fromLocationCode, toLocationCode, batchNo, qty, serialNos[]}`，扫码值由服务端解析 |
| POST | `/api/v1/wms/tasks/{id}/finish` | 完成（可短拣） |
| POST | `/api/v1/wms/waves` | 创建波次 `{shipmentIds[]}` |
| POST | `/api/v1/wms/packs` | 复核装箱 |
| CRUD | `/api/v1/wms/moves` | 手工移库 |
| GET | `/api/v1/wms/scan/resolve?code=` | 扫码解析：识别是商品条码、库位码、批次码、序列号还是箱码 |
| POST | `/api/v1/labels/print-jobs` | 生成标签打印数据（PDF 或打印机指令） |

所有确认类接口都带 `Idempotency-Key`（PDA 网络抖动重试时不会重复移库）。

## 6. 标签

- 条码规则：商品条码沿用 `sku_barcode`；库位码 `L:{库位编码}`；批次码 `B:{SKU 编码}|{批次号}`；箱码 `X:{发货单号}-{箱号}`；扫码解析按前缀识别。
- 生成：后端用 ZXing 生成 Code128 / QR，输出 PDF（标签纸尺寸可配置：50×30、70×40、100×100 毫米）；常用条码打印机（斑马、TSC）可选输出 ZPL / TSPL 指令，由浏览器打印或局域网打印服务转发。

## 7. 移动端 `erp-mobile`

### 7.1 技术

| 项 | 选型 |
|---|---|
| 框架 | uni-app（Vue 3 + TypeScript + Vite） |
| 组件库 | wot-design-uni |
| 状态 | Pinia |
| 请求 | `uni.request` 封装，统一处理 `ApiResponse`、幂等键、`X-Company-Id` |
| 构建目标 | Android App（PDA）、H5（手机审批） |
| 登录 | App：设备令牌（`Authorization: Bearer`，见 00 第 4 节）；H5：会话 Cookie |
| 扫码 | PDA 键盘模拟模式：页面始终保持一个隐藏输入框获得焦点，扫码结果 + 回车触发解析；手机 H5 用摄像头扫码（`uni.scanCode`） |
| 提示 | 成功 / 失败分别用不同提示音和震动，方便仓库作业时不看屏幕 |

### 7.2 目录

```text
erp-mobile/
  src/
    api/            与 erp-web 相同的接口函数风格
    pages/
      login/
      home/         工作台：今日任务数、待办审批
      putaway/      上架：任务列表 → 扫库位 → 扫商品 / 批次 → 输数量 → 确认
      pick/         拣货
      check/        复核装箱
      move/         移库
      count/        盘点
      label/        标签补打
      approval/     审批：待办列表、单据摘要、同意 / 驳回
      stock/        库存查询（扫商品或库位）
    composables/    useScanner（扫码监听）、useTaskFlow
    stores/
```

### 7.3 作业页面交互（以上架为例）

```text
① 扫任务码或从列表选任务
② 屏幕提示：请扫描库位 A-01-02-03
③ 扫库位 → 校验是否为推荐库位（不是时提示“确认改放到 X？”）
④ 扫商品 / 批次 → 显示品名、批次、待上架数量
⑤ 输入数量（默认剩余数量）→ 确认 → 提示音
⑥ 下一行；全部完成后自动提交任务
```

- 每一步只显示当前需要的信息，字体 ≥ 18px，主按钮占满宽度，适合戴手套操作。
- 网络中断：保留当前输入并提示“网络断开，恢复后重新提交”；重新提交复用同一个幂等键。

## 8. 管理端页面

| 菜单（仓储作业） | 页面 |
|---|---|
| 作业任务 | 任务列表（按类型页签）、分配、取消、任务进度 |
| 波次 | 选择待拣发货单创建波次、打印拣货单 |
| 复核装箱 | 管理端复核（没有 PDA 时用扫码枪 + 电脑） |
| 移库 | 手工移库单 |
| 库位规则 | 固定库位、库区规则、拣货位上下限 |
| 标签打印 | 按单据、库位、批次批量打印 |
| 作业统计 | 人员作业量、任务时效 |

## 9. 迁移规划

1. `wms_task`、`wms_task_line`、`wms_task_serial`、`wms_wave`
2. `wms_pack`、`wms_pack_line`、`wms_location_rule`、`wms_move_doc`、`wms_move_line`
3. `warehouse.wms_task_mode`
4. 设备令牌表 `auth_device_token`（`token_hash`、`user_id`、`device_name`、`expires_at`、`last_used_at`）
5. 权限码：`wms_task:*`、`wms_wave:*`、`wms_pack:*`、`wms_move:*`、`label:print`

## 10. 测试要点

1. 收货过账后上架任务库位推荐正确；上架完成后收货库位余额为 0、存储库位增加，总数和成本不变。
2. `required` 模式下，复核未完成的发货单过账被拒绝。
3. 两个 PDA 同时拣同一库位最后 1 件，只有一个成功。
4. 网络重试：同一幂等键重复确认不重复移库。
5. 设备令牌：强制下线后 PDA 下一次请求返回 401；令牌不能用于开放平台接口。

## 11. 验收

开启库位与任务模式的仓库：采购收货 → PDA 上架到推荐库位 → 三张发货单合并波次 → PDA 拣货 → 复核装箱打印箱标签 → 发货过账；全程库存总数、成本与不开启任务时完全一致，库位余额正确。
