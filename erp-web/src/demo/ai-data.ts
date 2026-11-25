import type { AiDocument, AiEvidence, AiLibrary, AiSegment, AiToolResult, AiWikiPage } from '../types/ai'
import { documents, inventoryRows, money, snapshotDate, snapshotTime } from './data'

export const demoAiLibraries: AiLibrary[] = [
  { id: '91001', name: '购销与仓储制度（示例）', description: '销售、收货、库存预留与收款核销的虚构流程资料。', visibility: 'all', targetIds: [], status: 'active' },
  { id: '91002', name: '财务操作手册（示例）', description: '总账查询、单据核对与期间管理的虚构操作资料。', visibility: 'all', targetIds: [], status: 'active' },
]

const sourceDrafts = [
  { id: '92001', libraryId: '91001', title: '销售履约与收款核销指引（示例）', sections: [
    { id: '93001', heading: '分批履约与未清金额', content: '订单金额表示约定的销售金额；应收按已确认的发货签收金额计算。分批发货时，只将已履约部分计入应收。已核销收款从该部分应收扣减，未发货的订单金额不计入本次未清应收。' },
    { id: '93002', heading: '收款核销与经办复核', content: '收款登记后需要复核确认，再按客户、来源订单和未清金额逐笔核销。已核销收款、未核销余额与待收应收分别列示，不能把全部订单金额当作待收应收。演示环境只展示已有登记和核销结果。' },
  ] },
  { id: '92002', libraryId: '91001', title: '库存与预留操作规范（示例）', sections: [
    { id: '93003', heading: '在库、预留与可用', content: '在库数量由期初库存、已收货和已出库数量计算。可用数量为在库数量减去预留和冻结数量。已批准但未出库的需求可占用库存；待审核订单不表示已经出库。' },
    { id: '93004', heading: '库位与商品库存核对', content: '同一仓库各库位的商品库存汇总应与实时库存一致。本演示把每个商品放在一个存储位，收货暂存位和发货复核位没有未完成作业库存。生产计划尚未领料，不产生额外库存流水。' },
  ] },
  { id: '92003', libraryId: '91002', title: '凭证与账簿查询手册（示例）', sections: [
    { id: '93005', heading: '从业务单据核对凭证', content: '每张凭证的借方合计与贷方合计应一致。余额表、总分类账与明细分类账从同一组已记账凭证汇总。明细中的凭证号可回溯原凭证，核对银行、应收、应付与库存科目。' },
    { id: '93006', heading: '演示账簿口径', content: '样例期初含银行存款和商品库存；采购含不可抵扣税额计入库存成本，销售按样例税额拆分。演示未执行期末结转，不能把这些虚构金额用于实际申报、记账或经营决策。' },
  ] },
]

export const demoAiDocuments: AiDocument[] = sourceDrafts.map((source) => ({
  id: source.id, libraryId: source.libraryId, title: source.title, fileName: source.title + '.md', mime: 'text/markdown',
  size: new TextEncoder().encode(source.sections.map((section) => section.content).join('\n')).length,
  versionNo: 1, status: 'indexed', error: null, uploadedBy: '301', updatedAt: snapshotTime,
}))
export const demoAiSegments: AiSegment[] = sourceDrafts.flatMap((source) => source.sections.map((section, index) => ({
  ...section, documentId: source.id, seq: index + 1, pageFrom: null, pageTo: null, charCount: section.content.length, status: 'indexed',
})))
export const demoAiWiki: AiWikiPage[] = [
  { id: '94001', slug: 'sales-settlement', title: '销售履约与收款核销', summary: '区分订单金额、已确认应收、已核销收款和未清应收。',
    content: '## 怎样判断客户还欠多少\n\n先看已经确认的履约金额，再扣除核销到该笔业务的收款。尚未发货的订单金额不计入本次应收。\n\n| 金额 | 含义 |\n| --- | --- |\n| 订单金额 | 约定的销售金额 |\n| 已履约金额 | 已确认签收的销售金额 |\n| 未清应收 | 已履约金额减去已核销收款 |\n\n### 查看顺序\n\n1. 查看销售订单和关联发货单。\n2. 查看已核销收款。\n3. 用应收账龄报表核对未清金额。\n\n库存规则见 [[inventory-reservations]]。', sourceIds: ['93001', '93002'] },
  { id: '94002', slug: 'inventory-reservations', title: '在库、预留与可用库存', summary: '理解库存预留，以及库位余额和实时库存的对应关系。',
    content: '## 为什么在库数量大于可用数量\n\n已批准的订单会预留尚未发出的商品。预留不等于出库，但会减少可供其他订单使用的数量。\n\n**可用数量 = 在库数量 − 预留数量 − 冻结数量**。\n\n### 核对方法\n\n- 在实时库存查看三个数量。\n- 按仓库和商品核对库位余额。\n- 从关联订单查看尚未完成的发货数量。\n\n销售与收款规则见 [[sales-settlement]]。', sourceIds: ['93003', '93004'] },
  { id: '94003', slug: 'voucher-ledgers', title: '凭证与账簿核对', summary: '从业务记录回溯凭证，核对余额和借贷平衡。',
    content: '## 先核对凭证，再查看账簿\n\n每张凭证的借方合计与贷方合计一致。科目余额表、总分类账与明细分类账均来自同一组凭证。\n\n### 建议检查\n\n1. 核对银行存款与资金流水余额。\n2. 核对应收、应付与业务未清金额。\n3. 核对库存科目与库存金额。\n4. 点击明细中的凭证号查看来源。\n\n本演示未进行期末结转，金额只用于展示。', sourceIds: ['93005', '93006'] },
].map((page) => ({ ...page, status: 'published', revisionId: page.id + '-1' }))

