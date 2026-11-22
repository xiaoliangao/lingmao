# S3 本轮前端回归

在仓库根目录运行：

```sh
python3 tests/audit-fix/s3-browser/write-recovery.py
python3 tests/audit-fix/s3-browser/auth-cookies.py
```

需要已安装 `web` 的锁定依赖、Python Playwright 和 Chromium。可用 `SCM_UI_CHROMIUM` 指定本机 Chromium / headless-shell 路径。脚本在同一进程树中启动 Vite 和浏览器，无需数据库，不连接参考系统。

这里使用生产 `request.ts`、用户 Store、首页、订单评审及 `TechPackTab` 组件，HTTP 响应为明确的合成测试数据和故障注入。因此它证明前端传输、交互与渲染行为，不能替代真实后端落库、业务闭环或权限序列化验收。

22 项检查覆盖：等待旧收据后的同体并发合并；恢复等待、后台核对、迟到成功响应期间的会话切换；旧账号 `/auth/me` 的迟到 200、业务 401、HTTP 401 不得覆盖新账号资料或清除新 Token；首页字符串 BIGINT 筛选恢复；评审关闭/切单的实际请求取消和失败态；30 尺码 × 100 行的资料包尺寸编辑、上下移、删除、保存、滚动与固定列；按颜色 BOM 的滚动和悬浮料卡；资料包只读、加载失败态、三位序号不换行及定点成本预览；上游提醒的 50→2→50 游标翻页、筛选归首页、请求取消、单张/批量处理 100 条后保留剩余提醒与失败重试。

输出写入忽略目录 `test-artifacts/full-audit/s3/`：JSON、Vite 日志、关键页面截图。`write-recovery-before.json`、`late-response-before.json`、`auth-identity-before.json` 是本轮修复前执行时留存的证据，不由绿色回归重新生成；不提交日志、截图和临时运行目录。

`auth-cookies.py` 另外提供 8 项认证检查：它使用真实本地 HTTP 服务器和浏览器 HttpOnly Cookie 存储，延迟旧退出、刷新、改密响应并并发新登录，核对全局认证锁和旧排队退出的 epoch、刷新失败的真实 Request-Id，以及改密遇业务/HTTP 401 在 5 秒内安全结束而不递归申请刷新锁；没有 Web Locks 时核对 access-only 登录/退出、零 Set-Cookie、不刷新和迟到旧退出不清新访问令牌。HTTP 服务只实现明确的合成契约，真实后端对应响应仍由 S2 集成测试验证。报告不记录 Cookie / Token 值。

该脚本优先用普通测试 DNS 映射访问非安全 HTTP 源 `scm-http.test`。如果本地沙盒拒绝该入口，会明确记录 `insecure_http_origin.verified=false`，只以缺失 Web Locks 的能力注入继续验证分支，不把它当作真实 HTTP/IP UI 证据。在 `GITHUB_ACTIONS=true` 或 `SCM_UI_REQUIRE_INSECURE_ORIGIN=1` 下，此种入口不可达会使门禁失败，不能静默跳过。

既有完整回归另行运行：

```sh
node --test tests/prod-readiness/a3-browser/core.test.mjs
SCM_UI_ARTIFACTS="$PWD/test-artifacts/full-audit/s3" python3 tests/prod-readiness/a3-browser/run.py
```

既有 55 列 × 1000 行页面用于公共虚拟表容量验证。生产接入范围包括库存流水，以及总列数达到 30 的资料包尺寸指示和按颜色 BOM；小于阈值时保留原资料包表格。

## P34 / P35 补货与待料增量验收

```sh
python3 tests/audit-fix/s3-browser/bounded-lists.py
```

独立的 9 项回归只覆盖本次 API 升级：补货 235 个 SKU 的默认 50 / 末页 35 / 最大 100 分页，20 张草稿预览与独立总数，翻页的真实请求取消、清选和当前页生单，重取丢弃旧改量、错误重试和空态，待料的订单号 / 款号 / 算料单三种独立总数与预览悬浮提示，以及库存角标只取一行并读取总数。页面、Store 和请求实现均来自生产源码，HTTP 数据和故障注入明确为合成数据；真实 SQL 的有界读取、235 SKU 与 125 来源计数由 S2 另外验证。

产物单独写入 `test-artifacts/full-audit/s3/p34-35/`，保留上一阶段报告，不把此前 90 项浏览器结果冒充为新改动的验收。

## P35 齐料局部失败态与重试

```sh
python3 tests/audit-fix/s3-browser/production-kitting.py
```

独立的 6 项回归挂载实际 `ProductionTable`、需求订单列表、需求订单详情和生产单详情。明确注入 HTTP 403 / 404 / 503，核对失败不能冒充“—”或“未算料”、重试不触发行导航、恢复后显示真实齐料标签；生产表还验证慢页加载提示、`net::ERR_ABORTED` 和迟到结果不覆盖新页。订单列表与详情恢复后的标签仍能跳转到对应算料单。

HTTP 响应为合成契约，不能证明服务端 DataScope 或 board-only 权限已修复；真实权限回归由 S2 验证。结果、截图和原始“503 却显示 —”负向证据写入 `test-artifacts/full-audit/s3/p35-kitting/`；只将脚本和夹具提交，截图、日志继续作为 CI Artifact。
