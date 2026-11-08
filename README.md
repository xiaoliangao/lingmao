# SCM · 服装供应链管理系统

款式 → 订单 → 算料采购 → 发料 → 外发生产 → 回货，一条主线管到底。

- 需求：[docs/PRD.md](docs/PRD.md)
- 原型：[docs/原型/index.html](docs/原型/index.html)（下载后用浏览器打开，不用联网；左下"意见"可以按页留言）
- 架构：[docs/架构设计.md](docs/架构设计.md)
- 进度：[docs/进度.md](docs/进度.md)

## 技术栈

后端 Spring Boot 4.1 / Java 21 / MyBatis-Plus / Flyway / PostgreSQL 16 / Redis 7；前端 Vue 3 / TypeScript / Element Plus / Vite。

## 本地运行

1. 起本机的 PostgreSQL 和 Redis：`docker compose -f dev/compose.yml up -d`（数据在 `dev/data/`；不用了就 `docker compose -f dev/compose.yml down --rmi all && rm -rf dev/data`）
2. 复制 `src/main/resources/application-local.yaml.example` 为 `application-local.yaml`，填上 JWT 密钥和初始管理员密码（连接已经对应第 1 步）
3. 后端：`./mvnw spring-boot:run`（Swagger：`/swagger-ui.html`）
4. 前端：`cd web && pnpm install && pnpm dev`

## 部署

由甲方那边部署：我们交付一套 Docker 部署包和[部署手册](deploy/package/部署手册.md)，设计见 [RFC 002](docs/rfc/002-交付部署包.md)。

打包：`deploy/build-package.sh`（第一次安装加 `--full`），包在 `dist/`。
