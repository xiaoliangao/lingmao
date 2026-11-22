# 智能助手镜像（可选服务，RFC 017 §13 ③）：Node 22 运行时 + 打好的 assistant/。
# 由 deploy/build-package.sh 在本机编译好、装好生产依赖（pg、yaml，都是纯 JS），构建目录里是：
#   dist/（编译后的 js）、node_modules/（平铺，没有符号链接）、package.json、tools.yaml、migrations/、openapi.json
# openapi.json 是这次打包导出的：工具的参数和说明从它取，所以助手和应用必须是同一个版本（镜像标签一样）。
# 镜像里不装依赖、不编译：构建时不用联网，给另一种 CPU 打包也不用模拟着跑 pnpm。
# 基础镜像用 Debian 系的 slim，不用 alpine：理由同 app.Dockerfile（不知道服务器的系统和内核）
FROM node:22.20.0-bookworm-slim

LABEL project=scm

WORKDIR /app
COPY . .
# 容器里要让 Nginx 连得到，听所有地址（本机开发默认只听 127.0.0.1）
ENV TZ=Asia/Shanghai NODE_ENV=production ASSISTANT_HOST=0.0.0.0
# 镜像自带的 node 用户（uid 1000），不用 root 跑；文件归 root，进程改不了自己的代码
USER node
EXPOSE 8091

# 堆上限 128MB（compose.yml 里容器限 192m；实测问答时常驻 60–70MB）
CMD ["node", "--max-old-space-size=128", "dist/main.js"]
