/* ============================================================
 * 三期确认清单（11-21 起替换原来的批注）：三期（2027 年起）开工前要甲方定的事。
 * 每条问题挂在相关的页面上：在那一页按 C 或点左下角「这一页要定的」，右边列出这一页的问题，对着页面当场答；
 * 侧边栏「三期确认清单」（/confirm）列全部，导出 Markdown 看、导出 JSON 合并几个人的答复。
 * 题号沿用 PRD §12（Q01…），以后每一轮导入都按题号对；题目改了把 CONFIRM_VER 加一，新题先在 PRD §12 加一行。
 * 答复存在浏览器 localStorage（只在这台电脑这个浏览器里），按"题号 + 答的人"存：导入只补别人的、同一个人新的盖旧的。
 * 每页的"其他意见"沿用原来批注的数据（scm-proto-feedback-v1），以前写的批注、导出的 JSON 都还在、还能导。
 * 导出的文件会转来转去，仓库也是公开的：Key、密码不让填，价格和真实的供应商不写进题目。
 * ============================================================ */
const CONFIRM_VER = 1
const CF_KEY = 'scm-proto-confirm-v1'
const FB_KEY = 'scm-proto-feedback-v1'
const CF_STATES = ['已准备', '没有']
const NO_SECRET = 'Key、密码不要写在这里：开通后直接交给部署方放进服务器配置，不经过我们'
const WHEN = ['这次交付就要', '12 月（二期）', '明年（三期）', '先不要']
const RANK = ['先做', '以后再说', '不要']

/* 一条题：
 *   id 题号（PRD §12）；t 问什么；who 谁答；due 最晚
 *   now 现在系统是什么样；at 在系统哪里 [{ t, to }]，没有页面的写 { t, pal: true }（点了打开 ⌘K）
 *   ask 要定的 [{ k, t, type: one 单选 | many 多选 | grid 每行一个单选 | text | num, opts, rows, unit, ph }]
 *   mats 要准备的材料 [{ k, s 简称, t }]，每样标"已准备 / 没有"；文件发给对接人，这里只记准备没有
 *   dflt 不答就按这个做；diff 答得不一样会怎样；then 答了以后我们做什么；warn 卡片上的提醒 */
