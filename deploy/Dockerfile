# 后端镜像：只有 Java 运行时和打好的 jar。jar 由 deploy.sh 在本机打好传上来，服务器上不装 Maven
FROM eclipse-temurin:21-jre-alpine

LABEL project=lingmao

# 不用 root 跑：应用被攻破时，拿到的只是一个普通用户。uid 固定，服务器上的上传目录 chown 给它
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app

WORKDIR /app
COPY app.jar app.jar
ENV TZ=Asia/Shanghai
USER app
EXPOSE 8080

# 堆最多用容器内存上限的 75%（compose 里限 512M，堆最多约 384M），剩下的留给 JVM 自己和线程栈
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
