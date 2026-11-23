# 可选构建方式：在有 Docker 的机器上直接用 Dockerfile 构建轻量镜像。
# 构建上下文必须是仓库根目录：
#   docker build -f docker/Dockerfile -t tonglink-studio:0.1.0 .

FROM python:3.11-slim-bookworm

# Java 21 与 Node.js 22 运行时随仓库提供，保证单容器可执行代码节点
COPY docker/extra/opt/java /opt/java
COPY docker/extra/opt/node /opt/node
COPY backend/tonglink-app/target/tonglink-studio.jar /app/app.jar

RUN mkdir -p /app/data

ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:/opt/node/bin:/usr/local/bin:/usr/bin:/bin \
    SPRING_PROFILES_ACTIVE=lite \
    TONGLINK_DB_PATH=/app/data/tonglink.db \
    TZ=Asia/Shanghai

WORKDIR /app
VOLUME ["/app/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/actuator/health', timeout=3)" || exit 1

ENTRYPOINT ["java", "-Xms1024m", "-Xmx1024m", "-jar", "/app/app.jar"]