const CONFIRM = [
  { g: 'AI 和智能助手', qs: [
    { id: 'Q45', t: '要不要做智能助手？先做哪几样？', who: '管理层、甲方 IT', due: '排三期之前',
      now: '系统里还没有 AI 功能。管理后台有一个「AI 功能」总开关，只有超级管理员能开关，装好时是关的；哪一样做好了才接上它，打开马上能用，关掉马上停。',
      at: [
        { t: '⌘K 搜索框：页面、单号都搜不到时，最后一项是"问助手"，回答是一个筛好的列表', pal: true },
        { t: '首页：每天早上一张"今天要盯的"卡片，按待办、到料、交期风险排好', to: '/' },
        { t: '物料入库单：拍送货单、码单，识别成入库单草稿，按这家没入齐的采购单对上', to: '/stock-in' },
        { t: '物料 › 价格记录：发报价截图，换算成采购单位，算出和现价差多少', to: '/materials/ML0012?tab=prices' },
        { t: '手机报工：工厂发的微信消息（裁床数、进度），整理成报工草稿', to: '/mobile' },
        { t: '采购单等单据：问"为什么不能反审核""为什么被拦""在等谁审"', to: '/purchases' },
      ],
      ask: [
        { k: 'want', t: '要哪几样（可以多选）', type: 'many', opts: ['问数、打开筛好的列表', '问"为什么"', '拍照识别出草稿', '首页"今天要盯的"', '都先不要'] },
        { k: 'first', t: '最先要哪一样', type: 'one', opts: ['问数、打开筛好的列表', '问"为什么"', '拍照识别出草稿', '首页"今天要盯的"'] },
        { k: 'data', t: '价格、供应商这些数据，能不能发给外部的大模型', type: 'one', opts: ['能，用外部模型（按用量付费）', '不能，自己部署（要一台带显卡的服务器）', '还要商量'] },
        { k: 'model', t: '用哪家的模型', type: 'text', ph: '已经在用的、或者指定要用的；不指定由我们推荐' },
        { k: 'budget', t: '用外部模型的话：每月最多花多少、哪个部门出', type: 'text', ph: '到了上限当月就停用' },
        { k: 'gpu', t: '自己部署的话：带显卡的服务器谁准备、放在哪', type: 'text', ph: '比如：IT 准备，放公司机房' },
        { k: 'limit', t: '每人每天最多问几次', type: 'num', unit: '次', ph: '不填我们先定一个，试用时再调' },
      ],
      mats: [
        { k: 'account', s: '模型账号', t: '用外部模型：模型服务的账号，你们开通、付费（Key 交给部署方，见上面的提醒）' },
        { k: 'server', s: '显卡服务器', t: '自己部署：一台带显卡的服务器。你们选了模型，我们给配置单' },
        { k: 'questions', s: '常问的问题', t: '平时最常问的 5–10 个问题（比如"这周哪些单要逾期"），先把这些的算法定死' },
        { k: 'photos', s: '照片样例', t: '选了拍照识别：送货单、码单、报价截图各 3–5 张（价格可以涂掉）' },
      ],
      dflt: '不做，整个项目做完以后再说。做的话只查数和出草稿，不提交、不审核；每个人从助手看到的，和他自己在系统里能看到的一样。',
      diff: '数据能出公司，用外部模型，按用量付费；不能出，要自己部署模型、加一台带显卡的服务器。',
      then: '按你们选的先后做；每一样先装在试用环境给你们试，你们的超级管理员打开总开关才生效。',
      warn: NO_SECRET },
    { id: 'Q22', t: '合同、供应商风险排查（企查查）什么时候要？', who: '管理层', due: '上线前',
      now: '这两块是 11-11 新加的，原型里有样子，系统里还没做。供应商的信用代码、准入审批已经做好了，以后接上不用返工。',
      at: [
        { t: '供应商 › 风险：每周复查，新增的风险进首页待办', to: '/suppliers/GYS0005?tab=risk' },
        { t: '合同：按模板出合同、签回', to: '/contracts' },
      ],
      ask: [
        { k: 'contract', t: '合同什么时候要', type: 'one', opts: WHEN },
        { k: 'risk', t: '风险排查什么时候要', type: 'one', opts: WHEN },
      ],
      mats: [{ k: 'tpl', s: '合同模板', t: '现在用的采购合同、加工合同模板（Excel），排上以后给' }],
      dflt: '不放进这次交付，最早二期（12 月）。',
      diff: '要放进这次交付，就得拿别的模块换时间。',
      then: '按你们给的时间排；风险排查还要先定下面 Q23 的账号和费用。' },
    { id: 'Q23', t: '企查查的账号和费用谁出？多久查一次？', who: '财务、甲方 IT', due: '排风险排查之前（看 Q22）',
      now: '领猫导进来的供应商没有信用代码，刚上线时大多数查不了。所以第一步先按全称查工商信息：同名的几家都列出来，采购点"用这家"才补进档案。有了信用代码，系统定期把合作中的供应商查一遍，新增的风险进首页待办。',
      at: [
        { t: '供应商列表：缺信用代码的标出来', to: '/suppliers' },
        { t: '供应商 › 风险：每次查的结果、新增了哪几条', to: '/suppliers/GYS0005?tab=risk' },
      ],
      ask: [
        { k: 'payer', t: '企查查账号谁开通、谁付费', type: 'one', opts: ['甲方开通、付费', '还没定'] },
        { k: 'count', t: '合作中的供应商大概多少家', type: 'num', unit: '家' },
        { k: 'often', t: '多久查一次', type: 'one', opts: ['每周', '每月', '只在准入时查'] },
      ],
      mats: [{ k: 'plan', s: '套餐和单价', t: '企查查开通的套餐和每次查询的价格（我们按家数估每月要花多少）' }],
      dflt: '你们开通企查查并付费，Key 交给部署方放进服务器配置，不经过我们；每周把合作中的供应商查一遍，准入时和手动点的另算。',
      diff: '供应商多、费用高的话，改成每月查一次，或者只在准入时查。',
      then: '排上风险排查时先做"按全称补信用代码"，再做定期复查。',
      warn: NO_SECRET },
    { id: 'Q27', t: '工厂的对账单是什么样？识别走哪条路？', who: '财务、甲方 IT', due: '排对账结算之前',
      now: '对账结算还是待定模块：原型里有样子，系统里没做。以后想把工厂、面辅料商发来的对账单识别出来，和系统里的入库、回货逐行对，对不上的列出来。',
      at: [{ t: '对账：按月和供应商对账、确认', to: '/finance' }],
      ask: [
        { k: 'form', t: '对账单一般是什么样（可以多选）', type: 'many', opts: ['Excel', 'PDF', '拍照', '手写'] },
        { k: 'no', t: '上面有没有我们的单号（采购单号、外发加工单号）', type: 'one', opts: ['有', '有的有', '没有'] },
        { k: 'out', t: '对账单能不能传给外面的识别服务', type: 'one', opts: ['能，用第三方服务（按页付费）', '不能，自己部署（要一台带显卡的服务器）', '还要商量'] },
        { k: 'n', t: '每月大概多少张', type: 'num', unit: '张' },
      ],
      mats: [{ k: 'samples', s: '对账单样例', t: '3–5 份工厂、面辅料商的对账单，各种样子各一份（金额可以涂掉）' }],
      dflt: '跟着对账结算排期再定。倾向先用第三方服务试（不用加服务器），前提是你们同意把对账单传给服务商；本来就是 Excel 的直接导入，不用识别。',
      diff: '对账单不能传到外面，就要自己部署，加一台带显卡的服务器（可以和智能助手用同一台）。',
      then: '排对账结算时，按你们的对账单样子设计导入和比对。' },
  ] },
  { g: '和别的系统对接', qs: [
    { id: 'Q01', t: '要不要和聚水潭对接？接哪几样？', who: '生产、财务、仓管', due: '排三期之前',
      now: '你们 11-21 定了：成品库存以聚水潭为准，物料仓在本系统。现在两边不对接：外发加工单回货、成品采购单到货在本系统登记收了多少，再手工在聚水潭建入库。',
      at: [
        { t: '外发加工单：回货登记', to: '/production' },
        { t: '成品采购单：到货登记', to: '/fp-purchases' },
      ],
      ask: [
        { k: 'want', t: '要不要对接', type: 'one', opts: ['要', '先不要，手工建入库够用'] },
        { k: 'what', t: '要对接的话，接哪几样（可以多选）', type: 'many', opts: ['回货后自动在聚水潭建入库', '聚水潭的成品库存、销量传回来，给下单参考', '别的（写在补充里）'] },
        { k: 'owner', t: '聚水潭那边谁管？能不能开通开放接口？', type: 'text', ph: '比如：电商部管，能开' },
        { k: 'other', t: '还有别的系统要和本系统对接吗', type: 'text', ph: '比如：钉钉、企业微信、电商平台' },
      ],
      mats: [
        { k: 'api', s: '接口权限', t: '聚水潭开放平台的接口权限，由你们的聚水潭管理员在聚水潭后台申请（密钥交给部署方，见上面的提醒）' },
        { k: 'test', s: '测试店铺', t: '一个测试用的店铺或测试账号，对接时联调用' },
      ],
      dflt: '三期也不对接，照现在回货后手工在聚水潭建入库。',
      diff: '要对接，排在三期，先看聚水潭能给哪些接口，再定做哪几样。',
      then: '拿到接口权限后先在测试店铺上联调，你们核对没问题再切到正式店铺。',
      warn: NO_SECRET },
    { id: 'Q04', t: '要不要对接金蝶、用友等财务软件？', who: '财务', due: '排对账结算之前',
      now: '一期、二期都不对接。财务结算是待定模块，系统里已经记着每张单的数量、单价、金额，以后对账、传财务软件都用得上。',
      at: [{ t: '对账', to: '/finance' }],
      ask: [
        { k: 'soft', t: '现在用哪家财务软件', type: 'one', opts: ['金蝶', '用友', '别的', '没用'] },
        { k: 'ver', t: '哪个版本？云上的，还是装在自己服务器上的？', type: 'text' },
        { k: 'what', t: '要从本系统传过去什么（可以多选）', type: 'many', opts: ['应付（对完账的金额）', '凭证', '发票信息', '不用传'] },
      ],
      mats: [{ k: 'doc', s: '接口说明', t: '财务软件的接口说明，或者财务软件服务商的联系人（排的时候再要）' }],
      dflt: '排对账结算时再定。',
      diff: '要对接，告诉我们用哪家、哪个版本，排对账结算时一起做。',
      then: '排对账结算时一起做。' },
  ] },
  { g: '工厂、供应商上系统', qs: [
    { id: 'Q02', t: '工厂、面辅料商要不要账号上系统？', who: '生产、采购', due: '排三期之前',
      now: '现在没有外部账号：跟单员替工厂在手机上报进度、拍照；工艺单、纸样下载后发微信给工厂；供应商的资料、资质由采购录。',
      at: [
        { t: '手机报工：现在是跟单员替工厂报', to: '/mobile' },
        { t: '外发加工单：工艺单下载后发微信', to: '/production' },
        { t: '供应商：资料、资质由采购录', to: '/suppliers/GYS0005' },
      ],
      ask: [
        { k: 'factory', t: '工厂要不要账号', type: 'one', opts: ['要', '不要'] },
        { k: 'fwant', t: '工厂最想在系统里做哪几样（可以多选）', type: 'many', opts: ['看单、接单', '自己报进度、传照片', '下载工艺单、纸样', '对账', '预约送货'] },
        { k: 'supplier', t: '面辅料商要不要账号（自己填资料、实名认证、看采购单）', type: 'one', opts: ['要', '不要'] },
        { k: 'n', t: '合作的工厂大概多少家', type: 'num', unit: '家' },
        { k: 'device', t: '工厂那边用什么上', type: 'one', opts: ['手机', '电脑', '都有'] },
      ],
      mats: [{ k: 'pilot', s: '试点工厂', t: '先试的 1–2 家工厂（愿意配合的），和工厂那边的联系人' }],
      dflt: '三期也不做外部账号，照现在跟单员代报。',
      diff: '要的话排在三期，先做你们最想要的那一样；工厂账号只看得到发给这家工厂的单。',
      then: '先拿 1–2 家工厂试，跑顺了再给别的工厂开。' },
  ] },
  { g: '三期其余几样、待定模块', qs: [
    { id: 'Q67', t: '需求表里排在三期的几样，要不要、先做哪个？', who: '管理层、仓管、生产', due: '排三期之前',
      now: '这几样现在是这样：款式能传多张图；出入库在电脑上选物料、填数量；库存低于最小库存会进首页待办，能一键生成备货采购单；需求订单手工录，或者从 Excel 导入。',
      at: [
        { t: '款式：多张图', to: '/styles' },
        { t: '物料入库单：在电脑上填', to: '/stock-in' },
        { t: '补货提醒', to: '/reorder' },
        { t: '需求订单：手工录、从 Excel 导入', to: '/orders' },
      ],
      ask: [
        { k: 'rank', t: '每一样要不要', type: 'grid', rows: ['样品图库、素材库', '扫码出入库（扫码枪或手机）', '呆滞料分析', '订单从别的系统自动进来', '工厂产线'], opts: RANK },
        { k: 'scan', t: '扫码的话：用什么扫？物料上现在有没有条码？', type: 'text', ph: '比如：想用手机扫，物料上没有条码' },
        { k: 'idle', t: '呆滞料的话：多久没动算呆滞', type: 'num', unit: '天' },
        { k: 'src', t: '订单自动进来的话：从哪个系统来', type: 'text' },
      ],
      mats: [{ k: 'label', s: '标签照片', t: '要扫码的话：现在物料上贴的标签、条码拍几张' }],
      dflt: '三期开工前再问一次；没排的先不做。',
      diff: '哪样先做，告诉我们大概什么时候要、谁来提需求，我们出方案再排。',
      then: '按你们排的先后出方案。' },
    { id: 'Q53', t: '需求表里没标黄的模块，要不要做？先做哪个？', who: '管理层', due: '不急',
      now: '这几块是"待定、以后再做"，不是不做：原型里有样子（侧边栏标着"待定"），系统里没做；一期已经替它们把要用的数记下来了，比如价格、金额、实际日期、次品数、责任方。成品发货不在里面：你们 11-21 定了成品以聚水潭为准。',
      at: [
        { t: '企划', to: '/plans' },
        { t: '核价', to: '/costing' },
        { t: '质检', to: '/qc' },
        { t: '对账', to: '/finance' },
        { t: '供应商 › 绩效', to: '/suppliers/GYS0005?tab=perf' },
      ],
      ask: [
        { k: 'rank', t: '每一块要不要', type: 'grid', rows: ['商品企划', '核价与成本（询价比价、订单毛利）', '质量管理', '财务结算（对账、应付、发票）', '供应商绩效'], opts: RANK },
        { k: 'owner', t: '先做的那块，谁来提需求', type: 'text' },
      ],
      mats: [{ k: 'forms', s: '现在的表格', t: '先做的那块：你们现在用的表格或单据（Excel、照片都行）' }],
      dflt: '这次交付和二期都不排，等主线用顺了再说。',
      diff: '哪块要先做，告诉我们大概什么时候要、谁来提需求，我们出方案再排。',
      then: '按你们排的先后出方案。' },
  ] },
]
CONFIRM.forEach((g) => g.qs.forEach((q) => { q.g = g.g }))
const CONFIRM_QS = CONFIRM.flatMap((g) => g.qs)
// 二期确认问题（docs/二期确认问题.md）里在问、三期也要用的：这里只说一句为什么，不重复问
const CONFIRM_ELSEWHERE = [
  { id: 'Q55', t: '工厂超耗怎么算、要不要扣加工费', why: '三期想按原因把超耗归类，标出老超耗的工厂、老出缸差的面料商' },
  { id: 'Q60', t: '工厂产能怎么算', why: '需求订单审核时，算出交期前这家工厂还能做多少件' },
  { id: 'Q66', t: '每周、每月要看哪些报表', why: '三期的"一句话问数"只按定死的算法回答，你们常看的报表就是要先定死的那些数' },
]
// 和三期有关、已经定了的：折起来放在清单最后，不用再答
const CONFIRM_DONE = [
  { id: 'Q44', t: '成品库存以哪边为准', at: '11-21', a: '以聚水潭为准。本系统的成品模块收回不用，回货只登记收了多少。' },
  { id: 'Q47', t: '工艺单要不要统一模板', at: '11-18', a: '统一成一种模板（版师定），上传后系统按模板识别尺寸表和工艺。模板定了，识别工艺单不用 AI。哪一份是统一模板，资料包那边在问。' },
  { id: 'Q46', t: '要不要识别营业执照', at: '11-17', a: '不做。维护供应商时手填全称、信用代码、地址；以后接企查查，查到的工商信息人确认后补进档案。' },
  { id: 'Q29', t: '领猫里的供应商有没有信用代码', at: '11-17', a: '没有。导入时选填，缺的在列表上标出来，补齐前不能查风险、不能出合同。' },
]

