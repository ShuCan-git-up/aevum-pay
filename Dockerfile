# 构建上下文是仓库根目录。前端在打包时复制进 Spring 的 static。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY frontend frontend
RUN mvn -f backend/pom.xml -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget \
    && addgroup -S app && adduser -S app -G app
COPY --from=build /src/backend/target/aevum-pay-backend-*.jar /app/app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=8 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
