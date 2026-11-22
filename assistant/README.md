# 智能助手服务

用户在系统里点开问的助手（RFC 017 C 档，设计见 `docs/rfc/017-三期agent与AI.md` §3、§13，`docs/rfc/009-智能助手.md`）。
一个独立的 Node 服务，部署包里是可选的 `assistant` 容器，Nginx 把 `/assistant/` 转给它。

- **只读**：浏览器用登录身份换一张 30 分钟的助手令牌（`POST /api/auth/assistant-token`）交给它，它拿这张令牌 GET app 的接口。写接口、导出下载、文件地址在 app 那边就拦了，数据范围和金额权限照这个人的来。
- **工具**是 `tools.yaml` 里列的 app 接口，参数和说明取自打包时导出的 `openapi.json`；启动时逐条核对，不合规起不来。
- **模型**走 OpenAI 兼容的 chat completions 接口（直接用 fetch，不用厂商 SDK），地址、Key、模型名在 `.env`。没配模型服务也能起，问话回"还没有配置模型"。
- **自己的表**在同一个 PostgreSQL 的 `assistant` schema，用单独的库账号（读不到 app 的表）：对话、工具调用记录、每人每天的次数和全系统的 token 用量。迁移在 `migrations/`，只加不改。

## 本机开发

Node 22.18 以上（直接跑 `.ts`，不用编译）。先起本机开发库（仓库根目录的 `dev/compose.yml`），建助手的账号和 schema：

```sh
cd assistant && pnpm install
./dev/setup-db.sh            # 开发库 scm；打印连接串
```

起服务（app 在 8080 或自己起的 18080；`openapi.json` 从 app 的 `/v3/api-docs` 存一份）：

```sh
ASSISTANT_DATABASE_URL=postgres://scm_assistant@127.0.0.1:5433/scm \
ASSISTANT_APP_URL=http://127.0.0.1:8080 ASSISTANT_OPENAPI=./openapi.json \
ASSISTANT_LLM_BASE_URL=https://模型服务/v1 ASSISTANT_LLM_MODEL=模型名 ASSISTANT_LLM_API_KEY=… \
pnpm dev
```

模型的 Key 只放环境变量或 gitignore 的 `.env`，不写进仓库。

## 测试

```sh
pnpm typecheck
pnpm test                                    # 单测，不连库
ASSISTANT_OPENAPI=./openapi.json pnpm test   # 再核对仓库里的 tools.yaml
./dev/setup-db.sh scm_assistant_test && pnpm test:db   # 连本机库：迁移、限额、对话、账号隔离
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ASSISTANT_DATABASE_URL` | 必填 | 助手自己的库账号 |
| `ASSISTANT_APP_URL` | `http://app:8080` | app 的地址 |
| `ASSISTANT_OPENAPI` / `ASSISTANT_TOOLS` | `./openapi.json` / `./tools.yaml` | 工具从这两个文件生成 |
| `ASSISTANT_LLM_BASE_URL` / `_MODEL` / `_API_KEY` | 空 | 模型服务；地址到 `/v1` 为止 |
| `ASSISTANT_LLM_MODE` | `external` | `external` 外部云端（数据出公司）/ `self-hosted` 内网自部署；开 AI 开关前的弹窗显示 |
| `ASSISTANT_LLM_STREAM_USAGE` | `true` | 模型服务不认 `stream_options` 时填 `false` |
| `ASSISTANT_LLM_TIMEOUT_SECONDS` | 90 | 一次模型调用最多等多久 |
| `ASSISTANT_USER_DAILY_REQUESTS` | 200 | 每人每天最多问几句 |
| `ASSISTANT_DAILY_TOKENS` | 5000000 | 全系统每天最多用多少 token（一句话约 2 万） |
| `ASSISTANT_MAX_STEPS` | 6 | 一句话里模型最多来回几轮 |
| `ASSISTANT_PORT` | 8091 | |
| `ASSISTANT_SYSTEM_NAME` | 服装 SCM | 写进提示词；和前端 `web/src/config.ts` 的系统名一致 |
