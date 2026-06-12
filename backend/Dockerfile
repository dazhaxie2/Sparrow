# ---------- 后端构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q dependency:go-offline || true
COPY src ./src
RUN mvn -q -DskipTests package

# ---------- 前端构建阶段 ----------
FROM node:20-alpine AS frontend-build
WORKDIR /build
COPY sparrow-web/ ./
RUN npm ci
RUN npm run build

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend-build /build/target/sparrow.jar app.jar
COPY --from=frontend-build /build/dist /app/static
EXPOSE 8080
ENTRYPOINT ["java","-Xms256m","-Xmx768m","-jar","app.jar"]
