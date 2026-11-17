# 审查修复 UI 回归套件

16 个真实 API + Playwright 核心用例，匿名业务链覆盖见 [COVERAGE.md](COVERAGE.md)。由 `dev/ci/run-smoke.sh` 在全新隔离测试库与真实生产构建上运行；不接受生产地址。

`prepare_fixtures.py` 经真实 API 准备业务数据，`python -m unittest -v test_audit_ui` 执行断言并生成 PNG/JSON。基准版本使用 `prepare_before.py` 与 `capture_baseline.py`。运行账号只从环境读取，不写入 manifest 或产物。

套件生成不代表验收通过；具体结果与截图复核记录在 `docs/reviews/` 中。
