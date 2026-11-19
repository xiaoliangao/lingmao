# 后端镜像：Java 21 运行时 + 打好的 jar。jar 由 deploy/build-package.sh 在本机打好，放在构建目录的 app.jar。
# 基础镜像是 Ubuntu 系的 eclipse-temurin，不用 alpine：不知道部署方服务器的系统和内核版本，Debian / Ubuntu 系兼容面最宽（架构 §12.1）。
# 里面自带 curl（compose.yml 的健康检查、安装和升级脚本用它查 /api/version）和时区数据。
# 下面的 ENTRYPOINT 换掉了基础镜像自带的那个（它只负责导入自定义的 CA 证书，我们用不到）
FROM eclipse-temurin:21-jre

LABEL project=scm

# 不用 root 跑：应用被攻破时，拿到的只是一个普通用户。uid 固定 10001，安装脚本把宿主机上的上传、日志目录交给它
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --home-dir /home/app --shell /usr/sbin/nologin app

WORKDIR /app
COPY app.jar app.jar
ENV TZ=Asia/Shanghai
USER app
EXPOSE 8080

# 堆最多用容器内存上限的 75%（compose.yml 里限 1200m，堆最多约 900MB），剩下的给 JVM 自己和线程栈。
# 内存真的用完时直接退出，由 Docker 重启，不会半死不活地卡着
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
