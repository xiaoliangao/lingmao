# 前端镜像：Nginx 稳定版官方镜像（Debian 系，不用 alpine，理由同 app.Dockerfile）+ 前端打包出来的文件。
# 前端文件由 deploy/build-package.sh 放在构建目录的 dist/（就是 web/ 下 pnpm build 的产物）。
#
# Nginx 配置不打进镜像：安装目录的 nginx/ 由 compose.yml 挂进来，按 .env 的 SCM_WEB_MODE 用 http.conf 或 https.conf，
# 部署方能直接看到、能在 trusted-proxies.conf 里加自己的入口地址。镜像自带 curl，健康检查用它
FROM nginx:stable

LABEL project=scm

COPY dist/ /usr/share/nginx/html/
