# syntax=docker/dockerfile:1

# ---- Build: compile all modules and package the middleware fat JAR ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/pom.xml common/
COPY models/pom.xml models/
COPY repository/pom.xml repository/
COPY clients/pom.xml clients/
COPY services/pom.xml services/
COPY middleware/pom.xml middleware/
COPY common/src common/src
COPY models/src models/src
COPY repository/src repository/src
COPY clients/src clients/src
COPY services/src services/src
COPY middleware/src middleware/src

# Cache mounts keep the Maven distribution and dependencies across builds.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -q -DskipTests package \
    && cp middleware/target/middleware-*.jar /workspace/app.jar

# ---- Runtime: JRE only, non-root ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
