# 共享 build stage:一次性下载依赖 + 编译全部模块。
# 通过 docker compose 的 build.target 选择最终运行 stage。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Keep settings outside /root/.m2: the BuildKit cache mount below replaces that
# directory at RUN time and would otherwise hide the configured mirror.
COPY backend/docker/maven-settings.xml /opt/sparrow/maven-settings.xml

# 先单独拷 pom,go-offline 尽量预热缓存层,源码改动不会触发重新下载依赖
COPY backend/pom.xml .
COPY backend/sparrow-common/pom.xml sparrow-common/pom.xml
COPY backend/sparrow-gateway/pom.xml sparrow-gateway/pom.xml
COPY backend/sparrow-user/pom.xml sparrow-user/pom.xml
COPY backend/sparrow-graph/pom.xml sparrow-graph/pom.xml
COPY backend/sparrow-trade/pom.xml sparrow-trade/pom.xml
COPY backend/sparrow-ai/pom.xml sparrow-ai/pom.xml
COPY backend/sparrow-industry-chain/pom.xml sparrow-industry-chain/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /opt/sparrow/maven-settings.xml -B -e -ntp \
    -Daether.connector.basic.threads=4 \
    -Daether.connector.connectTimeout=30000 \
    -Daether.connector.requestTimeout=120000 \
    -Daether.transport.http.retryHandler.count=5 \
    -DskipTests dependency:go-offline || true

# 全量拷源码 + 打包
COPY backend/sparrow-common/src sparrow-common/src
COPY backend/sparrow-gateway/src sparrow-gateway/src
COPY backend/sparrow-user/src sparrow-user/src
COPY backend/sparrow-graph/src sparrow-graph/src
COPY backend/sparrow-trade/src sparrow-trade/src
COPY backend/sparrow-ai/src sparrow-ai/src
COPY backend/sparrow-industry-chain/src sparrow-industry-chain/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /opt/sparrow/maven-settings.xml -B -ntp \
    -Daether.connector.basic.threads=4 \
    -Daether.connector.connectTimeout=30000 \
    -Daether.connector.requestTimeout=120000 \
    -Daether.transport.http.retryHandler.count=5 \
    -DskipTests package

# 前端构建(仅 gateway 镜像用)
FROM node:20-alpine AS frontend-build
WORKDIR /build
COPY frontend/ ./
RUN npm ci && npm run build

# ===== 6 个运行 stage =====
# JDK 21 + G1GC(默认)。堆上限统一压低,适配 16G 机器全栈同机部署(RSS≈堆×1.4,需留足元空间+off-heap 给容器 limit)。
# 每个服务开 GC 日志(写 /app/gc.log,2 个文件轮转各 10M),便于排查 Full GC / 内存压力。
FROM eclipse-temurin:21-jre AS gateway-runtime
WORKDIR /app
COPY --from=build /build/sparrow-gateway/target/sparrow-gateway.jar app.jar
COPY --from=frontend-build /build/dist /app/static
EXPOSE 8080
ENTRYPOINT ["java","-Xms256m","-Xmx512m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]

FROM eclipse-temurin:21-jre AS user-runtime
WORKDIR /app
COPY --from=build /build/sparrow-user/target/sparrow-user.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-Xms256m","-Xmx512m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]

FROM eclipse-temurin:21-jre AS graph-runtime
WORKDIR /app
COPY --from=build /build/sparrow-graph/target/sparrow-graph.jar app.jar
EXPOSE 8082
# -Xmx768m:容器限 1G,留 off-heap 余量。Tomcat threads.max 在开虚拟线程后失效(spring.threads.virtual.enabled)。
ENTRYPOINT ["java","-Xms384m","-Xmx768m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]

FROM eclipse-temurin:21-jre AS ai-runtime
WORKDIR /app
COPY --from=build /build/sparrow-ai/target/sparrow-ai.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java","-Xms384m","-Xmx768m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]

FROM eclipse-temurin:21-jre AS trade-runtime
WORKDIR /app
COPY --from=build /build/sparrow-trade/target/sparrow-trade.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java","-Xms256m","-Xmx512m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]

FROM eclipse-temurin:21-jre AS industry-chain-runtime
WORKDIR /app
COPY --from=build /build/sparrow-industry-chain/target/sparrow-industry-chain.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java","-Xms256m","-Xmx512m","-Xlog:gc*:file=/app/gc.log:time,uptime,level,tags:filecount=2,filesize=10m","-jar","app.jar"]
