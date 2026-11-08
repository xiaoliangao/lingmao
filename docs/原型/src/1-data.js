/* ============================================================
 * 主数据 + 工具函数。全部虚构；今天固定 2026-11-08，方便演示逾期。
 * 单号：前缀 + yyyyMMdd + 4 位流水；款号：年份 2 位 + 季节码 + 品类码 + 3 位流水（27CF001）
 * SKU：款号-颜色码-尺码（27CF001-KQ-M）；物料 SKU：物料编码-颜色码[-规格]（ML0012-KQ）
 * 业务单据（订单、采购、库存……）不在这里写死，由 2-store.js 末尾用真实动作"演"出来，账才对得上。
 * ============================================================ */
const { reactive, computed, ref, watch, nextTick, onMounted, onUnmounted } = Vue
const TODAY = '2026-11-08'
const clock = { today: TODAY } // 演历史数据时临时改，演完改回 TODAY

/* ---------- 日期 ---------- */
const toD = (s) => new Date(s + 'T00:00:00')
const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const addDays = (s, n) => { const d = toD(s); d.setDate(d.getDate() + n); return ymd(d) }
const diffDays = (a, b) => Math.round((toD(a) - toD(b)) / 864e5)
const daysLeft = (s) => diffDays(s, clock.today)
const fd = (s) => (!s ? '—' : s.slice(0, 4) === clock.today.slice(0, 4) ? s.slice(5) : s)

