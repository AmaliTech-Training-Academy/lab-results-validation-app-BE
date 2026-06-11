# Stage 1: Build (compiles the application from source)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Stage 2 (CI): runtime built from a PRE-BUILT jar.
FROM eclipse-temurin:21-jre-alpine AS runtime-prebuilt
WORKDIR /app
# Apply latest Alpine security patches (fixes CVE-2026-45447 and any future OS-level CVEs)
RUN apk upgrade --no-cache
RUN addgroup -S spring && adduser -S spring -G spring
ARG JAR_FILE=target/*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]


FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Apply latest Alpine security patches (fixes CVE-2026-45447 and any future OS-level CVEs)
RUN apk upgrade --no-cache
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
