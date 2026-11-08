/* ============================================================
 * 演示用的假图：款式画成平面款式图（按品类出形状、按颜色上色），物料画成布样 / 辅料小图。
 * 都是 SVG 拼出来的，不联网。用法：<Thumb :code="款号 | 样衣号 | 物料编码 | SKU" :s="尺寸" :color="颜色代码"/>
 * 渐变、纹理在 PIC_DEFS 里，由外框挂一次，所有图共用。
 * ============================================================ */

// 平面款式图：100 × 100。body = 衣身外轮廓；over = 叠在上面的块（领、腰头、腰带），dark 为 true 时压暗；
// line = 缝线、口袋、门襟；dots = 纽扣 [x, y, r]
const GARMENTS = {
  tee: {
    body: 'M36 14C40 19 60 19 64 14L82 21L93 39L81 45L76 38V92H24V38L19 45L7 39L18 21Z',
    line: 'M36 14C40 21 60 21 64 14M38.5 15.3C42 19.8 58 19.8 61.5 15.3M24 87H76M80 42.5L91.5 37M20 42.5L8.5 37M29 16.7C27.5 25 25.5 31 24 38M71 16.7C72.5 25 74.5 31 76 38',
  },
  knit: {
    body: 'M37 13C41 19 59 19 63 13L79 18C86 30 90 56 92 82L82 84C80 66 77 52 75 40L76 92H24L25 40C23 52 20 66 18 84L8 82C10 56 14 30 21 18Z',
    line: 'M37 13C41 19 59 19 63 13M39.5 14.3C43 21.5 57 21.5 60.5 14.3M24.2 85H75.8M8.6 77.5L18.6 79.5M91.4 77.5L81.4 79.5M29 15.5C27.5 24 26 32 25 40M71 15.5C72.5 24 74 32 75 40',
    rib: [24.5, 75.5, 85, 92],
  },
  hoodie: {
    body: 'M37 13C41 19 59 19 63 13L79 18C86 30 90 56 92 82L82 84C80 66 77 52 75 40L76 92H24L25 40C23 52 20 66 18 84L8 82C10 56 14 30 21 18Z',
    over: 'M35.5 16C32 6 40 1 50 1S68 6 64.5 16C60 23 40 23 35.5 16Z',
    line: 'M40.5 16C43 24 57 24 59.5 16M46 22L45 34M54 22L55 34M33 63H67L70.5 81H29.5ZM24.2 85H75.8M8.6 77.5L18.6 79.5M91.4 77.5L81.4 79.5M29 15.5C27.5 24 26 32 25 40M71 15.5C72.5 24 74 32 75 40',
    rib: [24.5, 75.5, 85, 92],
  },
  jacket: {
    body: 'M37 9H63L79 15C85 29 89 51 91 73L81 75C79 60 77 48 75 39V85H25V39C23 48 21 60 19 75L9 73C11 51 15 29 21 15Z',
    over: 'M37 9L44 22L50 15L56 22L63 9C57 12.5 43 12.5 37 9Z',
    line: 'M50 15V85M25 79H75M30 33H44V45H30ZM56 33H70V45H56ZM30 37.5H44M56 37.5H70M29 15.5C27.5 23 26 31 25 39M71 15.5C72.5 23 74 31 75 39M10 68.5L19.6 70.5M90 68.5L80.4 70.5M48.6 24H51.4V29H48.6Z',
  },
  trench: {
    body: 'M38 7H62L78 13C84 29 88 55 89 74L80 76C78 61 76 49 74 41L79 95H21L26 41C24 49 22 61 20 76L11 74C12 55 16 29 22 13Z',
    over: 'M38 7L45 31L50 24L55 31L62 7C57 10 43 10 38 7Z',
    band: 'M24.6 54H75.4L75.8 60.5H24.2Z',
    line: 'M50 60.5V95M28.5 14C27.5 23 26.5 32 26 41M71.5 14C72.5 23 73.5 32 74 41M31 66L35 79M69 66L65 79M11.6 69L20.3 71M88.4 69L79.7 71M47 53H53V61.5H47Z',
    dots: [[44, 36, 1.7], [56, 36, 1.7], [44, 46, 1.7], [56, 46, 1.7]],
  },
  pants: {
    body: 'M28 7H72L78 94H56.5L50 33L43.5 94H22Z',
    band: 'M28 7H72L72.5 13H27.5Z',
    line: 'M50 13V28C47 29 46 26 46 23V13M29.5 14L35 27M70.5 14L65 27M38 16L33.5 94M62 16L66.5 94M33 7V13M44 7V13M56 7V13M67 7V13M23 89H43.9M56.1 89H77',
  },
  dress: {
    body: 'M38 7C42 13 58 13 62 7L71 10L69 25L66 41L85 93H15L34 41L31 25L29 10Z',
    line: 'M38 7C42 17 58 17 62 7M34 41H66M43 47L37 92M50 45V93M57 47L63 92M29 10C32 14 32.5 20 31 25M71 10C68 14 67.5 20 69 25',
  },
  shirt: {
    body: 'M38 9H62L79 15C85 29 89 53 91 75L82 77C80 61 77 49 75 40V86C66 93 34 93 25 86V40C23 49 20 61 18 77L9 75C11 53 15 29 21 15Z',
    over: 'M38 9L44 20L50 12.5L56 20L62 9C57 11.5 43 11.5 38 9Z',
    line: 'M50 12.5V90M53 12.5V90M9.6 70.5L18.6 72.5M90.4 70.5L81.4 72.5M57 29H67V39H57ZM29 15.5C27.5 24 26 32 25 40M71 15.5C72.5 24 74 32 75 40',
    dots: [[51.5, 25, 1.1], [51.5, 36, 1.1], [51.5, 47, 1.1], [51.5, 58, 1.1], [51.5, 69, 1.1], [51.5, 80, 1.1]],
  },
  skirt: {
    body: 'M32 10H68L85 88C64 93 36 93 15 88Z',
    band: 'M32 10H68L68.9 16H31.1Z',
    line: 'M41 16L33 90.5M50 16V91.5M59 16L67 90.5',
  },
  puffer: {
    body: 'M37 9H63L80 15C87 29 91 51 92 73L81 75.5C79 60 77 48 75.5 39L76 87H24L24.5 39C23 48 21 60 19 75.5L8 73C9 51 13 29 20 15Z',
    band: 'M37 3.5H63V10.5H37Z',
    line: 'M50 3.5V87M24.4 29H75.6M24.3 43H75.7M24.2 57H75.8M24.1 71H75.9M12.5 36L23.5 34M10.5 50L22 49M9 64L20.5 63.5M76.5 34L87.5 36M78 49L89.5 50M79.5 63.5L91 64M9 68.5L20 70.5M91 68.5L80 70.5',
  },
}

