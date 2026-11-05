# SCM · 服装供应链管理系统

款式 → 订单 → 算料采购 → 发料 → 外发生产 → 回货，一条主线管到底。

- 需求：[docs/PRD.md](docs/PRD.md)
- 原型：[docs/原型/index.html](docs/原型/index.html)（下载后用浏览器打开，需要联网加载组件库）
- 进度：[docs/进度.md](docs/进度.md)

## 技术栈

后端 Spring Boot 4.1 / Java 21 / MyBatis-Plus / Flyway / PostgreSQL 16 / Redis 7；前端 Vue 3 / TypeScript / Element Plus / Vite。

## 本地运行

1. 复制 `src/main/resources/application-local.yaml.example` 为 `application-local.yaml`，填上数据库和 Redis 连接
2. 后端：`./mvnw spring-boot:run`（Swagger：`/swagger-ui.html`）
3. 前端：`cd web && pnpm install && pnpm dev`

## 部署

`./deploy/deploy.sh`，一条命令发布当前提交（需要本机 `deploy/deploy.local` 里配置服务器）。
