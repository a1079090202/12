# 构建阶段：固定具体版本标签，避免浮动标签带来的不可复现构建
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# 镜像内无 Docker，无法运行 Zonky 内嵌 PG 集成测试；测试由 CI / 本地 mvn verify 执行
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21.0.5_11-jre-jammy
WORKDIR /app

# 非 root 运行；uid/gid 固定为 1001
RUN groupadd -r --gid 1001 app \
    && useradd -r --uid 1001 --gid 1001 --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app

COPY --from=build /build/target/dockyard-1.0.0.jar app.jar
USER app

EXPOSE 8080

# JAVA_OPTS 可在 compose / 运行时覆盖；默认按容器内存上限比例取堆
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
# shell 形式以便 JAVA_OPTS 展开；信号由 JVM 直接接收（PID 1 为 java）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