const PIC_DEFS = `<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
  <radialGradient id="pg-bg" cx=".5" cy=".38" r=".8"><stop offset="0" stop-color="#fbfbf8"/><stop offset="1" stop-color="#e7e9e2"/></radialGradient>
  <linearGradient id="pg-shade" x1="0" x2="1"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset=".42" stop-color="#fff" stop-opacity="0"/><stop offset=".7" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".18"/></linearGradient>
  <linearGradient id="pg-v" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".10"/><stop offset="1" stop-color="#000" stop-opacity=".12"/></linearGradient>
  <linearGradient id="pg-sheen" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".45" stop-color="#fff" stop-opacity=".38"/><stop offset=".55" stop-color="#fff" stop-opacity=".05"/><stop offset="1" stop-color="#000" stop-opacity=".12"/></linearGradient>
  <linearGradient id="pg-metal" x1="0" x2="1"><stop offset="0" stop-color="#8d7a4c"/><stop offset=".5" stop-color="#e4d29b"/><stop offset="1" stop-color="#8d7a4c"/></linearGradient>
  <pattern id="pg-twill-d" width="3.2" height="3.2" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="3.2" stroke="#000" stroke-opacity=".13" stroke-width="1.1"/></pattern>
  <pattern id="pg-twill-l" width="3.2" height="3.2" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="3.2" stroke="#fff" stroke-opacity=".10" stroke-width="1.1"/></pattern>
  <pattern id="pg-knit-d" width="4" height="3.4" patternUnits="userSpaceOnUse"><path d="M0 0L2 3L4 0" fill="none" stroke="#000" stroke-opacity=".09" stroke-width=".7"/></pattern>
  <pattern id="pg-knit-l" width="4" height="3.4" patternUnits="userSpaceOnUse"><path d="M0 0L2 3L4 0" fill="none" stroke="#fff" stroke-opacity=".07" stroke-width=".7"/></pattern>
  <filter id="pg-soft" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="1.2" stdDeviation="1.4" flood-color="#1c2230" flood-opacity=".16"/></filter>
</defs></svg>`

// 颜色深浅：深色布上的线用浅色，浅色布上的线用深色
function hexLum(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex || '')
  if (!m) return 1
  const n = parseInt(m[1], 16)
  return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255
}
const inkOn = (hex) => (hexLum(hex) < 0.45 ? 'rgba(255,255,255,.34)' : 'rgba(28,34,48,.30)')