/* ---------- 数字 ---------- */
const rnd = (n, d) => { const f = 10 ** d; return Math.round((n + Number.EPSILON) * f) / f }
const fq = (n) => (n == null || n === '' || Number.isNaN(n) ? '—' : rnd(+n, 3).toLocaleString('zh-CN', { maximumFractionDigits: 3 }))
const fm = (n) => (n == null || Number.isNaN(n) ? '—' : '¥' + rnd(+n, 2).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
const fpct = (a, b) => (b ? Math.min(100, Math.round((a / b) * 100)) : 0)
const sum = (arr, f = (x) => x) => arr.reduce((s, x) => s + (+f(x) || 0), 0)

/* ---------- 主数据 ---------- */
const db = reactive({
  me: 'u6',
  companies: [
    { code: 'ZJ', name: '杭州织间服饰有限公司', short: '织间服饰', status: '启用' },
    { code: 'ZJT', name: '织间（香港）贸易有限公司', short: '织间贸易', status: '启用' },
  ],
  users: [
    { id: 'u1', name: '陈晓', role: '生产跟单', scope: '本人' },
    { id: 'u2', name: '林舟', role: '采购', scope: '本人' },
    { id: 'u3', name: '周宁', role: '业务', scope: '本人' },
    { id: 'u4', name: '许然', role: '开发', scope: '全部' },
    { id: 'u5', name: '王敏', role: '仓管', scope: '全部' },
    { id: 'u6', name: '赵启', role: '管理层', scope: '全部' },
    { id: 'u7', name: '孙丽', role: '财务', scope: '全部' },
  ],
  colors: [
    { code: 'HE', name: '黑色', hex: '#232427', family: '黑白灰' },
    { code: 'MB', name: '米白', hex: '#efe8da', family: '黑白灰' },
    { code: 'BB', name: '本白', hex: '#f7f4ec', family: '黑白灰' },
    { code: 'HH', name: '花灰', hex: '#a7a8ab', family: '黑白灰' },
    { code: 'KQ', name: '卡其', hex: '#b49a6a', family: '棕' },
    { code: 'YM', name: '燕麦', hex: '#d8c8aa', family: '棕' },
    { code: 'ZQ', name: '藏青', hex: '#283350', family: '蓝' },
    { code: 'WL', name: '雾蓝', hex: '#8fa3b8', family: '蓝' },
    { code: 'JH', name: '酒红', hex: '#7b2c36', family: '红' },
  ],
  sizeGroups: [
    { id: 'W5', name: '女装字母码', sizes: ['S', 'M', 'L', 'XL', 'XXL'] },
    { id: 'M4', name: '男装字母码', sizes: ['M', 'L', 'XL', 'XXL'] },
    { id: 'F', name: '均码', sizes: ['F'] },
  ],
  seasons: [{ code: 'C', name: '春' }, { code: 'X', name: '夏' }, { code: 'Q', name: '秋' }, { code: 'D', name: '冬' }],
  categories: [
    { code: 'F', name: '风衣', path: '女装 / 外套', shape: 'coat' },
    { code: 'J', name: '夹克', path: '女装 / 外套', shape: 'top' },
    { code: 'T', name: 'T 恤', path: '女装 / 上衣', shape: 'top' },
    { code: 'W', name: '针织衫', path: '女装 / 上衣', shape: 'top' },
    { code: 'S', name: '卫衣', path: '女装 / 上衣', shape: 'top' },
    { code: 'K', name: '裤装', path: '女装 / 下装', shape: 'pants' },
    { code: 'L', name: '连衣裙', path: '女装 / 裙装', shape: 'coat' },
  ],
  materialCategories: [
    { code: 'ML', name: '梭织', parent: '面料' }, { code: 'MZ', name: '针织', parent: '面料' },
    { code: 'FL', name: '拉链', parent: '辅料' }, { code: 'FK', name: '纽扣', parent: '辅料' }, { code: 'FX', name: '线', parent: '辅料' },
    { code: 'BX', name: '唛标', parent: '包材' }, { code: 'BP', name: '吊牌', parent: '包材' }, { code: 'BD', name: '包装袋', parent: '包材' },
  ],
  dicts: {
    单位: ['米', '码', '公斤', '个', '条', '套', '张', '卷', '件'],
    结算方式: ['月结', '货到付款', '预付', '银行转账', '承兑'],
    税率: ['0%', '1%', '3%', '6%', '9%', '13%'],
    供应商分类: ['面料商', '辅料商', '成衣厂', '加工厂', '外协', '包材商', '其他'],
    订单类型: ['客户订单', '自营备货', '补单', '翻单'],
    加工方式: ['包工包料', '清加工', '半包'],
    生产节点: [['到料', 3], ['裁剪', 4], ['车缝', 10], ['后整', 3], ['包装', 2], ['入仓', 1]],
    异常类型: ['缺料', '面料质量', '做工质量', '延期', '工艺问题', '其他'],
    BOM部位: ['大身', '里布', '袋布', '领', '袖', '拉链', '纽扣', '织带', '主唛', '洗唛', '吊牌', '包装袋', '缝纫线'],
    品牌: ['织间', 'ZJ Basic'],
    波段: ['第 1 波', '第 2 波', '第 3 波', '第 4 波'],
    风格: ['通勤', '休闲', '运动'],
  },
  customers: [
    { code: 'KH0001', name: '华东某服饰有限公司', short: '华东某服饰', contact: '刘经理', phone: '138****2201', settle: '月结', term: 60, status: '启用', terms: { price: '按款报价，年度框架协议', deduct: '2%', rebate: '年度返点 1%' } },
    { code: 'KH0002', name: '南方某品牌管理有限公司', short: '南方某品牌', contact: '何小姐', phone: '139****8810', settle: '预付', term: 0, status: '启用', terms: { price: '30% 定金，出货前付清', deduct: '—', rebate: '—' } },
    { code: 'KH0003', name: '北方某电商有限公司', short: '北方某电商', contact: '马先生', phone: '136****5532', settle: '月结', term: 30, status: '启用', terms: { price: '平台结算价', deduct: '平台扣点 5%', rebate: '—' } },
  ],
  suppliers: [
    { code: 'GYS0001', short: '恒丰面料', name: '绍兴恒丰纺织有限公司', cats: ['面料商'], level: 'A', status: '合作中', contact: '吴总', phone: '135****1020', settle: '月结', term: 30, tax: '13%', buyer: 'u2', admitted: '2026-03-02', certs: [{ type: '营业执照', until: '2030-12-31' }] },
    { code: 'GYS0002', short: '鸿达辅料', name: '义乌鸿达服装辅料厂', cats: ['辅料商'], level: 'A', status: '合作中', contact: '陈姐', phone: '137****6612', settle: '月结', term: 30, tax: '3%', buyer: 'u2', admitted: '2026-03-02', certs: [{ type: '营业执照', until: '2029-06-30' }] },
    { code: 'GYS0003', short: '新雅包装', name: '杭州新雅包装材料有限公司', cats: ['包材商'], level: 'B', status: '合作中', contact: '李工', phone: '139****3345', settle: '货到付款', term: 0, tax: '13%', buyer: 'u2', admitted: '2026-04-11', certs: [{ type: '营业执照', until: '2028-01-31' }] },
    { code: 'GYS0004', short: '锦程制衣', name: '湖州锦程制衣有限公司', cats: ['成衣厂'], level: 'A', status: '合作中', contact: '孙厂长', phone: '138****7781', settle: '月结', term: 45, tax: '6%', buyer: 'u1', admitted: '2026-02-20', certs: [{ type: '营业执照', until: '2031-05-31' }, { type: '验厂报告', until: '2027-02-28' }] },
    { code: 'GYS0005', short: '华美服装厂', name: '嘉兴华美服装厂', cats: ['加工厂'], level: 'B', status: '合作中', contact: '钱老板', phone: '133****9902', settle: '月结', term: 30, tax: '3%', buyer: 'u1', admitted: '2026-05-08', certs: [{ type: '营业执照', until: '2029-09-30' }, { type: '验厂报告', until: '2026-11-30' }] },
    { code: 'GYS0006', short: '永兴制衣', name: '桐乡永兴制衣有限公司', cats: ['成衣厂'], level: 'C', status: '待准入', contact: '周经理', phone: '131****2276', settle: '月结', term: 30, tax: '3%', buyer: 'u1', admitted: null, certs: [] },
    { code: 'GYS0007', short: '盛达印绣', name: '杭州盛达印绣有限公司', cats: ['外协'], level: 'B', status: '合作中', contact: '冯师傅', phone: '186****4410', settle: '月结', term: 30, tax: '3%', buyer: 'u1', admitted: '2026-06-01', certs: [{ type: '营业执照', until: '2028-08-31' }] },
    { code: 'GYS0008', short: '利源纺织', name: '常熟利源纺织有限公司', cats: ['面料商'], level: 'D', status: '黑名单', contact: '郑总', phone: '150****8834', settle: '月结', term: 30, tax: '13%', buyer: 'u2', admitted: '2026-03-15', blackReason: '连续两批大货色差，拒不承担损失', certs: [{ type: '营业执照', until: '2027-04-30' }] },
    { code: 'GYS0009', short: '宏利针织', name: '海宁宏利针织有限公司', cats: ['面料商'], level: 'B', status: '合作中', contact: '褚总', phone: '159****6620', settle: '月结', term: 30, tax: '13%', buyer: 'u2', admitted: '2026-04-02', certs: [{ type: '营业执照', until: '2030-03-31' }] },
  ],
  warehouses: [
    { code: 'WL01', name: '主料仓', type: '物料仓', keeper: 'u5', status: '启用' },
    { code: 'CP01', name: '成品仓', type: '成品仓', keeper: 'u5', status: '启用' },
    { code: 'GC0004', name: '锦程制衣仓', type: '工厂仓', factory: 'GYS0004', status: '启用' },
    { code: 'GC0005', name: '华美服装厂仓', type: '工厂仓', factory: 'GYS0005', status: '启用' },
    { code: 'GC0006', name: '永兴制衣仓', type: '工厂仓', factory: 'GYS0006', status: '启用' },
  ],
  // 物料：price 按采购单位、含税；factor = 1 采购单位折多少库存单位
  materials: [
    { code: 'ML0012', name: '220g 斜纹棉', cat: 'ML', type: '面料', unit: '米', purUnit: '米', factor: 1, width: 148, weight: 220, comp: '100% 棉', supplier: 'GYS0001', supNo: 'HF-T220', price: 18.5, moq: 100, pack: 1, lead: 20, status: '启用',
      skus: [{ code: 'ML0012-KQ', color: 'KQ', supColor: 'C-1032', safety: 200 }, { code: 'ML0012-ZQ', color: 'ZQ', supColor: 'C-2210' }, { code: 'ML0012-HE', color: 'HE', supColor: 'C-0001' }] },
    { code: 'ML0015', name: '170T 涤纶里布', cat: 'ML', type: '里料', unit: '米', purUnit: '米', factor: 1, width: 150, weight: 60, comp: '100% 聚酯纤维', supplier: 'GYS0001', supNo: 'HF-L170', price: 6.8, moq: 200, pack: 1, lead: 10, status: '启用',
      skus: [{ code: 'ML0015-HE', color: 'HE', supColor: 'L-01' }, { code: 'ML0015-MB', color: 'MB', supColor: 'L-12' }] },
    { code: 'ML0018', name: '280g 棉涤帆布', cat: 'ML', type: '面料', unit: '米', purUnit: '米', factor: 1, width: 145, weight: 280, comp: '65% 棉 35% 涤', supplier: 'GYS0001', supNo: 'HF-C280', price: 22, moq: 100, pack: 1, lead: 15, status: '启用',
      skus: [{ code: 'ML0018-HE', color: 'HE', supColor: 'K-09' }, { code: 'ML0018-KQ', color: 'KQ', supColor: 'K-31' }] },
    { code: 'MZ0003', name: '32 支精梳棉卫衣布', cat: 'MZ', type: '面料', unit: '米', purUnit: '公斤', factor: 3.2, width: 180, weight: 320, comp: '100% 棉', supplier: 'GYS0009', supNo: 'HL-32S', price: 46, moq: 50, pack: 25, lead: 12, status: '启用',
      skus: [{ code: 'MZ0003-HE', color: 'HE' }, { code: 'MZ0003-HH', color: 'HH' }, { code: 'MZ0003-WL', color: 'WL' }, { code: 'MZ0003-MB', color: 'MB' }] },
    { code: 'MZ0007', name: '1×1 罗纹', cat: 'MZ', type: '面料', unit: '米', purUnit: '米', factor: 1, width: 100, weight: 300, comp: '95% 棉 5% 氨纶', supplier: 'GYS0009', price: 15, moq: 50, pack: 1, lead: 10, status: '启用',
      skus: [{ code: 'MZ0007-HE', color: 'HE' }, { code: 'MZ0007-HH', color: 'HH' }, { code: 'MZ0007-WL', color: 'WL' }] },
    { code: 'FL0031', name: '5# 金属拉链', cat: 'FL', type: '辅料', unit: '条', purUnit: '条', factor: 1, supplier: 'GYS0002', supNo: 'HD-Z5', price: 2.6, moq: 200, pack: 100, lead: 7, status: '启用',
      skus: [{ code: 'FL0031-HE-60', color: 'HE', spec: '60cm' }, { code: 'FL0031-HE-15', color: 'HE', spec: '15cm' }] },
    { code: 'FK0021', name: '18L 树脂纽扣', cat: 'FK', type: '辅料', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0002', supNo: 'HD-B18', price: 0.35, moq: 1000, pack: 500, lead: 5, status: '启用',
      skus: [{ code: 'FK0021-HE', color: 'HE', spec: '18L' }, { code: 'FK0021-KQ', color: 'KQ', spec: '18L' }] },
    { code: 'FX0002', name: '402 缝纫线', cat: 'FX', type: '辅料', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0002', price: 4.5, moq: 50, pack: 10, lead: 3, status: '启用',
      skus: [{ code: 'FX0002' }] },
    { code: 'BX0001', name: '主唛（织标）', cat: 'BX', type: '标签吊牌', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0003', price: 0.18, moq: 2000, pack: 1000, lead: 7, status: '启用', skus: [{ code: 'BX0001' }] },
    { code: 'BX0002', name: '洗唛', cat: 'BX', type: '标签吊牌', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0003', price: 0.08, moq: 2000, pack: 1000, lead: 7, status: '启用', skus: [{ code: 'BX0002' }] },
    { code: 'BP0001', name: '吊牌', cat: 'BP', type: '标签吊牌', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0003', price: 0.25, moq: 2000, pack: 1000, lead: 7, status: '启用', skus: [{ code: 'BP0001' }] },
    { code: 'BD0001', name: 'PE 包装袋', cat: 'BD', type: '包材', unit: '个', purUnit: '个', factor: 1, supplier: 'GYS0003', price: 0.3, moq: 1000, pack: 500, lead: 5, status: '启用', skus: [{ code: 'BD0001' }] },
  ],
  styles: [], // 由 2-store.js 用 createStyle 建，SKU 自动生成
  techpacks: [],
  orders: [], changes: [], mrps: [], purchases: [], stockDocs: [], productions: [], returns: [],
  stock: {}, // `${仓库}|${物料SKU}|${物权}` → 数量（库存单位）；物权 = '自有' 或客户编码
  stockLogs: [],
  seq: {},
  oplog: [],
})

/* ---------- 查找 ---------- */
const me = () => db.users.find((u) => u.id === db.me)
const userName = (id) => db.users.find((u) => u.id === id)?.name || '—'
const colorOf = (c) => db.colors.find((x) => x.code === c) || { code: c, name: c || '—', hex: 'transparent' }
const colorName = (c) => (c ? colorOf(c).name : '—')
const sizesOf = (gid) => db.sizeGroups.find((g) => g.id === gid)?.sizes || []
const catOf = (c) => db.categories.find((x) => x.code === c) || { code: c, name: c, shape: 'top' }
const sup = (c) => db.suppliers.find((s) => s.code === c)
const supName = (c) => sup(c)?.short || c || '—'
const cust = (c) => db.customers.find((x) => x.code === c)
const custName = (c) => cust(c)?.short || c || '—'
const ownerName = (o) => (!o || o === '自有' ? '自有' : '客供 · ' + custName(o))
const wh = (c) => db.warehouses.find((w) => w.code === c)
const whName = (c) => wh(c)?.name || c || '—'
const factoryWh = (supCode) => db.warehouses.find((w) => w.factory === supCode)
const mat = (c) => db.materials.find((m) => m.code === c)
const matOfSku = (s) => db.materials.find((m) => m.skus.some((k) => k.code === s))
const skuOf = (s) => matOfSku(s)?.skus.find((k) => k.code === s)
const skuName = (s) => { const m = matOfSku(s), k = skuOf(s); if (!m) return s; return [m.name, k.color && colorName(k.color), k.spec].filter(Boolean).join(' · ') }
const unitOf = (s) => matOfSku(s)?.unit || ''
const style = (c) => db.styles.find((s) => s.code === c)
const styleName = (c) => style(c)?.name || c
const order = (no) => db.orders.find((o) => o.no === no)
const purchase = (no) => db.purchases.find((p) => p.no === no)
const production = (no) => db.productions.find((p) => p.no === no)
const stockDoc = (no) => db.stockDocs.find((d) => d.no === no)
const mrp = (no) => db.mrps.find((m) => m.no === no)
const stockKey = (w, s, o) => `${w}|${s}|${o || '自有'}`
const stockQty = (w, s, o) => db.stock[stockKey(w, s, o)] || 0
