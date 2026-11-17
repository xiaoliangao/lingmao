# 审查整改验证

入口为通用 [CI 工作流](../../.github/workflows/ci.yml)，使用 `ubuntu-latest`、Java 21、Node 24.22.1、pnpm 11.25.0、PostgreSQL 16、Redis 7 与 Python Playwright 1.62.0。生产依赖和现有启动入口不变。

流水线依次安装锁定依赖，执行 `./mvnw verify`（含真实数据库迁移、架构检查、单测和集成测试），再执行 `pnpm typecheck`、`pnpm lint`、`pnpm test`、`pnpm build`。类型检查实际调用 `vue-tsc --noEmit` 和 `tsc --noEmit`，不以打包成功替代。原型由 `check-prototype.py` 启动配套 Playwright 浏览器，再调用现有 `docs/原型/check.mjs` 的自检与截图；原本的本机 CHROME 入口保留，重新生成 HTML 后检查工作树无漂移。

独立检查按自身前置条件继续执行：前后端构建成功后，即使原型或运维检查失败，真实服务与页面仍可收集证据。任何失败仍使整个任务失败；没有 `continue-on-error`，运维日志管道启用 `pipefail`，不会因 `tee` 成功掩盖脚本错误。

## 真实服务与浏览器

`run-smoke.sh after` 在隔离的 `scm_smoke_test` 建库并启动生产 JAR（18080），通过 `/api/version` 健康检查后注入仓库已有匿名主数据。业务单据、角色与用户均通过真实 API 建立，再由 18081 上的静态站点和代理加载正式 Vue 产物。详见 [UI 覆盖清单](ui/COVERAGE.md)。故障注入仅用于明确列出的错误/延迟场景；正常业务链不使用 Mock。

PR 还会以事件给出的真实 base SHA 创建临时 worktree，重新构建原版并运行 `capture-before.sh`。修复前截图只记录基准状态和原有 SKU 往返差异，不把原缺陷当作通过。截图、JSON 断言、合成导出、脱敏服务日志与查询计划位于 `test-artifacts/` 或 `target/`，由 Actions 上传，均被 `.gitignore` 排除。

`run-smoke.sh` 只允许 GitHub Actions 隔离 runner；Python 客户端只接受 loopback 地址，API 固定 18080，新建库必须以 `_test` 结尾。运行密码和签名密钥随机生成、只在进程环境中使用，不保存 Cookie、Token、请求头、HAR、浏览器存储快照或录屏。失败截图遮住登录输入，日志在上传前替换运行密钥。不要把生产地址、账号或数据库传给测试工具。

## 运维回归的边界

`check-backup-readonly.sh` 在独立本机 sshd/chroot 中验证可读备份、拒绝写入/删除/改名/命令/转发/目录逃逸；不修改 runner 既有 sshd。`check-restore.sh` 对临时 Docker PostgreSQL 执行实际 `pg_dump` / `pg_restore` 及交付包的恢复脚本，验证合法删除不误判、同快照数量和约束、损坏备份拒收。它们验证配置方案及交付脚本，不能代替真实部署主机、异地账户和实际业务对账。

运行状态、最终提交、原报告逐项销项及人工截图检查，以 `docs/reviews/` 的整改验收记录和对应 Actions run 为准。生成测试代码或发现用例数量不等于验收通过。
