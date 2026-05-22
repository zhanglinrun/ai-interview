FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY backend/pom.xml backend/pom.xml

RUN mvn -pl backend -am dependency:go-offline -DskipTests

COPY backend/src backend/src

RUN mvn -pl backend -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /workspace/backend/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