function garmentSvg(shape, hex) {
  const g = GARMENTS[shape] || GARMENTS.tee
  const ink = inkOn(hex), tex = hexLum(hex) < 0.45 ? 'l' : 'd'
  const knit = ['knit', 'hoodie'].includes(shape)
  const ribs = g.rib ? Array.from({ length: 18 }, (_, i) => { const x = g.rib[0] + ((g.rib[1] - g.rib[0]) * (i + 0.5)) / 18; return `M${x.toFixed(1)} ${g.rib[2]}V${g.rib[3]}` }).join('') : ''
  return `<g filter="url(#pg-soft)"><path d="${g.body}" fill="${hex}"/>${g.over ? `<path d="${g.over}" fill="${hex}"/>` : ''}</g>
    ${knit ? `<path d="${g.body}" fill="url(#pg-knit-${tex})"/>` : ''}
    <path d="${g.body}" fill="url(#pg-shade)"/><path d="${g.body}" fill="url(#pg-v)"/>
    ${g.band ? `<path d="${g.band}" fill="${hex}"/><path d="${g.band}" fill="#000" fill-opacity=".14"/>` : ''}
    ${g.over ? `<path d="${g.over}" fill="url(#pg-shade)"/><path d="${g.over}" fill="none" stroke="${ink}" stroke-width=".7"/>` : ''}
    <path d="${g.line}${ribs}" fill="none" stroke="${ink}" stroke-width=".7" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="${g.body}" fill="none" stroke="${ink}" stroke-width=".6" stroke-linejoin="round"/>
    ${(g.dots || []).map(([x, y, r]) => `<circle cx="${x}" cy="${y}" r="${r}" fill="${ink}"/>`).join('')}`
}