/* ---------- 存储 ---------- */
function cfLoad(key, empty) {
  try { return { ...empty, ...JSON.parse(localStorage.getItem(key) || '{}') } } catch { return empty }
}
function cfStore(key, v) {
  try { localStorage.setItem(key, JSON.stringify(v)) } catch { /* 隐私模式存不了，只在本次打开有效 */ }
}
const fb = reactive(cfLoad(FB_KEY, { name: '', items: [], verdicts: {} })) // 其他意见（原来的批注）和答的人的名字
const cf = reactive(cfLoad(CF_KEY, { answers: {} })) // answers[题号][答的人] = { v: { 要定的 k: 值 }, mats: { k: 已准备 | 没有 }, note, at }
watch(fb, () => cfStore(FB_KEY, fb), { deep: true })
watch(cf, () => cfStore(CF_KEY, cf), { deep: true })
const fbUi = reactive({ open: false, focus: null })

const navItem = (k) => NAV.flatMap((g) => g.items).find((it) => it.k === k)
const curMod = () => nav.r?.mod || 'home'
const nowStamp = () => new Date().toISOString()
const fmtStamp = (s) => { if (!s) return ''; const d = new Date(s); return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}` }
const cfRoute = (to) => ROUTES.find((r) => r.re.test(to.split('?')[0]))
const cfLinks = () => [...new Set(CONFIRM_QS.flatMap((q) => q.at.filter((a) => a.to).map((a) => a.to)))]
// 这一页（同一个模块的列表、详情都算）相关的题
const cfHere = () => CONFIRM_QS.filter((q) => q.at.some((a) => a.to && cfRoute(a.to)?.mod === curMod()))

/* ---------- 答复 ---------- */
const cfFilled = (v) => v != null && (Array.isArray(v) ? v.length > 0 : typeof v === 'object' ? Object.values(v).some(cfFilled) : String(v).trim() !== '')
const cfHas = (a) => !!a && (Object.values(a.v || {}).some(cfFilled) || Object.values(a.mats || {}).some(cfFilled) || cfFilled(a.note))
const cfAuthors = (q) => Object.entries(cf.answers[q.id] || {}).filter(([, a]) => cfHas(a)).sort((x, y) => (x[1].at || '').localeCompare(y[1].at || ''))
const cfAnswered = (q) => cfAuthors(q).length > 0
const cfOpenQs = () => CONFIRM_QS.filter((q) => !cfAnswered(q))
const cfMineOf = (q) => cf.answers[q.id]?.[fb.name]
// 改自己的答复：没写名字先拦下（答复按名字存）
function cfMine(q) {
  if (!fb.name) { toast('先在上面写上你的名字', null, true); return null }
  const m = (cf.answers[q.id] ||= {})
  return (m[fb.name] ||= { v: {}, mats: {}, note: '', at: '' })
}
function cfSet(q, k, val) {
  const a = cfMine(q)
  if (!a) return
  if (k === 'note') a.note = val
  else a.v[k] = val
  a.at = nowStamp()
}
// 点选项：单选再点一下取消；多选来回切
function cfPick(q, s, opt) {
  const a = cfMine(q)
  if (!a) return
  const cur = a.v[s.k]
  if (s.type === 'one') a.v[s.k] = cur === opt ? '' : opt
  else { const arr = Array.isArray(cur) ? cur.slice() : []; const i = arr.indexOf(opt); if (i >= 0) arr.splice(i, 1); else arr.push(opt); a.v[s.k] = arr }
  a.at = nowStamp()
}
function cfPickRow(q, s, row, opt) {
  const a = cfMine(q)
  if (!a) return
  const g = { ...(a.v[s.k] || {}) }
  g[row] = g[row] === opt ? '' : opt
  a.v[s.k] = g
  a.at = nowStamp()
}
function cfMat(q, m, st) {
  const a = cfMine(q)
  if (!a) return
  a.mats[m.k] = a.mats[m.k] === st ? '' : st
  a.at = nowStamp()
}
const cfIs = (q, s, opt, row) => { const v = cfMineOf(q)?.v[s.k]; return row != null ? v?.[row] === opt : Array.isArray(v) ? v.includes(opt) : v === opt }
// 一个人的答复写成几行字：页面上看别人的答复、导出 Markdown 都用它
function cfLines(q, a) {
  const out = []
  q.ask.forEach((s) => {
    const v = a.v?.[s.k]
    if (!cfFilled(v)) return
    const txt = s.type === 'many' ? v.join('、') : s.type === 'grid' ? Object.entries(v).filter(([, x]) => x).map(([r, x]) => `${r} ${x}`).join('；') : String(v).trim().replace(/\n/g, ' ') + (s.unit ? ' ' + s.unit : '')
    out.push(`${s.t}：${txt}`)
  })
  const ms = (q.mats || []).filter((m) => a.mats?.[m.k]).map((m) => `${m.s} ${a.mats[m.k]}`)
  if (ms.length) out.push('材料：' + ms.join('；'))
  if (cfFilled(a.note)) out.push('补充：' + a.note.trim().replace(/\n/g, ' '))
  return out
}

/* ---------- 其他意见（原来的批注） ---------- */
function fbAdd(text) {
  const t = text.trim()
  if (!t) return false
  if (!fb.name) { toast('先在上面写上你的名字', null, true); return false }
  fb.items.push({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), mod: curMod(), pat: nav.r?.pat || nav.path, path: nav.path, title: routeTitle(), anchor: '', rx: 0, ry: 0, text: t, author: fb.name, at: nowStamp() })
  return true
}
// 解决 / 重新打开：记下谁、什么时候解决的
function fbResolve(x, on = !x.done) {
  if (on) x.done = { by: fb.name, at: nowStamp() }
  else delete x.done
}

/* ---------- 导出 / 导入 ---------- */
function cfDownload(name, text, type) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([text], { type }))
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
function cfMarkdown() {
  const L = ['# 三期确认清单 · 答复', '', `导出：${fmtStamp(nowStamp())}${fb.name ? ' · ' + fb.name : ''} · 题目第 ${CONFIRM_VER} 版 · 已答 ${CONFIRM_QS.length - cfOpenQs().length} / ${CONFIRM_QS.length} 条`, '']
  CONFIRM.forEach((g) => {
    L.push(`## ${g.g}`, '')
    g.qs.forEach((q) => {
      L.push(`### ${q.id}　${q.t}`, '', `谁答：${q.who} · 最晚：${q.due}`, '')
      const as = cfAuthors(q)
      if (!as.length) L.push(`还没答。不答就按这个做：${q.dflt}`, '')
      as.forEach(([who, a]) => { L.push(`- ${who || '未署名'}（${fmtStamp(a.at)}）`); cfLines(q, a).forEach((x) => L.push(`  - ${x}`)) })
      if (as.length) L.push('')
    })
  })
  const known = new Set(CONFIRM_QS.map((q) => q.id))
  const stray = Object.entries(cf.answers).flatMap(([id, m]) => (known.has(id) ? [] : Object.entries(m).filter(([, a]) => cfHas(a)).map(([who, a]) => `- ${id} · ${who || '未署名'}：${JSON.stringify(a.v)}${a.note ? ' ' + a.note : ''}`)))
  if (stray.length) L.push('## 对不上题号的答复（题目改过）', '', ...stray, '')
  if (fb.items.length) {
    L.push('## 其他意见', '')
    fb.items.forEach((x) => L.push(`- ${x.done ? '~~' : ''}【${x.title}${x.anchor ? ' · ' + x.anchor : ''}】${x.text.replace(/\n/g, ' ')} —— ${x.author || '未署名'} ${fmtStamp(x.at)}（#${x.path}）${x.done ? '~~ 已解决' : ''}`))
    L.push('')
  }
  const vs = Object.entries(fb.verdicts).filter(([, m]) => Object.keys(m).length)
  if (vs.length) {
    L.push('## 以前对各模块的表态', '')
    vs.forEach(([k, m]) => L.push(`- ${navItem(k)?.t || k}：${Object.entries(m).map(([a, v]) => `${a || '未署名'} ${v}`).join('，')}`))
    L.push('')
  }
  return L.join('\n')
}
const cfJson = () => JSON.stringify({ kind: 'scm-proto-confirm', version: CONFIRM_VER, exportedAt: nowStamp(), by: fb.name, answers: cf.answers, items: fb.items, verdicts: fb.verdicts }, null, 2)
// 导入别人导出的 JSON：答复按"题号 + 答的人"补，同一个人新的盖旧的，不动别人的；意见按 id 补，别人那边解决了这边也标上。
// 以前的意见文件（只有 items）也收。dst = { answers, items, verdicts }
function cfMerge(dst, d) {
  if (!d || typeof d !== 'object' || (!d.answers && !Array.isArray(d.items))) throw new Error('不是导出的确认清单或意见文件')
  let answers = 0
  Object.entries(d.answers || {}).forEach(([id, m]) => Object.entries(m || {}).forEach(([who, a]) => {
    if (!a || typeof a !== 'object') return
    const byQ = (dst.answers[id] ||= {}), have = byQ[who]
    if (have && (a.at || '') <= (have.at || '')) return
    byQ[who] = { v: { ...(a.v || {}) }, mats: { ...(a.mats || {}) }, note: a.note || '', at: a.at || '' }
    answers++
  }))
  const have = new Map(dst.items.map((x) => [x.id, x]))
  const items = (d.items || []).filter((x) => x && x.id && !have.has(x.id))
  ;(d.items || []).forEach((x) => { const y = x && have.get(x.id); if (y && x.done && !y.done) y.done = x.done })
  dst.items.push(...items)
  Object.entries(d.verdicts || {}).forEach(([k, m]) => Object.assign((dst.verdicts[k] ||= {}), m))
  return { answers, items: items.length }
}
const cfImport = (text) => cfMerge({ answers: cf.answers, items: fb.items, verdicts: fb.verdicts }, JSON.parse(text))

