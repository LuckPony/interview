# ===== 面霸 · 后端镜像 =====
# 多阶段构建：gradle 出可执行 jar → JRE 21 运行。
# 构建：docker build -t mianba-backend .
# 生产部署见 docker-compose.prod.yml。

# ---- 构建阶段 ----
FROM gradle:9.5.1-jdk21 AS build
WORKDIR /work
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
COPY app ./app
RUN gradle :app:bootJar --no-daemon

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /work/app/build/libs/app-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
