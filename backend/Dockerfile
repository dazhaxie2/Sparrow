# 共享 build stage:一次性下载依赖 + 编译全部模块。
# 通过 docker compose 的 build.target 选择最终运行 stage。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/docker/maven-settings.xml /root/.m2/settings.xml

# 先单独拷 pom,go-offline 吃缓存层,源码改动不会触发重新下载依赖
COPY backend/pom.xml .
COPY backend/sparrow-common/pom.xml sparrow-common/pom.xml
COPY backend/sparrow-gateway/pom.xml sparrow-gateway/pom.xml
COPY backend/sparrow-user/pom.xml sparrow-user/pom.xml
COPY backend/sparrow-graph/pom.xml sparrow-graph/pom.xml
COPY backend/sparrow-trade/pom.xml sparrow-trade/pom.xml
COPY backend/sparrow-ai/pom.xml sparrow-ai/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -e -ntp \
    -Daether.connector.basic.threads=1 \
    -Daether.connector.connectTimeout=10000 \
    -Daether.connector.requestTimeout=30000 \
    -DskipTests dependency:go-offline || true

# 全量拷源码 + 打包
COPY backend/sparrow-common/src sparrow-common/src
COPY backend/sparrow-gateway/src sparrow-gateway/src
COPY backend/sparrow-user/src sparrow-user/src
COPY backend/sparrow-graph/src sparrow-graph/src
COPY backend/sparrow-trade/src sparrow-trade/src
COPY backend/sparrow-ai/src sparrow-ai/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp \
    -Daether.connector.basic.threads=1 \
    -Daether.connector.connectTimeout=10000 \
    -Daether.connector.requestTimeout=30000 \
    -DskipTests package

# 前端构建(仅 gateway 镜像用)
FROM node:20-alpine AS frontend-build
WORKDIR /build
COPY frontend/ ./
RUN npm ci && npm run build

# ===== 5 个运行 stage =====
FROM eclipse-temurin:17-jre AS gateway-runtime
WORKDIR /app
COPY --from=build /build/sparrow-gateway/target/sparrow-gateway.jar app.jar
COPY --from=frontend-build /build/dist /app/static
EXPOSE 8080
ENTRYPOINT ["java","-Xms384m","-Xmx768m","-jar","app.jar"]

FROM eclipse-temurin:17-jre AS user-runtime
WORKDIR /app
COPY --from=build /build/sparrow-user/target/sparrow-user.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-Xms384m","-Xmx768m","-jar","app.jar"]

FROM eclipse-temurin:17-jre AS graph-runtime
WORKDIR /app
COPY --from=build /build/sparrow-graph/target/sparrow-graph.jar app.jar
EXPOSE 8082
# Tomcat max=50 线程 + -Xmx1024m:50 并发×500KB≈25MB 可控。
# MALLOC_ARENA_MAX=2 + 4G 容器,JDK 镜像 off-heap ~500MB,总 RSS ~2G。
ENTRYPOINT ["java","-Xms512m","-Xmx1024m","-jar","app.jar"]

FROM eclipse-temurin:17-jre AS ai-runtime
WORKDIR /app
COPY --from=build /build/sparrow-ai/target/sparrow-ai.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java","-Xms512m","-Xmx1g","-jar","app.jar"]

FROM eclipse-temurin:17-jre AS trade-runtime
WORKDIR /app
COPY --from=build /build/sparrow-trade/target/sparrow-trade.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java","-Xms384m","-Xmx768m","-jar","app.jar"]
