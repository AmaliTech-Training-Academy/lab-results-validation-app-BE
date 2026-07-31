# Stage 1: Build (compiles the application from source)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Stage 2 (CI path): extract layers from a PRE-BUILT jar (no source available —
# only the jar, supplied via JAR_FILE from a downloaded CI artifact). This repo's
# jar is a thin executable jar with a Class-Path manifest entry pointing at an
# external lib/ directory (not the classic nested-BOOT-INF format), so the
# "dependencies" layer is lib/*.jar and "application" is just app.jar itself.
FROM eclipse-temurin:21-jre-alpine AS layers-prebuilt
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 3 (CI): runtime built from the pre-built jar's extracted layers, so a
# rebuild that only changes application code re-copies just app.jar instead of
# the whole dependency set.
FROM eclipse-temurin:21-jre-alpine AS runtime-prebuilt
WORKDIR /app
RUN apk upgrade --no-cache && addgroup -S spring && adduser -S spring -G spring
COPY --from=layers-prebuilt --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=layers-prebuilt --chown=spring:spring /app/extracted/application/ ./
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# Stage 4: extract layers from the freshly-built jar (default/local build path).
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN apk upgrade --no-cache && addgroup -S spring && adduser -S spring -G spring
COPY --from=layers --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=layers --chown=spring:spring /app/extracted/application/ ./
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
