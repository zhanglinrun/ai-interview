FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY backend/pom.xml backend/pom.xml

RUN mvn -B -ntp -pl backend -am dependency:go-offline -Dmaven.test.skip=true

COPY backend/src backend/src

RUN mvn -B -ntp -pl backend -am package -Dmaven.test.skip=true && \
    mkdir -p /workspace/extracted && \
    java -Djarmode=tools -jar backend/target/ai-interview-backend.jar \
      extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

COPY --from=builder --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/application/ ./

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