/* ---------- 名字 ---------- */
comp('CfName', {
  setup() {
    const draft = ref('')
    const save = () => { if (draft.value.trim()) fb.name = draft.value.trim(); else toast('先写上你的名字', null, true) }
    return { fb, draft, save }
  },
  template: `<div class="cf-name" data-c="答的人">
    <template v-if="fb.name">答的人：<b>{{ fb.name }}</b><button type="button" class="btn ghost sm" @click="fb.name = ''">换人</button></template>
    <template v-else><input class="inp" v-model="draft" placeholder="你的名字（答复按名字记）" @keydown.enter="save"><button type="button" class="btn pri sm" @click="save">好</button></template>
  </div>`,
})

/* ---------- 一条题 ---------- */
comp('CfCard', {
  props: { q: Object, compact: Boolean },
  setup(p) {
    const mine = computed(() => cfMineOf(p.q))
    const others = computed(() => cfAuthors(p.q).filter(([who]) => who !== fb.name))
    const answered = computed(() => cfAnswered(p.q))
    const pal = () => { shellUi.pal = true }
    return { fb, mine, others, answered, pal, cfPick, cfPickRow, cfIs, cfSet, cfMat, cfLines, cfRoute, CF_STATES, fmtStamp }
  },
  template: `<article class="cf-card" :id="'cf-' + q.id" :class="{ hl: nav.query.q === q.id && !compact, compact }" :data-c="q.id">
    <header><b class="code">{{ q.id }}</b><h4>{{ q.t }}</h4><span :class="['pill', answered ? 'green' : 'amber']">{{ answered ? '已答' : '没答' }}</span></header>
    <div class="cf-meta">谁答：{{ q.who }} · 最晚：{{ q.due }}</div>
    <div v-if="q.warn" class="banner red cf-warn"><Ico n="alert" :s="15"/><span>{{ q.warn }}</span></div>
    <dl class="cf-dl">
      <dt>现在是这样</dt><dd>{{ q.now }}</dd>
      <template v-if="!compact"><dt>在系统里看</dt><dd><ul class="cf-at">
        <li v-for="a in q.at" :key="a.t"><a v-if="a.to" :href="'#' + a.to"><Ico n="right" :s="13"/>{{ a.t }}</a><a v-else role="button" tabindex="0" @click="pal" @keydown.enter="pal"><Ico n="search" :s="13"/>{{ a.t }}</a></li>
      </ul></dd></template>
    </dl>
    <div class="cf-ask">
      <div v-for="s in q.ask" :key="s.k" class="cf-s">
        <label>{{ s.t }}</label>
        <div v-if="s.type === 'one' || s.type === 'many'" class="chips"><button v-for="o in s.opts" :key="o" type="button" class="chip" :class="{ on: cfIs(q, s, o) }" @click="cfPick(q, s, o)">{{ o }}</button></div>
        <table v-else-if="s.type === 'grid'" class="cf-grid"><tr v-for="r in s.rows" :key="r"><th>{{ r }}</th><td><div class="chips"><button v-for="o in s.opts" :key="o" type="button" class="chip" :class="{ on: cfIs(q, s, o, r) }" @click="cfPickRow(q, s, r, o)">{{ o }}</button></div></td></tr></table>
        <div v-else-if="s.type === 'num'" class="row g4"><input class="inp cf-num" type="number" min="0" :disabled="!fb.name" :value="mine?.v[s.k] ?? ''" :placeholder="s.ph || ''" @change="cfSet(q, s.k, $event.target.value)"><span v-if="s.unit" class="dim">{{ s.unit }}</span></div>
        <textarea v-else class="inp" rows="2" :disabled="!fb.name" :value="mine?.v[s.k] ?? ''" :placeholder="s.ph || ''" @change="cfSet(q, s.k, $event.target.value)"></textarea>
      </div>
      <div v-if="q.mats.length" class="cf-s"><label>要准备的材料<span class="dim">（文件发给对接人，不要贴在这里）</span></label>
        <div v-for="m in q.mats" :key="m.k" class="cf-mat"><span>{{ m.t }}</span>
          <span class="chips"><button v-for="st in CF_STATES" :key="st" type="button" class="chip" :class="{ on: mine?.mats[m.k] === st }" @click="cfMat(q, m, st)">{{ st }}</button></span></div>
      </div>
      <div class="cf-s"><label>补充</label><textarea class="inp" rows="2" :disabled="!fb.name" :value="mine?.note ?? ''" placeholder="上面没问到的、想说明的" @change="cfSet(q, 'note', $event.target.value)"></textarea></div>
    </div>
    <dl class="cf-dl cf-if">
      <dt>不答就按这个做</dt><dd>{{ q.dflt }}</dd>
      <dt>答得不一样</dt><dd>{{ q.diff }}</dd>
      <dt>答了以后</dt><dd>{{ q.then }}</dd>
    </dl>
    <div v-if="mine?.at" class="cf-by dim">{{ fb.name }} {{ fmtStamp(mine.at) }} 改过</div>
    <div v-for="[who, a] in others" :key="who" class="cf-other"><b>{{ who || '未署名' }}</b><span class="dim"> {{ fmtStamp(a.at) }}</span>
      <div v-for="x in cfLines(q, a)" :key="x">{{ x }}</div></div>
  </article>`,
})

