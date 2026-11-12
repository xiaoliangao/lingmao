// 原型检查：构建 → 无头 Chrome 打开 → 跑自检（PRD §4.3 的例子）→ 逐个打开侧边栏页面，看有没有报错。
// 用法：node check.mjs [--shots 截图目录] [--only 路径,路径]；OUT=文件 时构建到那里（几个人同时检查时各用各的）
// 要 Node 22（自带 WebSocket）和 Chrome；Chrome 不在默认位置时设 CHROME=路径
import { execFileSync, spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, mkdirSync, rmSync, readdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const opt = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : null }
const shots = opt('--shots'), only = opt('--only')?.split(',')
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const out = process.env.OUT || join(here, 'index.html')
console.log(execFileSync('bash', [join(here, 'build.sh')], { env: { ...process.env, OUT: out } }).toString().trim())
// 各文件共用一个全局作用域：顶层 function 重名不报错，后写的悄悄盖掉先写的（const 重名浏览器会报错，不用查）
const fnSeen = new Map(), fnDup = []
for (const f of readdirSync(join(here, 'src')).filter((x) => /^\d.*\.js$/.test(x)).sort()) {
  for (const [, n] of readFileSync(join(here, 'src', f), 'utf8').matchAll(/^(?:async\s+)?function\s+([\w$]+)/gm)) { if (fnSeen.has(n)) fnDup.push(`${n}（${fnSeen.get(n)}、${f}）`); else fnSeen.set(n, f) }
}
if (fnDup.length) { console.log('✗ 顶层函数重名，后面的会盖掉前面的：' + fnDup.join('；')); process.exitCode = 1 }
const profile = mkdtempSync(join(tmpdir(), 'scm-proto-'))
const port = 9300 + Math.floor(Math.random() * 500)
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--window-size=1440,900', '--no-first-run', 'about:blank'], { stdio: 'ignore' })
let ws, id = 0
const pending = new Map(), errors = []
const send = (method, params = {}) => new Promise((resolve, reject) => { const n = ++id; pending.set(n, { resolve, reject }); ws.send(JSON.stringify({ id: n, method, params })) })
const evaluate = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value }

try {
  let target
  for (let i = 0; i < 50 && !target; i++) { try { target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page') } catch { await sleep(100) } }
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r) => ws.addEventListener('open', r))
  ws.addEventListener('message', (m) => {
    const d = JSON.parse(m.data)
    if (d.id && pending.has(d.id)) { const p = pending.get(d.id); pending.delete(d.id); d.error ? p.reject(new Error(d.error.message)) : p.resolve(d.result) }
    if (d.method === 'Runtime.exceptionThrown') errors.push(d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text)
    if (d.method === 'Runtime.consoleAPICalled' && d.params.type === 'error') errors.push(d.params.args.map((a) => a.value ?? a.description).join(' '))
  })
  await send('Runtime.enable'); await send('Page.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  const url = 'file://' + out

  await send('Page.navigate', { url: url + '?selftest' }); await sleep(1200)
  const st = await evaluate('window.__selftest || null')
  let bad = 0
  if (!st) { console.log('✗ 自检没跑起来'); bad++ } else st.forEach((x) => { if (!x.ok) bad++; console.log((x.ok ? '✓ ' : '✗ ') + x.name + (x.ok ? '' : '  → ' + JSON.stringify(x.got))) })

  await send('Page.navigate', { url }); await sleep(800)
  const paths = only || (await evaluate(`[...NAV.flatMap(g => g.items.map(i => i.to)), '/feedback', ...SEARCH.flatMap(f => f()).map(x => x.to).filter((t, i, a) => a.findIndex(y => y.split('/')[1] === t.split('/')[1]) === i)]`))
  if (shots) { rmSync(shots, { recursive: true, force: true }); mkdirSync(shots, { recursive: true }) }
  for (const p of [...new Set(paths)]) {
    const before = errors.length
    await evaluate(`location.hash = '#${p}'`); await sleep(350)
    const info = await evaluate(`({ found: !!document.querySelector('.main > div') && !document.querySelector('.main .empty a[href="#/"]'), text: document.querySelector('.main')?.innerText.length || 0 })`)
    const errs = errors.slice(before)
    const ok = info.found && info.text > 20 && !errs.length
    if (!ok) bad++
    console.log(`${ok ? '✓' : '✗'} #${p}${info.found ? '' : '  页面没注册'}${errs.length ? '  ' + errs.join(' | ') : ''}`)
    if (shots) { const s = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(join(shots, (p.replace(/\W+/g, '_').replace(/^_|_$/g, '') || 'home') + '.png'), Buffer.from(s.data, 'base64')) }
  }
  if (fnDup.length) bad++
  console.log(bad ? `\n${bad} 项没过` : '\n全部通过')
  process.exitCode = bad ? 1 : 0
} finally {
  ws?.close(); chrome.kill(); await sleep(300); rmSync(profile, { recursive: true, force: true })
}
