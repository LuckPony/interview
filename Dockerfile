# ===== 面霸 · 后端镜像 =====
# 多阶段构建：Gradle 产出可执行 jar，JRE 21 运行。

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
EXPOSE 23333
ENTRYPOINT ["java", "-jar", "app.jar"]
