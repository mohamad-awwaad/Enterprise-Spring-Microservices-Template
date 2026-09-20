# syntax=docker/dockerfile:1
#
# Single Dockerfile shared by all 5 Java services (bff, gateway, profile-service,
# order-service, keycloak-admin-service). Select the service with --build-arg
# MODULE=<dir-name>, e.g.:
#
#   docker build --build-arg MODULE=bff -t template/bff .
#
# ==============================================================================
# Builder - compiles and packages the whole Maven reactor.
#
# Intentionally has NO ARG: Docker/BuildKit builds and caches this stage once
# and reuses it for every service image built from this Dockerfile, instead of
# rebuilding the whole reactor 5 times.
# ==============================================================================
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# POMs first so the dependency graph layer is only invalidated when a POM
# actually changes, not on every source edit.
COPY pom.xml ./
COPY common-core/pom.xml common-core/pom.xml
COPY common-web/pom.xml common-web/pom.xml
COPY common-security/pom.xml common-security/pom.xml
COPY dependencies-bom/pom.xml dependencies-bom/pom.xml
COPY bff/pom.xml bff/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY profile-service/pom.xml profile-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY keycloak-admin-service/pom.xml keycloak-admin-service/pom.xml

# Now the sources for every module.
COPY common-core/src common-core/src
COPY common-web/src common-web/src
COPY common-security/src common-security/src
COPY bff/src bff/src
COPY gateway/src gateway/src
COPY profile-service/src profile-service/src
COPY order-service/src order-service/src
COPY keycloak-admin-service/src keycloak-admin-service/src

RUN mvn -B -ntp -DskipTests package

# ==============================================================================
# Extractor - splits one service's repackaged jar into Spring Boot's
# dependencies / application layers (jarmode=tools), so the runtime stage can
# copy them as separate, independently-cacheable layers: the large third-party
# dependency layer rarely changes, while the small application layer (our own
# code) changes on every build.
# ==============================================================================
FROM eclipse-temurin:25-jre-alpine AS extractor
ARG MODULE
WORKDIR /extract
COPY --from=builder /build/${MODULE}/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted --application-filename application.jar \
    && mkdir -p extracted/dependencies/lib extracted/application/lib

# ==============================================================================
# Runtime
# ==============================================================================
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

# Dependency layer first (big, stable) then the application layer (small,
# changes every build) - both merged into ./lib since application.jar's
# manifest Class-Path references every jar as a flat lib/<name>.jar.
COPY --from=extractor /extract/extracted/dependencies/lib/ ./lib/
COPY --from=extractor /extract/extracted/application/lib/ ./lib/
COPY --from=extractor /extract/extracted/application/application.jar ./application.jar

USER spring:spring

ENTRYPOINT ["java", "-jar", "application.jar"]