/* ---------- 右侧面板：这一页要定的 + 这一页的其他意见 ---------- */
comp('ConfirmPanel', {
  setup() {
    useEsc(() => { fbUi.open = false })
    const text = ref('')
    const qs = computed(() => cfHere())
    const open = ref(null)
    watch(() => nav.seq, () => { open.value = qs.value.find((q) => !cfAnswered(q))?.id || null }, { immediate: true })
    const items = computed(() => fb.items.filter((x) => x.mod === curMod()).slice().reverse().sort((a, b) => !!a.done - !!b.done))
    function send() { if (fbAdd(text.value)) { text.value = ''; toast('意见已记下') } }
    const remove = (x) => fb.items.splice(fb.items.indexOf(x), 1)
    return { fb, fbUi, text, qs, open, items, send, remove, cfAnswered, fbResolve, fmtStamp, routeTitle }
  },
  template: `<aside class="fb" aria-label="这一页要定的">
    <header><span>这一页要定的 · {{ routeTitle() }}</span>
      <button class="btn ghost icon" title="关掉 (C)" @click="fbUi.open = false"><Ico n="x"/></button></header>
    <div class="items">
      <CfName style="margin:12px 0 4px"/>
      <div class="h2" style="margin-top:12px">三期要你们定的<span class="dim" style="font-weight:400">{{ qs.length ? qs.length + ' 条' : '' }}</span><a href="#/confirm" style="margin-left:auto;font-weight:400;font-size:12px">全部清单</a></div>
      <div v-for="q in qs" :key="q.id" class="cf-fold">
        <button type="button" class="cf-fold-h" :aria-expanded="open === q.id" @click="open = open === q.id ? null : q.id">
          <b class="code">{{ q.id }}</b><span>{{ q.t }}</span><span :class="['pill', cfAnswered(q) ? 'green' : 'amber']">{{ cfAnswered(q) ? '已答' : '没答' }}</span></button>
        <CfCard v-if="open === q.id" :q="q" compact/>
      </div>
      <p v-if="!qs.length" class="dim" style="font-size:13px">这一页没有要定的三期问题。<a href="#/confirm">看全部清单</a></p>
      <div class="h2">这一页的其他意见</div>
      <textarea class="inp" rows="3" style="width:100%" v-model="text" placeholder="这一页哪里要改、哪里看不懂" @keydown.meta.enter="send" @keydown.ctrl.enter="send"></textarea>
      <div class="row" style="justify-content:flex-end;margin-top:6px"><button class="btn pri sm" @click="send">发送</button></div>
      <div v-for="x in items" :key="x.id" class="item" :class="{ done: x.done }">
        <div class="who"><b>{{ x.author || '未署名' }}</b><span>{{ fmtStamp(x.at) }}</span><span class="sp"></span>
          <button class="fb-act" @click="fbResolve(x)">{{ x.done ? '重新打开' : '解决' }}</button>
          <button v-if="x.author === fb.name" title="删除" @click="remove(x)"><Ico n="trash" :s="13"/></button></div>
        <p>{{ x.text }}</p>
        <div v-if="x.anchor" class="dim" style="font-size:12px">{{ x.anchor }}</div>
      </div>
    </div>
  </aside>`,
})