// 物料：梭织 / 针织 / 里布画成叠起来的布样，辅料、包材各画各的
function swatch(hex, fill) {
  const tex = hexLum(hex) < 0.45 ? 'l' : 'd'
  const front = 'M14 26H76L82 32V88H14Z', back = 'M24 12H86V74H82V32L76 26H24Z'
  return `<g filter="url(#pg-soft)"><path d="${back}" fill="${hex}"/><path d="${front}" fill="${hex}"/></g>
    <path d="${back}" fill="#000" fill-opacity=".14"/>
    <path d="${front}" fill="url(#pg-${fill === 'knit' ? 'knit' : 'twill'}-${tex})"/>
    ${fill === 'sheen' ? `<path d="${front}" fill="url(#pg-sheen)"/>` : `<path d="${front}" fill="url(#pg-shade)"/>`}
    <path d="M76 26V32H82Z" fill="#fff" fill-opacity=".28"/>
    <path d="${front}" fill="none" stroke="${inkOn(hex)}" stroke-width=".5"/>`
}
const MAT_DRAW = {
  woven: (hex) => swatch(hex, 'twill'),
  knit: (hex) => swatch(hex, 'knit'),
  lining: (hex) => swatch(hex, 'sheen'),
  zipper: (hex) => `<g filter="url(#pg-soft)"><rect x="37" y="6" width="26" height="88" rx="2" fill="${hex}"/></g><rect x="37" y="6" width="26" height="88" rx="2" fill="url(#pg-shade)"/>
    ${Array.from({ length: 21 }, (_, i) => `<rect x="${i % 2 ? 50 : 46.5}" y="${10 + i * 3.7}" width="3.5" height="2.4" rx=".6" fill="url(#pg-metal)"/>`).join('')}
    <path d="M44 24H56L55 40H45Z" fill="url(#pg-metal)" stroke="#6f6038" stroke-width=".5"/><rect x="47.5" y="40" width="5" height="12" rx="2.5" fill="url(#pg-metal)" stroke="#6f6038" stroke-width=".5"/>`,
  button: (hex) => `<g filter="url(#pg-soft)"><circle cx="50" cy="50" r="31" fill="${hex}"/></g><circle cx="50" cy="50" r="31" fill="url(#pg-sheen)"/>
    <circle cx="50" cy="50" r="23" fill="none" stroke="${inkOn(hex)}" stroke-width="1.6"/>
    ${[[43, 43], [57, 43], [43, 57], [57, 57]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="3.4" fill="#1c2230" fill-opacity=".55"/>`).join('')}`,
  thread: (hex) => `<g filter="url(#pg-soft)"><path d="M36 12H64L71 84H29Z" fill="${hex}"/></g><path d="M36 12H64L71 84H29Z" fill="url(#pg-shade)"/>
    ${Array.from({ length: 17 }, (_, i) => { const y = 16 + i * 4, d = ((y - 12) / 72) * 7; return `<path d="M${36 - d + 0.8} ${y}H${64 + d - 0.8}" stroke="${inkOn(hex)}" stroke-width=".5"/>` }).join('')}
    <path d="M26 84H74V90C74 92 72 93 70 93H30C28 93 26 92 26 90Z" fill="#b8a27a"/><ellipse cx="50" cy="12" rx="14" ry="2.6" fill="#b8a27a"/>`,
  label: () => `<g filter="url(#pg-soft)"><path d="M12 34H88V66H12Z" fill="#f7f4ec"/></g><path d="M12 34H20V66H12ZM80 34H88V66H80Z" fill="#000" fill-opacity=".06"/>
    <text x="50" y="49" text-anchor="middle" font-size="9" font-weight="700" fill="#2d4e8c" font-family="Georgia,serif" letter-spacing="1.5">ZHIJIAN</text>
    <path d="M32 56H68" stroke="#2d4e8c" stroke-opacity=".5" stroke-width="1.2"/><path d="M12 34H88V66H12Z" fill="none" stroke="#c9c3b3" stroke-width=".6"/>`,
  tag: () => `<path d="M50 20C40 6 58 2 60 -2" fill="none" stroke="#7b6a4a" stroke-width="1"/>
    <g filter="url(#pg-soft)"><path d="M36 12H64L72 22V92H28V22Z" fill="#d8c8aa"/></g><path d="M36 12H64L72 22V92H28V22Z" fill="url(#pg-shade)"/>
    <circle cx="50" cy="21" r="3" fill="#fbfbf8" stroke="#a8936a"/><text x="50" y="44" text-anchor="middle" font-size="8" font-weight="700" fill="#1c2230" font-family="Georgia,serif" letter-spacing="1">ZHIJIAN</text>
    <path d="M37 54H63M37 61H58M37 68H61" stroke="#1c2230" stroke-opacity=".35" stroke-width="1.4"/><path d="M37 78H63" stroke="#1c2230" stroke-opacity=".7" stroke-width="3" stroke-dasharray="1 1.2"/>`,
  bag: () => `<g filter="url(#pg-soft)"><path d="M18 12H82V92H18Z" fill="#dfe8f0" fill-opacity=".85"/></g><path d="M18 22H82" stroke="#9fb3c8" stroke-width="1.6"/>
    <path d="M28 30L44 84M40 30L50 62" stroke="#fff" stroke-opacity=".8" stroke-width="2.4" stroke-linecap="round"/><path d="M18 12H82V92H18Z" fill="none" stroke="#9fb3c8" stroke-width=".8"/>`,
}
const MAT_KIND = { MZ: 'knit', FL: 'zipper', FK: 'button', FX: 'thread', BX: 'label', BP: 'tag', BD: 'bag' }
const matKind = (m) => (m.type === '里料' ? 'lining' : MAT_KIND[m.cat] || 'woven')

// code 可以是款号、样衣号、物料编码、SKU；color 指定颜色时按它上色
function picOf(code, color) {
  const st = style(code) || (db.samples || []).find((x) => x.no === code)
  if (st) return garmentSvg(catOf(st.cat).shape, colorOf(color || st.colors?.[0]).hex)
  const m = mat(code) || matOfSku(code)
  if (m) {
    const c = color || skuOf(code)?.color || m.skus.find((k) => k.color)?.color
    return MAT_DRAW[matKind(m)](c ? colorOf(c).hex : '#efe8da')
  }
  return garmentSvg('tee', '#d9dcd3')
}
const PIC_CACHE = new Map()
const picSvg = (code, color) => { const k = code + '|' + (color || ''); if (!PIC_CACHE.has(k)) PIC_CACHE.set(k, picOf(code, color)); return PIC_CACHE.get(k) }

comp('Thumb', {
  props: { code: String, s: { type: Number, default: 32 }, color: String },
  setup: (p) => ({ svg: computed(() => picSvg(p.code, p.color)) }),
  template: `<span class="thumb" :style="{ width: s + 'px', height: s + 'px' }"><svg viewBox="0 0 100 100" width="100%" height="100%" v-html="svg"></svg></span>`,
})

/* 大图 + 各颜色的小图，点小图换颜色（款式、样衣、物料详情右侧） */
comp('Gallery', {
  props: { code: String, colors: Array, s: { type: Number, default: 258 } },
  setup: (p) => ({ c: ref(p.colors?.[0]) }),
  template: `<div class="gallery" data-c="图片"><Thumb :code="code" :color="c" :s="s"/>
    <div v-if="colors && colors.length > 1" class="row g4" style="margin-top:8px;flex-wrap:wrap"><button v-for="x in colors" :key="x" class="gthumb" :class="{ on: x === c }" :title="colorName(x)" @click="c = x"><Thumb :code="code" :color="x" :s="42"/></button></div></div>`,
})

/* ---------- 资料包的 BOM 图：款式图上按 BOM 行号标出每种料用在哪 ----------
 * BOM_SPOTS[形状][部位] = [x, y]（0–100，同款式图坐标）；没写坐标的部位（包装袋、缝纫线……）列在图下面 */
const BOM_SPOTS = {
  tee: { 大身: [50, 60], 领: [50, 17], 袖: [86, 32], 主唛: [50, 21], 洗唛: [28, 80], 吊牌: [70, 22] },
  knit: { 大身: [50, 58], 领: [50, 16], 袖: [86, 55], 主唛: [50, 20], 洗唛: [29, 80], 吊牌: [69, 22], 纽扣: [50, 40] },
  hoodie: { 大身: [50, 50], 领: [50, 19], 袖: [86, 55], 袋布: [50, 73], 拉链: [50, 40], 主唛: [42, 24], 洗唛: [29, 80], 吊牌: [69, 25] },
  jacket: { 大身: [38, 62], 领: [42, 13], 袖: [86, 45], 拉链: [50, 58], 纽扣: [37, 37.5], 袋布: [63, 40], 里布: [58, 25], 主唛: [50, 17], 洗唛: [28, 74], 吊牌: [68, 18] },
  trench: { 大身: [38, 78], 领: [44, 14], 袖: [84, 45], 纽扣: [44, 36], 织带: [62, 57], 袋布: [33, 72], 里布: [57, 21], 主唛: [50, 12], 洗唛: [27, 88], 吊牌: [68, 15] },
  pants: { 大身: [38, 60], 织带: [64, 10], 拉链: [48, 22], 纽扣: [50, 10], 袋布: [33, 20], 主唛: [36, 10], 洗唛: [26, 40], 吊牌: [72, 16] },
  dress: { 大身: [50, 66], 领: [50, 13], 拉链: [33, 32], 里布: [60, 78], 主唛: [50, 17], 洗唛: [33, 50], 吊牌: [64, 14] },
  shirt: { 大身: [38, 62], 领: [44, 13], 袖: [86, 48], 纽扣: [51.5, 36], 袋布: [62, 34], 主唛: [50, 16], 洗唛: [28, 78], 吊牌: [68, 18] },
  skirt: { 大身: [50, 60], 织带: [62, 13], 拉链: [36, 25], 纽扣: [50, 13], 里布: [64, 76], 主唛: [42, 13], 洗唛: [30, 60], 吊牌: [72, 18] },
  puffer: { 大身: [38, 50], 领: [50, 7], 袖: [86, 45], 拉链: [50, 40], 纽扣: [44, 64], 里布: [58, 22], 主唛: [50, 14], 洗唛: [27, 80], 吊牌: [68, 16] },
}
comp('BomFigure', {
  props: { code: String, color: String, rows: Array, hl: Number },
  emits: ['hover'],
  setup(p) {
    const marks = computed(() => {
      const spots = BOM_SPOTS[catOf(style(p.code)?.cat).shape] || BOM_SPOTS.tee, seen = {}
      return p.rows.map((r, i) => {
        const at = spots[r.part]
        if (!at) return { i, off: true }
        const n = (seen[r.part] = (seen[r.part] || 0) + 1) - 1 // 同一部位第二行往右挪一点
        return { i, x: at[0] + n * 6, y: at[1] }
      })
    })
    return { marks }
  },
  template: `<div class="fig"><Thumb :code="code" :color="color" :s="280"/>
    <span v-for="m in marks.filter(m => !m.off)" :key="m.i" class="mk" :class="{ on: hl === m.i }" :style="{ left: m.x + '%', top: m.y + '%' }" @mouseenter="$emit('hover', m.i)" @mouseleave="$emit('hover', null)">{{ m.i + 1 }}</span>
    <div v-if="marks.some(m => m.off)" class="off"><span v-for="m in marks.filter(m => m.off)" :key="m.i" class="mk-l" :class="{ on: hl === m.i }" @mouseenter="$emit('hover', m.i)" @mouseleave="$emit('hover', null)"><i>{{ m.i + 1 }}</i>{{ rows[m.i].part }}</span></div>
  </div>`,
})