export const demoAiEvidence: AiEvidence[] = demoAiSegments.map((segment) => ({
  segmentId: segment.id, title: demoAiDocuments.find((document) => document.id === segment.documentId)!.title,
  heading: segment.heading, content: segment.content, page: null, sourceIds: [segment.id],
  wikiSlug: demoAiWiki.find((page) => page.sourceIds.includes(segment.id))!.slug,
}))

export interface DemoAiAnswer {
  id: string
  question: string
  summary: string
  category: string
  content: string
  sourceIds: string[]
  tools: AiToolResult[]
  asOf: string
}

const partialOrder = documents.find((row) => row.id === '10112')!
const partialShipment = documents.find((row) => row.kind === 'shipments' && row.relatedOrderId === partialOrder.id)!
const stock = inventoryRows().find((row) => row.skuId === '403')!
export const demoAiAnswers: DemoAiAnswer[] = [
  { id: '95001', question: '星桥商贸这张订单还需要收多少款？', summary: '从订单、发货和核销记录解释未清应收。', category: '业务查数',
    content: `截至 ${snapshotDate}，星桥商贸的订单 **${partialOrder.documentNo}** 金额为 **${partialOrder.totalAmount} 元**。\n\n已确认发货签收 **${partialShipment.totalAmount} 元**，已核销收款 **${partialOrder.allocatedAmount} 元**，本次未清应收为 **${money(Number(partialShipment.totalAmount) - Number(partialOrder.allocatedAmount))} 元**。\n\n尚未履约的订单金额不计入本次应收。下面的记录可在右侧弹窗中核对。`,
    sourceIds: ['93001', '93002'], tools: [{ code: 'order_progress', label: '关联销售记录（示例）', asOf: snapshotTime, rows:
      documents.filter((row) => row.id === partialOrder.id || row.relatedOrderId === partialOrder.id).map((row) => ({ documentNo: row.documentNo, totalAmount: row.totalAmount, detailUrl: '/' + row.kind + '/' + row.id })),
    }], asOf: snapshotTime },
  { id: '95002', question: '桌面收纳盒为什么在库和可用数量不同？', summary: '结合库存预留解释可用量。', category: '库存解释',
    content: `截至 ${snapshotDate}，武汉中心仓的桌面收纳盒在库 **${stock.onHandQty} 个**，预留 **${stock.reservedQty} 个**，冻结 **${stock.blockedQty} 个**，可用 **${stock.availableQty} 个**。\n\n预留对应已批准但尚未发出的需求，因此在库量不会立即减少，可用量会先扣减。`, sourceIds: ['93003', '93004'],
    tools: [{ code: 'available_stock', label: '库存计算结果（示例）', asOf: snapshotTime, rows: [{ skuName: stock.skuName, onHandQty: stock.onHandQty, reservedQty: stock.reservedQty, blockedQty: stock.blockedQty, availableQty: stock.availableQty }] }], asOf: snapshotTime },
  { id: '95003', question: '月底怎样核对业务记录和财务账簿？', summary: '展示带来源引用的操作指引。', category: '知识问答',
    content: '建议按下面的顺序核对：\n\n1. 检查凭证借贷是否平衡。\n2. 核对银行存款与资金流水。\n3. 核对应收、应付与业务未清金额。\n4. 核对库存科目与库存金额。\n5. 从明细账的凭证号回溯原凭证。\n\n本演示未执行期末结转，以上是预设的操作指引。', sourceIds: ['93005', '93006'], tools: [], asOf: snapshotTime },
  { id: '95004', question: '能帮我审批订单并直接付款吗？', summary: '展示助手的只读边界与拒绝执行结果。', category: '权限边界',
    content: '**不能执行审批、过账或付款。**\n\n此演示只能浏览预设回答、知识资料和已有业务记录。实际业务中的审批、复核和付款仍需由有权限的人员在对应流程办理。', sourceIds: ['93002'], tools: [], asOf: snapshotTime },
]