/* ---------- 三期确认清单页 ---------- */
route('/confirm', {
  setup() {
    const file = ref(null)
    const seg = ref('全部')
    const openN = computed(() => cfOpenQs().length)
    const shown = (q) => seg.value === '全部' || (seg.value === '没答' ? !cfAnswered(q) : cfAnswered(q))
    const groups = computed(() => CONFIRM.map((g) => ({ g: g.g, qs: g.qs.filter(shown) })).filter((g) => g.qs.length))
    // 其他意见按页面分组，没解决的页排前面
    const notes = computed(() => {
      const m = new Map()
      fb.items.forEach((x) => { if (!m.has(x.path)) m.set(x.path, { path: x.path, title: x.title, items: [] }); m.get(x.path).items.push(x) })
      return [...m.values()].sort((a, b) => !a.items.some((x) => !x.done) - !b.items.some((x) => !x.done))
    })
    onMounted(() => { const q = nav.query.q; if (q) nextTick(() => document.getElementById('cf-' + q)?.scrollIntoView({ block: 'start' })) })
    function imp(e) {
      const f = e.target.files[0]
      if (!f) return
      f.text().then((t) => { try { const r = cfImport(t); toast(`导入 ${r.answers} 份答复、${r.items} 条意见`) } catch (err) { toast(err instanceof SyntaxError ? '文件打不开：不是导出的 JSON' : err.message, null, true) } })
      e.target.value = ''
    }
    async function clear() {
      if (!(await ask('清空答复和意见', '这台电脑上记的答复和其他意见会全部删掉，删了找不回来。先导出备份。', '清空', true))) return
      Object.keys(cf.answers).forEach((k) => delete cf.answers[k])
      fb.items.splice(0)
      Object.keys(fb.verdicts).forEach((k) => delete fb.verdicts[k])
    }
    const exportMd = () => cfDownload('三期确认清单-答复.md', cfMarkdown(), 'text/markdown')
    const exportJson = () => cfDownload('三期确认清单-答复.json', cfJson(), 'application/json')
    return { CONFIRM_QS, CONFIRM_DONE, CONFIRM_ELSEWHERE, seg, openN, groups, notes, file, imp, clear, exportMd, exportJson, fbResolve, fmtStamp }
  },
  template: `<div>
    <Head t="三期确认清单">
      <input ref="file" type="file" accept=".json" hidden @change="imp">
      <button class="btn ghost" title="合并别人导出的 JSON" @click="file.click()"><Ico n="upload" :s="14"/>导入</button>
      <button class="btn" @click="exportJson">导出 JSON</button>
      <button class="btn pri" @click="exportMd"><Ico n="download" :s="14"/>导出 Markdown</button>
      <More :items="[{ t: '清空答复和意见', danger: true, fn: clear }]"/>
    </Head>
    <div class="page narrow" data-c="三期确认清单">
      <div class="cf-top">
        <CfName/>
        <span class="dim">已答 {{ CONFIRM_QS.length - openN }} / {{ CONFIRM_QS.length }} 条</span>
        <Seg v-model="seg" :items="[['全部', '全部', CONFIRM_QS.length], ['没答', '没答', openN], ['已答', '已答', CONFIRM_QS.length - openN]]" style="margin-left:auto"/>
      </div>
      <p class="mut cf-lead">三期（2027 年起）开工前要你们定的事。每条写了现在系统是什么样、不答我们就怎么做；点「在系统里看」去那一页对着看，在那一页按 C 也能答。答完点「导出 Markdown」发给对接人。</p>
      <template v-for="g in groups" :key="g.g">
        <div class="h2">{{ g.g }}</div>
        <CfCard v-for="q in g.qs" :key="q.id" :q="q"/>
      </template>
      <p v-if="!groups.length" class="empty">{{ seg === '没答' ? '都答了：点右上角「导出 Markdown」发给对接人' : '还没有答的：从「没答」里挑一条开始' }}</p>
      <details class="cf-done" data-c="二期在问">
        <summary>二期在问、三期也要用（{{ CONFIRM_ELSEWHERE.length }} 条，在《二期要确认的问题》里答）</summary>
        <div v-for="x in CONFIRM_ELSEWHERE" :key="x.id" class="cf-done-i"><b class="code">{{ x.id }}</b> {{ x.t }}<div class="mut">三期要用：{{ x.why }}</div></div>
      </details>
      <details class="cf-done" data-c="已经定了的">
        <summary>已经定了的，不用再答（{{ CONFIRM_DONE.length }} 条）</summary>
        <div v-for="x in CONFIRM_DONE" :key="x.id" class="cf-done-i"><b class="code">{{ x.id }}</b> {{ x.t }}<span class="dim"> · {{ x.at }} 定的</span><div class="mut">{{ x.a }}</div></div>
      </details>
      <div class="h2">其他意见<span class="dim" style="font-weight:400">每一页按 C 写；以前的批注也在这里</span></div>
      <div v-for="g in notes" :key="g.path" class="cf-notes">
        <a :href="'#' + g.path" class="cf-notes-h">{{ g.title }}</a>
        <div v-for="x in g.items" :key="x.id" class="row" style="align-items:flex-start;padding:6px 0" :class="{ 'fb-done': x.done }">
          <div style="flex:1;min-width:0"><div class="fb-text">{{ x.text }}</div>
            <div class="dim" style="font-size:12px">{{ x.author || '未署名' }} · {{ fmtStamp(x.at) }}<template v-if="x.anchor"> · {{ x.anchor }}</template><template v-if="x.done"> · {{ x.done.by || '未署名' }} {{ fmtStamp(x.done.at) }} 解决</template></div></div>
          <button class="btn sm" :class="{ ghost: x.done }" @click="fbResolve(x)">{{ x.done ? '重新打开' : '解决' }}</button>
        </div>
      </div>
      <p v-if="!notes.length" class="dim" style="font-size:13px">还没有其他意见。</p>
    </div>
  </div>`,
}, { mod: 'confirm', t: '三期确认清单' })
redirect('/feedback', () => '/confirm') // 原来的"意见汇总"

