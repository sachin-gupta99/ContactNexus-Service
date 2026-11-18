# syntax=docker/dockerfile:1.7

# ---- build stage ------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
# Prime the dependency cache from pom.xml first so a code change does not
# invalidate the Maven download layer.
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
 && mv target/*.jar target/app.jar

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Spring Boot + Hibernate needs headroom. On a 512MB Fly machine, cap the heap
# so native + metaspace + buffers have room, and use the serial GC which has
# a smaller footprint than G1 for small heaps.
ENV JAVA_OPTS="-Xmx400m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dserver.port=8080"

# A non-root user is a cheap hardening win: if the JVM is ever broken into,
# the attacker lands as 'app', not root.
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/uploads && chown -R app:app /app

COPY --from=build --chown=app:app /app/target/app.jar /app/app.jar

USER app
EXPOSE 8080

# exec form so the JVM is PID 1 and receives SIGTERM from Fly on redeploy,
# which lets Spring Boot run its shutdown hooks instead of being killed.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
