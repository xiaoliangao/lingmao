/* ============================================================
 * 假数据：全部虚构。今天固定为 2026-11-05，方便演示逾期、延期。
 * 单号：前缀 + yyyyMMdd + 4 位流水（与 SCM 现有单号生成器一致）
 * 款号：年份 2 位 + 季节码（春 C 夏 X 秋 Q 冬 D）+ 品类码 + 3 位流水，如 27CF001
 * SKU：款号-颜色码-尺码，如 27CF001-KQ-M；物料 SKU：物料编码-颜色码[-规格]
 * 贯穿 PRD 的例子：华东某服饰订 27CF001 女式风衣 3 色 × 5 码共 1200 件，交期 2027-03-20
 * ============================================================ */
const { reactive, ref, computed, watch, nextTick } = Vue
const TODAY = '2026-11-05'
const SIZE5 = (s, m, l, xl, xxl) => ({ S: s, M: m, L: l, XL: xl, XXL: xxl })
const SIZE4 = (s, m, l, xl) => ({ S: s, M: m, L: l, XL: xl })

const db = reactive({
  me: { name: '陈晓', role: '生产跟单' },
  company: '杭州织间服饰有限公司',

  colors: [
    { code: 'HE', name: '黑色', hex: '#25272c', family: '黑灰' },
    { code: 'MB', name: '米白', hex: '#eee7d8', family: '白' },
    { code: 'KQ', name: '卡其', hex: '#b39a6c', family: '棕' },
    { code: 'WL', name: '雾蓝', hex: '#8ea2b7', family: '蓝' },
    { code: 'YM', name: '燕麦', hex: '#d6c6aa', family: '棕' },
    { code: 'ZQ', name: '藏青', hex: '#27324e', family: '蓝' },
    { code: 'JH', name: '酒红', hex: '#7a2b34', family: '红' },
    { code: 'BB', name: '本白', hex: '#f6f3ea', family: '白' },
  ],

  sizeGroups: [
    { id: 'W5', name: '女装 S-XXL', sizes: ['S', 'M', 'L', 'XL', 'XXL'] },
    { id: 'W4', name: '女装 S-XL', sizes: ['S', 'M', 'L', 'XL'] },
    { id: 'M4', name: '男装 M-XXL', sizes: ['M', 'L', 'XL', 'XXL'] },
    { id: 'F', name: '均码', sizes: ['F'] },
  ],

  styleCategories: [
    { code: 'F', name: '风衣' }, { code: 'J', name: '夹克' }, { code: 'T', name: 'T 恤' },
    { code: 'W', name: '针织衫' }, { code: 'S', name: '卫衣' }, { code: 'K', name: '裤装' },
  ],

  materialCategories: [
    { id: 'FAB', label: '面料', children: [{ id: 'FAB-W', label: '梭织' }, { id: 'FAB-K', label: '针织' }] },
    { id: 'LIN', label: '里料' },
    { id: 'ACC', label: '辅料', children: [{ id: 'ACC-Z', label: '拉链' }, { id: 'ACC-B', label: '纽扣' }, { id: 'ACC-I', label: '衬' }] },
    { id: 'TAG', label: '标签吊牌' },
    { id: 'PKG', label: '包材' },
  ],

  customers: [
    { id: 'C001', short: '华东某服饰', name: '华东某服饰有限公司', contact: '刘倩', settle: '月结 60 天' },
    { id: 'C002', short: '拾光品牌', name: '上海拾光品牌管理有限公司', contact: '程雨', settle: '月结 60 天' },
    { id: 'C003', short: '青禾服饰', name: '杭州青禾服饰有限公司', contact: '许宁', settle: '月结 30 天' },
  ],

  suppliers: [
    { id: 'S001', short: '澜庭纺织', name: '绍兴澜庭纺织有限公司', cat: '面料商', level: 'A', status: '合作中', settle: '月结', days: 30, invoice: '专票 13%', contact: '周海峰', phone: '138****1201', buyer: '吴佳', certs: [{ name: '营业执照', until: '长期' }, { name: '验厂报告', until: '2027-03-31' }] },
    { id: 'S002', short: '云织面料', name: '苏州云织面料有限公司', cat: '面料商', level: 'B', status: '合作中', settle: '月结', days: 60, invoice: '专票 13%', contact: '沈立', phone: '139****8810', buyer: '吴佳', certs: [{ name: '营业执照', until: '长期' }, { name: 'OEKO-TEX 证书', until: '2026-10-31' }] },
    { id: 'S003', short: '恒扣辅料', name: '义乌恒扣辅料有限公司', cat: '辅料商', level: 'A', status: '合作中', settle: '现结', days: 0, invoice: '普票', contact: '楼晓燕', phone: '135****6620', buyer: '吴佳', certs: [{ name: '营业执照', until: '长期' }] },
    { id: 'S004', short: '某某制衣厂', name: '东莞某某制衣厂', cat: '成衣厂', level: 'A', status: '合作中', settle: '月结', days: 30, invoice: '专票 13%', contact: '黄志强', phone: '137****3305', buyer: '陈晓', certs: [{ name: '营业执照', until: '长期' }, { name: '验厂报告', until: '2027-01-15' }] },
    { id: 'S005', short: '明辉服装', name: '湖州明辉服装加工厂', cat: '加工厂', level: 'B', status: '合作中', settle: '月结', days: 30, invoice: '不开票', contact: '钱明辉', phone: '136****0071', buyer: '陈晓', certs: [{ name: '营业执照', until: '长期' }] },
    { id: 'S006', short: '印象印绣', name: '杭州印象印绣有限公司', cat: '外协（印绣洗）', level: '—', status: '待准入', settle: '月结', days: 30, invoice: '专票 6%', contact: '方琳', phone: '158****2290', buyer: '陈晓', certs: [] },
    { id: 'S007', short: '速达包装', name: '广州速达包装材料厂', cat: '包材', level: 'C', status: '停用', settle: '现结', days: 0, invoice: '普票', contact: '邓凯', phone: '133****5512', buyer: '吴佳', certs: [{ name: '营业执照', until: '长期' }] },
    { id: 'S008', short: '诚信面料', name: '嘉兴诚信面料行', cat: '面料商', level: '—', status: '黑名单', settle: '现结', days: 0, invoice: '普票', contact: '马强', phone: '152****9083', buyer: '吴佳', certs: [], blackReason: '2026-09 两批面料克重不足、以次充好，经理会议决定拉黑' },
    { id: 'S009', short: '锦标织唛', name: '温州锦标织唛厂', cat: '辅料商', level: 'B', status: '合作中', settle: '月结', days: 30, invoice: '普票', contact: '叶青', phone: '139****4456', buyer: '吴佳', certs: [{ name: '营业执照', until: '长期' }] },
  ],

  warehouses: [
    { code: 'WH01', name: '主料仓', type: '物料仓', factory: '', address: '杭州市余杭区仓前街道 8 号库', keeper: '赵立', status: '启用' },
    { code: 'WH02', name: '辅料仓', type: '物料仓', factory: '', address: '杭州市余杭区仓前街道 8 号库二楼', keeper: '赵立', status: '启用' },
    { code: 'WH09', name: '成品中转仓', type: '成品仓', factory: '', address: '杭州市余杭区仓前街道 9 号库', keeper: '孙婷', status: '启用' },
    { code: 'WF004', name: '某某制衣厂·工厂仓', type: '工厂仓', factory: 'S004', address: '东莞（虚拟仓：料在工厂）', keeper: '陈晓', status: '启用' },
    { code: 'WF005', name: '明辉服装·工厂仓', type: '工厂仓', factory: 'S005', address: '湖州（虚拟仓：料在工厂）', keeper: '陈晓', status: '启用' },
  ],

  materials: [
    { code: 'ML0001', name: '220g 精梳棉汁布', cat: 'FAB-K', type: '面料', unit: '米', buyUnit: '公斤', rate: 2.53, width: 180, gsm: 220, comp: '100% 棉', supplier: 'S001', supplierNo: 'LT-2207', price: 18.5, moq: 25, lead: 7, status: '启用',
      skus: [{ sku: 'ML0001-HE', color: 'HE', colorNo: 'LT-2207-01', spec: '', safety: 100 }, { sku: 'ML0001-MB', color: 'MB', colorNo: 'LT-2207-02', spec: '', safety: 100 }, { sku: 'ML0001-WL', color: 'WL', colorNo: 'LT-2207-07', spec: '', safety: 50 }] },
    { code: 'ML0002', name: '220g 斜纹棉', cat: 'FAB-W', type: '面料', unit: '米', buyUnit: '米', rate: 1, width: 148, gsm: 220, comp: '100% 棉', supplier: 'S002', supplierNo: 'YZ-8803', price: 24, moq: 100, lead: 10, status: '启用',
      skus: [{ sku: 'ML0002-KQ', color: 'KQ', colorNo: 'YZ-8803-K2', spec: '', safety: 0 }, { sku: 'ML0002-ZQ', color: 'ZQ', colorNo: 'YZ-8803-N1', spec: '', safety: 0 }, { sku: 'ML0002-HE', color: 'HE', colorNo: 'YZ-8803-B', spec: '', safety: 0 }] },
    { code: 'ML0003', name: '涤纶里布 210T', cat: 'LIN', type: '里料', unit: '米', buyUnit: '米', rate: 1, width: 150, gsm: 75, comp: '100% 涤', supplier: 'S002', supplierNo: 'YZ-210T', price: 6.8, moq: 100, lead: 5, status: '启用',
      skus: [{ sku: 'ML0003-KQ', color: 'KQ', colorNo: '210T-17', spec: '', safety: 0 }, { sku: 'ML0003-ZQ', color: 'ZQ', colorNo: '210T-33', spec: '', safety: 0 }, { sku: 'ML0003-HE', color: 'HE', colorNo: '210T-黑', spec: '', safety: 0 }] },
    { code: 'ML0004', name: '1×1 棉氨罗纹', cat: 'FAB-K', type: '面料', unit: '米', buyUnit: '公斤', rate: 3.2, width: 100, gsm: 280, comp: '95% 棉 5% 氨纶', supplier: 'S001', supplierNo: 'LT-R11', price: 22, moq: 10, lead: 7, status: '启用',
      skus: [{ sku: 'ML0004-HE', color: 'HE', colorNo: 'R11-01', spec: '', safety: 20 }, { sku: 'ML0004-MB', color: 'MB', colorNo: 'R11-02', spec: '', safety: 20 }, { sku: 'ML0004-WL', color: 'WL', colorNo: 'R11-07', spec: '', safety: 10 }, { sku: 'ML0004-YM', color: 'YM', colorNo: 'R11-12', spec: '', safety: 10 }] },
    { code: 'ML0005', name: '320g 棉灯芯绒', cat: 'FAB-W', type: '面料', unit: '米', buyUnit: '米', rate: 1, width: 145, gsm: 320, comp: '100% 棉', supplier: 'S001', supplierNo: 'LT-C14', price: 21, moq: 100, lead: 10, status: '启用',
      skus: [{ sku: 'ML0005-KQ', color: 'KQ', colorNo: 'C14-05', spec: '', safety: 0 }, { sku: 'ML0005-ZQ', color: 'ZQ', colorNo: 'C14-11', spec: '', safety: 0 }] },
    { code: 'FL0001', name: '5# 金属拉链（古铜）', cat: 'ACC-Z', type: '辅料', unit: '条', buyUnit: '条', rate: 1, width: null, gsm: null, comp: '铜', supplier: 'S003', supplierNo: 'HK-Z5', price: 1.8, moq: 200, lead: 5, status: '启用',
      skus: [{ sku: 'FL0001-60', color: '', colorNo: '', spec: '60cm', safety: 0 }, { sku: 'FL0001-65', color: '', colorNo: '', spec: '65cm', safety: 0 }] },
    { code: 'FL0002', name: '树脂四眼扣 24L', cat: 'ACC-B', type: '辅料', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '树脂', supplier: 'S003', supplierNo: 'HK-B24', price: 0.25, moq: 1000, lead: 5, status: '启用',
      skus: [{ sku: 'FL0002-KQ', color: 'KQ', colorNo: 'B24-卡', spec: '24L', safety: 0 }, { sku: 'FL0002-ZQ', color: 'ZQ', colorNo: 'B24-藏', spec: '24L', safety: 0 }, { sku: 'FL0002-HE', color: 'HE', colorNo: 'B24-黑', spec: '24L', safety: 0 }] },
    { code: 'FL0003', name: '无纺黏合衬', cat: 'ACC-I', type: '辅料', unit: '米', buyUnit: '米', rate: 1, width: 100, gsm: 30, comp: '涤', supplier: 'S003', supplierNo: 'HK-N30', price: 3.2, moq: 100, lead: 3, status: '启用',
      skus: [{ sku: 'FL0003-BB', color: 'BB', colorNo: '本白', spec: '', safety: 50 }] },
    { code: 'BQ0001', name: '自有品牌主唛（织标）', cat: 'TAG', type: '标签吊牌', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '涤', supplier: 'S009', supplierNo: 'JB-M01', price: 0.12, moq: 2000, lead: 7, status: '启用',
      skus: [{ sku: 'BQ0001-HE', color: 'HE', colorNo: '黑底白字', spec: '5×2cm', safety: 500 }] },
    { code: 'BQ0002', name: '吊牌（含吊粒）', cat: 'TAG', type: '标签吊牌', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '纸', supplier: 'S009', supplierNo: 'JB-D02', price: 0.35, moq: 2000, lead: 7, status: '启用',
      skus: [{ sku: 'BQ0002-BB', color: 'BB', colorNo: '', spec: '9×5cm', safety: 500 }] },
    { code: 'BQ0003', name: '华东主唛（客供）', cat: 'TAG', type: '标签吊牌', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '涤', supplier: '', supplierNo: '', price: 0, moq: 0, lead: 0, status: '启用',
      skus: [{ sku: 'BQ0003-HE', color: 'HE', colorNo: '客户提供', spec: '', safety: 0 }] },
    { code: 'BQ0004', name: '洗水唛', cat: 'TAG', type: '标签吊牌', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '缎带', supplier: 'S009', supplierNo: 'JB-X04', price: 0.08, moq: 2000, lead: 7, status: '启用',
      skus: [{ sku: 'BQ0004-BB', color: 'BB', colorNo: '', spec: '3×8cm', safety: 500 }] },
    { code: 'BQ0005', name: '拾光主唛（客供）', cat: 'TAG', type: '标签吊牌', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: '涤', supplier: '', supplierNo: '', price: 0, moq: 0, lead: 0, status: '启用',
      skus: [{ sku: 'BQ0005-HE', color: 'HE', colorNo: '客户提供', spec: '', safety: 0 }] },
    { code: 'BC0001', name: 'OPP 自封袋', cat: 'PKG', type: '包材', unit: '个', buyUnit: '个', rate: 1, width: null, gsm: null, comp: 'OPP', supplier: 'S007', supplierNo: 'SD-40', price: 0.12, moq: 1000, lead: 3, status: '停用',
      skus: [{ sku: 'BC0001-4050', color: '', colorNo: '', spec: '40×50cm', safety: 0 }] },
  ],

  styles: [
    { code: '27CF001', name: '女式风衣', cat: '外套 / 风衣', year: 2027, season: '春', wave: 'C1', styleTag: '通勤', gender: '女', designer: '林夏', source: '样衣转大货', sample: 'YY202609050001', customer: 'C001', customerNo: 'HD-W2701', sizeGroup: 'W5', colors: ['KQ', 'ZQ', 'HE'], tagPrice: 699, comp: '100% 棉', standard: 'GB/T 2664-2017', safety: 'B 类', launch: '2027-02-20', factory: 'S004', status: '启用', shape: 'coat', disabledSkus: [] },
    { code: '27CT002', name: '女式圆领短袖 T 恤', cat: '上衣 / T 恤', year: 2027, season: '春', wave: 'C2', styleTag: '基础', gender: '女', designer: '苏禾', source: '自主开发', sample: '', customer: '', customerNo: '', sizeGroup: 'W4', colors: ['HE', 'MB', 'WL'], tagPrice: 129, comp: '100% 棉', standard: 'GB/T 22849-2014', safety: 'B 类', launch: '2027-03-01', factory: 'S005', status: '启用', shape: 'tee', disabledSkus: [] },
    { code: '27CW003', name: '女式针织开衫', cat: '上衣 / 针织衫', year: 2027, season: '春', wave: 'C1', styleTag: '慵懒', gender: '女', designer: '林夏', source: '客户来样', sample: '', customer: 'C003', customerNo: 'QH-2711', sizeGroup: 'W4', colors: ['YM', 'MB'], tagPrice: 259, comp: '95% 棉 5% 氨纶', standard: 'FZ/T 73018-2021', safety: 'B 类', launch: '2027-03-10', factory: 'S005', status: '启用', shape: 'cardigan', disabledSkus: [] },
    { code: '26QJ002', name: '女式灯芯绒短夹克', cat: '外套 / 夹克', year: 2026, season: '秋', wave: 'Q3', styleTag: '复古', gender: '女', designer: '苏禾', source: '自主开发', sample: '', customer: 'C002', customerNo: 'SG-J2609', sizeGroup: 'W4', colors: ['KQ', 'ZQ'], tagPrice: 459, comp: '100% 棉', standard: 'GB/T 2664-2017', safety: 'B 类', launch: '2026-10-01', factory: 'S004', status: '启用', shape: 'coat', disabledSkus: [] },
    { code: '25DS011', name: '男式连帽卫衣', cat: '上衣 / 卫衣', year: 2025, season: '冬', wave: 'D1', styleTag: '运动', gender: '男', designer: '苏禾', source: '自主开发', sample: '', customer: '', customerNo: '', sizeGroup: 'M4', colors: ['HE', 'ZQ'], tagPrice: 199, comp: '80% 棉 20% 涤', standard: 'FZ/T 73043-2012', safety: 'B 类', launch: '2025-10-15', factory: 'S004', status: '停用', shape: 'hoodie', disabledSkus: [] },
  ],

  // 资料包：一个版本 = BOM + 工艺单（尺寸表、吊牌包装资料二期加进来）
  packs: {
    '27CF001': [
      {
        ver: 'V2', status: '已生效', date: '2026-10-14', by: '林夏', note: '大身损耗 2% → 3%（斜纹面料对条损耗大）',
        bom: [
          { part: '大身', mat: 'ML0002', colorMode: '随款色', colorMap: { KQ: 'ML0002-KQ', ZQ: 'ML0002-ZQ', HE: 'ML0002-HE' }, qtyMode: '分码', usage: SIZE5(2.25, 2.35, 2.45, 2.55, 2.65), loss: 3, source: '我方采购' },
          { part: '里布', mat: 'ML0003', colorMode: '随款色', colorMap: { KQ: 'ML0003-KQ', ZQ: 'ML0003-ZQ', HE: 'ML0003-HE' }, qtyMode: '统一', usage: 1.65, loss: 3, source: '我方采购' },
          { part: '粘衬', mat: 'FL0003', colorMode: '固定', fixedSku: 'FL0003-BB', qtyMode: '统一', usage: 0.45, loss: 5, source: '我方采购' },
          { part: '门襟扣', mat: 'FL0002', colorMode: '随款色', colorMap: { KQ: 'FL0002-KQ', ZQ: 'FL0002-ZQ', HE: 'FL0002-HE' }, qtyMode: '统一', usage: 8, loss: 2, source: '我方采购' },
          { part: '主唛', mat: 'BQ0003', colorMode: '固定', fixedSku: 'BQ0003-HE', qtyMode: '统一', usage: 1, loss: 1, source: '客供' },
          { part: '洗水唛', mat: 'BQ0004', colorMode: '固定', fixedSku: 'BQ0004-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '吊牌', mat: 'BQ0002', colorMode: '固定', fixedSku: 'BQ0002-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '包装袋', mat: 'BC0001', colorMode: '固定', fixedSku: 'BC0001-4050', qtyMode: '统一', usage: 1, loss: 0, source: '加工厂自备' },
        ],
        process: [
          { name: '裁剪', type: '裁剪', req: '大身、里布分床排料；斜纹面料单向排料，同一件不得跨缸', sec: 120, price: 2.6 },
          { name: '粘衬', type: '车缝', req: '前门襟、领面、袋盖粘衬，130℃ 压 12 秒，冷却后再车', sec: 90, price: 1.2 },
          { name: '合缝', type: '车缝', req: '合肩缝、侧缝、袖缝，缝份 1cm，针距 3cm 12 针', sec: 600, price: 7.5 },
          { name: '绱领', type: '车缝', req: '领面领里分开绱，领尖左右对称，误差 ≤ 0.3cm', sec: 420, price: 5.5 },
          { name: '绱袖', type: '车缝', req: '袖山吃势均匀，袖底十字缝对齐', sec: 480, price: 6.0 },
          { name: '装里布', type: '车缝', req: '下摆里布比面布短 2cm，留活摆', sec: 540, price: 6.5 },
          { name: '锁眼钉扣', type: '后道', req: '门襟 6 眼、袖袢 2 眼，扣位按纸样', sec: 240, price: 3.0 },
          { name: '整烫', type: '后道', req: '垫布整烫，不得出极光；腰带单独整烫', sec: 300, price: 3.2 },
          { name: '包装', type: '包装', req: '吊牌挂左侧袖底，折叠后入袋，每箱不超过 20 件', sec: 90, price: 0.8 },
        ],
      },
      {
        ver: 'V1', status: '已作废', date: '2026-09-28', by: '林夏', note: '样衣转大货自动生成',
        bom: [
          { part: '大身', mat: 'ML0002', colorMode: '随款色', colorMap: { KQ: 'ML0002-KQ', ZQ: 'ML0002-ZQ', HE: 'ML0002-HE' }, qtyMode: '分码', usage: SIZE5(2.25, 2.35, 2.45, 2.55, 2.65), loss: 2, source: '我方采购' },
          { part: '里布', mat: 'ML0003', colorMode: '随款色', colorMap: { KQ: 'ML0003-KQ', ZQ: 'ML0003-ZQ', HE: 'ML0003-HE' }, qtyMode: '统一', usage: 1.65, loss: 3, source: '我方采购' },
        ],
        process: [],
      },
    ],
    '27CT002': [
      {
        ver: 'V1', status: '已生效', date: '2026-10-30', by: '苏禾', note: '首版',
        bom: [
          { part: '大身', mat: 'ML0001', colorMode: '随款色', colorMap: { HE: 'ML0001-HE', MB: 'ML0001-MB', WL: 'ML0001-WL' }, qtyMode: '分码', usage: SIZE4(0.95, 1.0, 1.06, 1.12), loss: 5, source: '我方采购' },
          { part: '领口罗纹', mat: 'ML0004', colorMode: '随款色', colorMap: { HE: 'ML0004-HE', MB: 'ML0004-MB', WL: 'ML0004-WL' }, qtyMode: '统一', usage: 0.08, loss: 5, source: '我方采购' },
          { part: '主唛', mat: 'BQ0001', colorMode: '固定', fixedSku: 'BQ0001-HE', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '洗水唛', mat: 'BQ0004', colorMode: '固定', fixedSku: 'BQ0004-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '吊牌', mat: 'BQ0002', colorMode: '固定', fixedSku: 'BQ0002-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '包装袋', mat: 'BC0001', colorMode: '固定', fixedSku: 'BC0001-4050', qtyMode: '统一', usage: 1, loss: 0, source: '加工厂自备' },
        ],
        process: [
          { name: '裁剪', type: '裁剪', req: '汁布松布 24 小时后再裁，防缩', sec: 40, price: 0.8 },
          { name: '合肩', type: '车缝', req: '四线包缝，肩缝加纱带', sec: 60, price: 0.9 },
          { name: '绱领', type: '车缝', req: '罗纹领拉量均匀，领圈不起波浪', sec: 90, price: 1.4 },
          { name: '绱袖合侧', type: '车缝', req: '袖底十字缝对齐', sec: 120, price: 1.6 },
          { name: '冚下摆袖口', type: '车缝', req: '双针冚车，宽 2cm', sec: 80, price: 1.0 },
          { name: '整烫包装', type: '包装', req: '吊牌挂左侧腋下，单件入袋', sec: 60, price: 0.8 },
        ],
      },
    ],
    '27CW003': [
      {
        ver: 'V1', status: '草稿', date: '2026-11-03', by: '林夏', note: '客户来样，用量待版房确认',
        bom: [
          { part: '大身', mat: 'ML0004', colorMode: '随款色', colorMap: { YM: 'ML0004-YM', MB: 'ML0004-MB' }, qtyMode: '统一', usage: 0.9, loss: 6, source: '我方采购' },
          { part: '门襟扣', mat: 'FL0002', colorMode: '固定', fixedSku: 'FL0002-KQ', qtyMode: '统一', usage: 5, loss: 2, source: '我方采购' },
        ],
        process: [],
      },
    ],
    '26QJ002': [
      {
        ver: 'V1', status: '已生效', date: '2026-09-20', by: '苏禾', note: '首版',
        bom: [
          { part: '大身', mat: 'ML0005', colorMode: '随款色', colorMap: { KQ: 'ML0005-KQ', ZQ: 'ML0005-ZQ' }, qtyMode: '分码', usage: SIZE4(1.55, 1.62, 1.7, 1.78), loss: 4, source: '我方采购' },
          { part: '里布', mat: 'ML0003', colorMode: '随款色', colorMap: { KQ: 'ML0003-KQ', ZQ: 'ML0003-ZQ' }, qtyMode: '统一', usage: 1.2, loss: 3, source: '我方采购' },
          { part: '门襟拉链', mat: 'FL0001', colorMode: '固定', fixedSku: 'FL0001-60', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '粘衬', mat: 'FL0003', colorMode: '固定', fixedSku: 'FL0003-BB', qtyMode: '统一', usage: 0.35, loss: 5, source: '我方采购' },
          { part: '主唛', mat: 'BQ0005', colorMode: '固定', fixedSku: 'BQ0005-HE', qtyMode: '统一', usage: 1, loss: 1, source: '客供' },
          { part: '洗水唛', mat: 'BQ0004', colorMode: '固定', fixedSku: 'BQ0004-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
          { part: '吊牌', mat: 'BQ0002', colorMode: '固定', fixedSku: 'BQ0002-BB', qtyMode: '统一', usage: 1, loss: 1, source: '我方采购' },
        ],
        process: [],
      },
    ],
    '25DS011': [{ ver: 'V3', status: '已生效', date: '2025-08-20', by: '苏禾', note: '加厚帽里', bom: [], process: [] }],
  },

  orders: [
    {
      no: 'DD202611050001', type: '客户订单', customer: 'C001', contract: 'HD-PO-2711-03', date: '2026-11-05', due: '2027-03-20', promise: '', follower: '陈晓', sales: '何蔓', currency: 'CNY',
      delivery: '华东某服饰苏州仓；按色按码装箱，每箱不超过 20 件', status: '待审核', auditBy: '', auditAt: '', note: '春季主推款，客户要求 3 月 20 日前全部到仓',
      lines: [
        { style: '27CF001', pack: '', price: 238, qty: { KQ: SIZE5(80, 120, 120, 60, 20), ZQ: SIZE5(80, 120, 120, 60, 20), HE: SIZE5(80, 120, 120, 60, 20) } },
      ],
      changes: [],
    },
    {
      no: 'DD202610150001', type: '客户订单', customer: 'C002', contract: 'SG-PO-2610-07', date: '2026-10-15', due: '2026-11-25', promise: '2026-11-22', follower: '陈晓', sales: '何蔓', currency: 'CNY',
      delivery: '上海市青浦区拾光物流仓；按色按码装箱', status: '生产中', auditBy: '何蔓', auditAt: '2026-10-16 10:12', note: '',
      lines: [{ style: '26QJ002', pack: 'V1', price: 186, qty: { KQ: SIZE4(60, 120, 120, 60), ZQ: SIZE4(40, 80, 80, 40) } }],
      changes: [
        { no: 'BG202610200001', type: '数量', detail: '藏青 L 70 → 80、XL 30 → 40（客户追加 20 件）', reason: '客户追加', duty: '客户', by: '陈晓', at: '2026-10-20 15:40', status: '已审核', effect: '加工单 JG202610170002 已同步数量；里布需追加采购 25 米' },
      ],
    },
    {
      no: 'DD202610280001', type: '翻单', customer: 'C002', contract: 'SG-PO-2610-19', date: '2026-10-28', due: '2026-12-15', promise: '2026-12-12', follower: '陈晓', sales: '何蔓', currency: 'CNY', source: 'DD202610150001',
      delivery: '上海市青浦区拾光物流仓', status: '待排单', auditBy: '何蔓', auditAt: '2026-10-29 09:30', note: '翻单，资料包沿用 V1',
      lines: [{ style: '26QJ002', pack: 'V1', price: 186, qty: { KQ: SIZE4(20, 40, 40, 20), ZQ: SIZE4(20, 40, 40, 20) } }],
      changes: [],
    },
    {
      no: 'DD202611040001', type: '自营备货', customer: '', contract: '', date: '2026-11-04', due: '2027-02-28', promise: '', follower: '陈晓', sales: '', currency: 'CNY',
      delivery: '杭州成品中转仓（WH09），到货后推聚水潭入库', status: '待审核', auditBy: '', auditAt: '', note: '春季基础款首单',
      lines: [{ style: '27CT002', pack: '', price: 42, qty: { HE: SIZE4(100, 200, 200, 100), MB: SIZE4(80, 160, 160, 80), WL: SIZE4(50, 100, 100, 50) } }],
      changes: [],
    },
    {
      no: 'DD202611050002', type: '客户订单', customer: 'C003', contract: '', date: '2026-11-05', due: '2027-01-10', promise: '', follower: '陈晓', sales: '何蔓', currency: 'CNY',
      delivery: '杭州市拱墅区青禾仓', status: '草稿', auditBy: '', auditAt: '', note: '',
      lines: [{ style: '27CW003', pack: '', price: 96, qty: { YM: SIZE4(50, 100, 100, 50), MB: SIZE4(30, 60, 60, 30) } }],
      changes: [],
    },
    {
      no: 'DD202609120001', type: '自营备货', customer: '', contract: '', date: '2026-09-12', due: '2026-10-20', promise: '2026-10-18', follower: '陈晓', sales: '', currency: 'CNY',
      delivery: '杭州成品中转仓（WH09）', status: '已完成', auditBy: '何蔓', auditAt: '2026-09-13 11:02', note: '',
      lines: [{ style: '25DS011', pack: 'V3', price: 96, qty: { HE: { M: 60, L: 90, XL: 90, XXL: 60 }, ZQ: { M: 40, L: 60, XL: 60, XXL: 40 } } }],
      changes: [],
    },
  ],

  production: [
    {
      no: 'JG202610170001', order: 'DD202610150001', style: '26QJ002', pack: 'V1', factory: 'S004', mode: '清加工', price: 52, date: '2026-10-17', due: '2026-11-20', follower: '陈晓', status: '生产中',
      qty: { KQ: SIZE4(60, 120, 120, 60) }, received: {}, defects: 0, terms: '次品率超 2% 的部分按加工价 2 倍扣款；每延期一天扣总加工费 1%',
      nodes: [
        { name: '到料', ps: '2026-10-18', pe: '2026-10-24', as: '2026-10-19', ae: '2026-10-26', qty: 360 },
        { name: '裁剪', ps: '2026-10-25', pe: '2026-10-28', as: '2026-10-27', ae: '2026-10-29', qty: 360 },
        { name: '车缝', ps: '2026-10-29', pe: '2026-11-12', as: '2026-10-30', ae: '', qty: 210 },
        { name: '后整', ps: '2026-11-12', pe: '2026-11-16', as: '', ae: '', qty: 0 },
        { name: '包装', ps: '2026-11-16', pe: '2026-11-18', as: '', ae: '', qty: 0 },
        { name: '入仓', ps: '2026-11-19', pe: '2026-11-20', as: '', ae: '', qty: 0 },
      ],
      issues: [],
      receipts: [],
      reports: [
        { at: '2026-11-04 18:20', by: '陈晓', node: '车缝', qty: 210, note: '两条线在做，日产 40 件左右' },
        { at: '2026-10-29 17:05', by: '陈晓', node: '裁剪', qty: 360, note: '裁完，卡其两缸，已分缸打包' },
      ],
    },
    {
      no: 'JG202610170002', order: 'DD202610150001', style: '26QJ002', pack: 'V1', factory: 'S005', mode: '清加工', price: 50, date: '2026-10-17', due: '2026-11-15', follower: '陈晓', status: '生产中',
      qty: { ZQ: SIZE4(40, 80, 80, 40) }, received: {}, defects: 0, terms: '同主合同',
      nodes: [
        { name: '到料', ps: '2026-10-18', pe: '2026-10-24', as: '2026-10-19', ae: '', qty: 0 },
        { name: '裁剪', ps: '2026-10-25', pe: '2026-10-27', as: '2026-10-25', ae: '2026-10-27', qty: 240 },
        { name: '车缝', ps: '2026-10-28', pe: '2026-11-04', as: '2026-10-28', ae: '', qty: 150 },
        { name: '后整', ps: '2026-11-05', pe: '2026-11-09', as: '', ae: '', qty: 0 },
        { name: '包装', ps: '2026-11-09', pe: '2026-11-11', as: '', ae: '', qty: 0 },
        { name: '入仓', ps: '2026-11-12', pe: '2026-11-15', as: '', ae: '', qty: 0 },
      ],
      issues: [
        { type: '缺料', desc: '藏青里布 ML0003-ZQ 供应商延期，装里布工序停了', duty: '供应商（云织面料）', by: '陈晓', at: '2026-10-30 10:15', action: '已催料，云织承诺 11-07 发货；车缝先做不带里布的工序', newDue: '2026-11-18', status: '处理中' },
      ],
      receipts: [],
      reports: [{ at: '2026-11-03 16:40', by: '陈晓', node: '车缝', qty: 150, note: '等里布，只能先做外壳' }],
    },
    {
      no: 'JG202609150001', order: 'DD202609120001', style: '25DS011', pack: 'V3', factory: 'S004', mode: '包工包料', price: 96, date: '2026-09-15', due: '2026-10-18', follower: '陈晓', status: '已完成',
      qty: { HE: { M: 60, L: 90, XL: 90, XXL: 60 }, ZQ: { M: 40, L: 60, XL: 60, XXL: 40 } }, received: { HE: { M: 60, L: 90, XL: 89, XXL: 60 }, ZQ: { M: 40, L: 60, XL: 59, XXL: 40 } }, defects: 2, terms: '',
      nodes: [
        { name: '到料', ps: '2026-09-16', pe: '2026-09-22', as: '2026-09-16', ae: '2026-09-21', qty: 500 },
        { name: '裁剪', ps: '2026-09-23', pe: '2026-09-25', as: '2026-09-23', ae: '2026-09-25', qty: 500 },
        { name: '车缝', ps: '2026-09-26', pe: '2026-10-10', as: '2026-09-26', ae: '2026-10-09', qty: 500 },
        { name: '后整', ps: '2026-10-10', pe: '2026-10-13', as: '2026-10-10', ae: '2026-10-13', qty: 500 },
        { name: '包装', ps: '2026-10-13', pe: '2026-10-15', as: '2026-10-14', ae: '2026-10-15', qty: 498 },
        { name: '入仓', ps: '2026-10-16', pe: '2026-10-18', as: '2026-10-16', ae: '2026-10-17', qty: 498 },
      ],
      issues: [],
      receipts: [{ no: 'HH202610170001', date: '2026-10-17', qty: 498, defects: 2, wh: 'WH09', logistics: '德邦 DPK2026101788', note: '2 件跳线，退厂返修后补寄' }],
      reports: [],
    },
  ],

  purchases: [
    {
      no: 'CG202610160001', supplier: 'S001', type: '订单采购', orders: ['DD202610150001'], date: '2026-10-16', due: '2026-10-23', buyer: '吴佳', wh: 'WH01', status: '已到齐', tax: 13,
      lines: [{ sku: 'ML0005-KQ', qty: 600, price: 21, due: '2026-10-23', inQty: 600 }, { sku: 'ML0005-ZQ', qty: 400, price: 21, due: '2026-10-23', inQty: 400 }],
    },
    {
      no: 'CG202610160002', supplier: 'S002', type: '订单采购', orders: ['DD202610150001'], date: '2026-10-16', due: '2026-10-23', buyer: '吴佳', wh: 'WH01', status: '部分到货', tax: 13,
      lines: [{ sku: 'ML0003-KQ', qty: 450, price: 6.8, due: '2026-10-23', inQty: 450 }, { sku: 'ML0003-ZQ', qty: 300, price: 6.8, due: '2026-10-23', inQty: 0 }],
    },
    {
      no: 'CG202610160003', supplier: 'S003', type: '订单采购', orders: ['DD202610150001'], date: '2026-10-16', due: '2026-10-20', buyer: '吴佳', wh: 'WH02', status: '已到齐', tax: 0,
      lines: [{ sku: 'FL0001-60', qty: 613, price: 1.8, due: '2026-10-20', inQty: 613 }, { sku: 'FL0003-BB', qty: 226, price: 3.2, due: '2026-10-20', inQty: 226 }],
    },
    {
      no: 'CG202610160004', supplier: 'S009', type: '订单采购', orders: ['DD202610150001'], date: '2026-10-16', due: '2026-10-23', buyer: '吴佳', wh: 'WH02', status: '已到齐', tax: 0,
      lines: [{ sku: 'BQ0004-BB', qty: 2000, price: 0.08, due: '2026-10-23', inQty: 2000 }, { sku: 'BQ0002-BB', qty: 2000, price: 0.35, due: '2026-10-23', inQty: 2000 }],
    },
    {
      no: 'CG202610290001', supplier: 'S001', type: '备货采购', orders: [], date: '2026-10-29', due: '2026-11-08', buyer: '吴佳', wh: 'WH01', status: '待审核', tax: 13,
      lines: [{ sku: 'ML0001-HE', qty: 200, price: 46.8, due: '2026-11-08', inQty: 0 }],
    },
  ],

  stockIns: [
    { no: 'RK202610190001', type: '采购入库', wh: 'WH02', supplier: 'S003', customer: '', src: 'CG202610160003', date: '2026-10-19', status: '已审核', by: '赵立', lines: [{ sku: 'FL0001-60', qty: 613, lot: '' }, { sku: 'FL0003-BB', qty: 226, lot: '' }] },
    { no: 'RK202610200001', type: '客供入库', wh: 'WH02', supplier: '', customer: 'C002', src: 'DD202610150001', date: '2026-10-20', status: '已审核', by: '赵立', lines: [{ sku: 'BQ0005-HE', qty: 650, lot: '' }] },
    { no: 'RK202610220001', type: '采购入库', wh: 'WH01', supplier: 'S001', customer: '', src: 'CG202610160001', date: '2026-10-22', status: '已审核', by: '赵立', lines: [{ sku: 'ML0005-KQ', qty: 600, lot: 'G2610-118 / G2610-121' }, { sku: 'ML0005-ZQ', qty: 400, lot: 'G2610-133' }] },
    { no: 'RK202610220002', type: '采购入库', wh: 'WH01', supplier: 'S002', customer: '', src: 'CG202610160002', date: '2026-10-22', status: '已审核', by: '赵立', lines: [{ sku: 'ML0003-KQ', qty: 450, lot: '' }] },
    { no: 'RK202610230001', type: '采购入库', wh: 'WH02', supplier: 'S009', customer: '', src: 'CG202610160004', date: '2026-10-23', status: '已审核', by: '赵立', lines: [{ sku: 'BQ0004-BB', qty: 2000, lot: '' }, { sku: 'BQ0002-BB', qty: 2000, lot: '' }] },
    { no: 'RK202611050001', type: '其他入库', wh: 'WH01', supplier: '', customer: '', src: '', date: '2026-11-05', status: '草稿', by: '赵立', lines: [{ sku: 'ML0001-MB', qty: 12, lot: '' }], note: '样布退回' },
  ],

  stockOuts: [
    { no: 'LL202610260001', type: '领料出库', from: 'WH01', to: 'WF004', src: 'JG202610170001', date: '2026-10-26', status: '已审核', by: '赵立', lines: [{ sku: 'ML0005-KQ', qty: 600, owner: '自有' }, { sku: 'ML0003-KQ', qty: 450, owner: '自有' }, { sku: 'FL0001-60', qty: 364, owner: '自有' }, { sku: 'FL0003-BB', qty: 133, owner: '自有' }, { sku: 'BQ0005-HE', qty: 364, owner: 'C002' }, { sku: 'BQ0004-BB', qty: 364, owner: '自有' }, { sku: 'BQ0002-BB', qty: 364, owner: '自有' }] },
    { no: 'LL202610260002', type: '领料出库', from: 'WH01', to: 'WF005', src: 'JG202610170002', date: '2026-10-26', status: '已审核', by: '赵立', lines: [{ sku: 'ML0005-ZQ', qty: 400, owner: '自有' }, { sku: 'FL0001-60', qty: 243, owner: '自有' }, { sku: 'FL0003-BB', qty: 89, owner: '自有' }, { sku: 'BQ0005-HE', qty: 243, owner: 'C002' }, { sku: 'BQ0004-BB', qty: 243, owner: '自有' }, { sku: 'BQ0002-BB', qty: 243, owner: '自有' }] },
    { no: 'DB202611010001', type: '调拨', from: 'WH02', to: 'WH01', src: '', date: '2026-11-01', status: '已审核', by: '赵立', lines: [{ sku: 'FL0003-BB', qty: 4, owner: '自有' }] },
    { no: 'CK202611030001', type: '采购退货', from: 'WH02', to: '', src: 'CG202610160003', date: '2026-11-03', status: '草稿', by: '赵立', lines: [{ sku: 'FL0001-60', qty: 6, owner: '自有' }], note: '拉头卡顿，退 6 条' },
    { no: 'TL202611040001', type: '退料入库', from: 'WF004', to: 'WH01', src: 'JG202610170001', date: '2026-11-04', status: '已审核', by: '赵立', lines: [{ sku: 'ML0005-KQ', qty: 21.6, owner: '自有' }] },
  ],

  // 库存：仓库 + 物料 SKU + 物权（自有 / 客户编号）
  stock: [
    { wh: 'WH01', sku: 'ML0001-HE', owner: '自有', qty: 312.5 },
    { wh: 'WH01', sku: 'ML0001-MB', owner: '自有', qty: 180 },
    { wh: 'WH01', sku: 'ML0001-WL', owner: '自有', qty: 36 },
    { wh: 'WH01', sku: 'ML0002-KQ', owner: '自有', qty: 120 },
    { wh: 'WH01', sku: 'ML0002-HE', owner: '自有', qty: 35.5 },
    { wh: 'WH01', sku: 'ML0004-HE', owner: '自有', qty: 30 },
    { wh: 'WH01', sku: 'ML0004-MB', owner: '自有', qty: 12 },
    { wh: 'WH01', sku: 'ML0005-KQ', owner: '自有', qty: 21.6 },
    { wh: 'WH01', sku: 'FL0003-BB', owner: '自有', qty: 8 },
    { wh: 'WH02', sku: 'FL0001-60', owner: '自有', qty: 6 },
    { wh: 'WH02', sku: 'FL0002-KQ', owner: '自有', qty: 300 },
    { wh: 'WH02', sku: 'FL0002-ZQ', owner: '自有', qty: 40 },
    { wh: 'WH02', sku: 'BQ0001-HE', owner: '自有', qty: 1500 },
    { wh: 'WH02', sku: 'BQ0002-BB', owner: '自有', qty: 1393 },
    { wh: 'WH02', sku: 'BQ0004-BB', owner: '自有', qty: 1393 },
    { wh: 'WH02', sku: 'BQ0005-HE', owner: 'C002', qty: 43 },
    { wh: 'WF004', sku: 'ML0005-KQ', owner: '自有', qty: 578.4 },
    { wh: 'WF004', sku: 'ML0003-KQ', owner: '自有', qty: 450 },
    { wh: 'WF004', sku: 'FL0001-60', owner: '自有', qty: 364 },
    { wh: 'WF004', sku: 'FL0003-BB', owner: '自有', qty: 133 },
    { wh: 'WF004', sku: 'BQ0005-HE', owner: 'C002', qty: 364 },
    { wh: 'WF004', sku: 'BQ0004-BB', owner: '自有', qty: 364 },
    { wh: 'WF004', sku: 'BQ0002-BB', owner: '自有', qty: 364 },
    { wh: 'WF005', sku: 'ML0005-ZQ', owner: '自有', qty: 400 },
    { wh: 'WF005', sku: 'FL0001-60', owner: '自有', qty: 243 },
    { wh: 'WF005', sku: 'FL0003-BB', owner: '自有', qty: 89 },
    { wh: 'WF005', sku: 'BQ0005-HE', owner: 'C002', qty: 243 },
    { wh: 'WF005', sku: 'BQ0004-BB', owner: '自有', qty: 243 },
    { wh: 'WF005', sku: 'BQ0002-BB', owner: '自有', qty: 243 },
  ],

  stockLog: [
    { at: '2026-11-04 16:02', docType: '退料入库', doc: 'TL202611040001', wh: 'WH01', sku: 'ML0005-KQ', owner: '自有', change: 21.6, after: 21.6, by: '赵立' },
    { at: '2026-11-04 16:02', docType: '退料出库', doc: 'TL202611040001', wh: 'WF004', sku: 'ML0005-KQ', owner: '自有', change: -21.6, after: 578.4, by: '赵立' },
    { at: '2026-11-01 10:20', docType: '调拨入库', doc: 'DB202611010001', wh: 'WH01', sku: 'FL0003-BB', owner: '自有', change: 4, after: 8, by: '赵立' },
    { at: '2026-11-01 10:20', docType: '调拨出库', doc: 'DB202611010001', wh: 'WH02', sku: 'FL0003-BB', owner: '自有', change: -4, after: 0, by: '赵立' },
    { at: '2026-10-26 14:31', docType: '领料入库', doc: 'LL202610260002', wh: 'WF005', sku: 'ML0005-ZQ', owner: '自有', change: 400, after: 400, by: '赵立' },
    { at: '2026-10-26 14:31', docType: '领料出库', doc: 'LL202610260002', wh: 'WH01', sku: 'ML0005-ZQ', owner: '自有', change: -400, after: 0, by: '赵立' },
    { at: '2026-10-26 14:31', docType: '领料出库', doc: 'LL202610260002', wh: 'WH02', sku: 'BQ0005-HE', owner: 'C002', change: -243, after: 43, by: '赵立' },
    { at: '2026-10-26 11:05', docType: '领料入库', doc: 'LL202610260001', wh: 'WF004', sku: 'ML0005-KQ', owner: '自有', change: 600, after: 600, by: '赵立' },
    { at: '2026-10-26 11:05', docType: '领料出库', doc: 'LL202610260001', wh: 'WH01', sku: 'ML0005-KQ', owner: '自有', change: -600, after: 0, by: '赵立' },
    { at: '2026-10-26 11:05', docType: '领料出库', doc: 'LL202610260001', wh: 'WH02', sku: 'BQ0005-HE', owner: 'C002', change: -364, after: 286, by: '赵立' },
    { at: '2026-10-23 09:40', docType: '采购入库', doc: 'RK202610230001', wh: 'WH02', sku: 'BQ0002-BB', owner: '自有', change: 2000, after: 2000, by: '赵立' },
    { at: '2026-10-22 15:30', docType: '采购入库', doc: 'RK202610220002', wh: 'WH01', sku: 'ML0003-KQ', owner: '自有', change: 450, after: 450, by: '赵立' },
    { at: '2026-10-22 15:12', docType: '采购入库', doc: 'RK202610220001', wh: 'WH01', sku: 'ML0005-KQ', owner: '自有', change: 600, after: 600, by: '赵立' },
    { at: '2026-10-22 15:12', docType: '采购入库', doc: 'RK202610220001', wh: 'WH01', sku: 'ML0005-ZQ', owner: '自有', change: 400, after: 400, by: '赵立' },
    { at: '2026-10-20 13:50', docType: '客供入库', doc: 'RK202610200001', wh: 'WH02', sku: 'BQ0005-HE', owner: 'C002', change: 650, after: 650, by: '赵立' },
    { at: '2026-10-19 10:08', docType: '采购入库', doc: 'RK202610190001', wh: 'WH02', sku: 'FL0001-60', owner: '自有', change: 613, after: 613, by: '赵立' },
  ],

  samples: [
    {
      no: 'YY202609050001', name: '女式风衣', cat: '外套 / 风衣', designer: '林夏', pattern: '王敏', way: '内部版房', factory: '', fabric: '云织面料 YZ-8803 斜纹棉样布', season: '2027 春', sizeGroup: 'W5', colors: ['KQ', 'ZQ'], status: '已转大货', style: '27CF001', shape: 'coat', tagPrice: 699, catCode: 'F',
      rounds: [
        { no: 'DY202609050001', round: 1, kind: '初样', status: '已评审', due: '2026-09-12', nodes: [{ name: '接单', at: '09-05' }, { name: '纸样', at: '09-07' }, { name: '裁剪', at: '09-08' }, { name: '车版', at: '09-11' }, { name: '完成', at: '09-12' }], review: { at: '2026-09-13', by: '林夏、何蔓', result: '改版', opinion: '腰带位置下移 2cm；领子偏硬' } },
        { no: 'DY202609140001', round: 2, kind: '改版样', status: '已评审', due: '2026-09-20', nodes: [{ name: '接单', at: '09-14' }, { name: '纸样', at: '09-15' }, { name: '裁剪', at: '09-16' }, { name: '车版', at: '09-19' }, { name: '完成', at: '09-20' }], review: { at: '2026-09-22', by: '林夏、何蔓、客户刘倩', result: '通过', opinion: '客户确认，转大货' } },
      ],
    },
    {
      no: 'YY202610120001', name: '女式短款西装', cat: '外套 / 西装', designer: '林夏', pattern: '王敏', way: '内部版房', factory: '', fabric: '澜庭纺织 LT-3301 精纺样布', season: '2027 春', sizeGroup: 'W4', colors: ['HE', 'YM'], status: '开发中', style: '', shape: 'coat', tagPrice: 599, catCode: 'J',
      rounds: [
        { no: 'DY202610120001', round: 1, kind: '初样', status: '已评审', due: '2026-10-19', nodes: [{ name: '接单', at: '10-12' }, { name: '纸样', at: '10-14' }, { name: '裁剪', at: '10-15' }, { name: '车版', at: '10-18' }, { name: '完成', at: '10-19' }], review: { at: '2026-10-20', by: '林夏、何蔓', result: '改版', opinion: '肩宽偏大 1cm；驳头太宽；袖长加 1cm' } },
        { no: 'DY202610220001', round: 2, kind: '改版样', status: '打样中', due: '2026-10-31', nodes: [{ name: '接单', at: '10-22' }, { name: '纸样', at: '10-24' }, { name: '裁剪', at: '10-27' }, { name: '车版', at: '' }, { name: '完成', at: '' }], review: null },
      ],
    },
    {
      no: 'YY202610250001', name: '女式落肩卫衣', cat: '上衣 / 卫衣', designer: '苏禾', pattern: '外部', way: '外部工厂', factory: 'S004', fabric: '澜庭纺织 LT-2207 汁布', season: '2027 春', sizeGroup: 'W4', colors: ['YM', 'WL'], status: '待评审', style: '', shape: 'hoodie', tagPrice: 229, catCode: 'S',
      rounds: [
        { no: 'DY202610250001', round: 1, kind: '初样', status: '待评审', due: '2026-11-03', nodes: [{ name: '接单', at: '10-25' }, { name: '纸样', at: '10-27' }, { name: '裁剪', at: '10-28' }, { name: '车版', at: '11-01' }, { name: '完成', at: '11-03' }], review: null },
      ],
    },
    {
      no: 'YY202608150001', name: '男式工装裤', cat: '下装 / 长裤', designer: '苏禾', pattern: '王敏', way: '内部版房', factory: '', fabric: '云织面料 YZ-6610 斜纹', season: '2027 春', sizeGroup: 'M4', colors: ['KQ'], status: '已淘汰', style: '', shape: 'pants', tagPrice: 259, catCode: 'K',
      rounds: [{ no: 'DY202608150001', round: 1, kind: '初样', status: '已评审', due: '2026-08-22', nodes: [{ name: '接单', at: '08-15' }, { name: '纸样', at: '08-17' }, { name: '裁剪', at: '08-18' }, { name: '车版', at: '08-21' }, { name: '完成', at: '08-22' }], review: { at: '2026-08-25', by: '林夏、何蔓', result: '淘汰', opinion: '和 26 秋款版型重复' } }],
    },
  ],

  logs: {
    '27CF001': [
      { at: '2026-10-14 16:20', by: '林夏', text: '资料包 V2 生效，V1 作废（大身损耗 2% → 3%）' },
      { at: '2026-10-02 10:05', by: '林夏', text: '新增颜色 黑色（HE），生成 SKU 5 个' },
      { at: '2026-09-28 14:30', by: '林夏', text: '由样衣 YY202609050001 转大货，生成款号 27CF001 和资料包 V1' },
    ],
  },

  seq: {},
})

// 新单号：前缀 + yyyyMMdd + 4 位流水（原型从 0005 起，避开假数据里已有的号）
function nextNo(prefix) {
  const key = prefix + TODAY.replaceAll('-', '')
  db.seq[key] = (db.seq[key] || 4) + 1
  return key + String(db.seq[key]).padStart(4, '0')
}