/* ---------- 自检（9-app.js 在 ?selftest 时和 selftest() 一起跑） ---------- */
function confirmSelftest() {
  const out = [], ok = (name, cond, got) => out.push({ name: '确认清单：' + name, ok: !!cond, got })
  const ids = CONFIRM_QS.map((q) => q.id)
  ok('题号不重复，都是 PRD §12 的 Q 加两位数', new Set(ids).size === ids.length && ids.every((x) => /^Q\d{2}$/.test(x)), ids)
  const side = [...CONFIRM_DONE, ...CONFIRM_ELSEWHERE].map((x) => x.id)
  ok('已经定了的、二期在问的不和要答的重号', !side.some((x) => ids.includes(x)) && new Set(side).size === side.length, side)
  const bad = CONFIRM_QS.flatMap((q) => q.at.filter((a) => a.to && !cfRoute(a.to)).map((a) => q.id + ' ' + a.to))
  ok('每条"在系统里看"都对得上页面', !bad.length, bad)
  const thin = CONFIRM_QS.filter((q) => !(q.now && q.dflt && q.diff && q.then && q.who && q.due && q.ask.length && q.at.length)).map((q) => q.id)
  ok('每条都写了现在的样子、不答怎么做、答了以后、谁答、最晚', !thin.length, thin)
  const secret = CONFIRM_QS.flatMap((q) => [...q.ask.map((s) => s.t + (s.ph || '')), ...q.mats.map((m) => m.t)].filter((t) => /key|密码|密钥/i.test(t) && !/交给部署方/.test(t)).map((t) => q.id + ' ' + t))
  ok('没有一处让填 Key、密码', !secret.length, secret)
  const q = CONFIRM_QS[0]
  const src = { kind: 'scm-proto-confirm', version: CONFIRM_VER, answers: { [q.id]: { 张三: { v: { want: [q.ask[0].opts[0]] }, mats: { [q.mats[0].k]: '已准备' }, note: '先试试', at: '2026-11-22T02:00:00.000Z' } } }, items: [{ id: 'n1', text: '这页字小', path: '/', title: '首页', at: '2026-11-22T02:00:00.000Z' }], verdicts: {} }
  const dst = { answers: { [q.id]: { 李四: { v: {}, mats: {}, note: '我的', at: '2026-11-23T00:00:00.000Z' }, 张三: { v: {}, mats: {}, note: '旧的', at: '2026-11-21T00:00:00.000Z' } } }, items: [], verdicts: {} }
  const r1 = cfMerge(dst, JSON.parse(JSON.stringify(src)))
  ok('导入：同一个人新的盖旧的，别人的不动', dst.answers[q.id].张三.note === '先试试' && dst.answers[q.id].李四.note === '我的' && r1.answers === 1 && r1.items === 1, [r1, dst.answers[q.id]])
  const r2 = cfMerge(dst, JSON.parse(JSON.stringify(src)))
  ok('导入：同一份再导一次不重复', r2.answers === 0 && r2.items === 0 && dst.items.length === 1, r2)
  cfMerge(dst, { answers: { [q.id]: { 张三: { v: {}, note: '更旧的', at: '2026-11-20T00:00:00.000Z' } } } })
  ok('导入：旧的答复不盖新的', dst.answers[q.id].张三.note === '先试试', dst.answers[q.id].张三.note)
  ok('导入：以前的意见文件（只有 items）也能导', cfMerge(dst, { version: 1, items: [{ id: 'old1', text: '旧批注' }] }).items === 1 && dst.items.length === 2, dst.items.length)
  let e = ''
  try { cfMerge(dst, { foo: 1 }) } catch (x) { e = x.message }
  ok('导入：不是导出的文件，报错说清楚', e.includes('不是导出的'), e)
  const lines = cfLines(q, dst.answers[q.id].张三)
  ok('答复写成文字：选项、材料、补充都在', lines.some((x) => x.includes(q.ask[0].opts[0])) && lines.some((x) => x.includes('已准备')) && lines.some((x) => x.includes('先试试')), lines)
  return out
}
