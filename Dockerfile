FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY web-support ./web-support
COPY demo-fixtures ./demo-fixtures
COPY verification ./verification
COPY booking-core ./booking-core
COPY hotel-core ./hotel-core
COPY hotel-service ./hotel-service
COPY booking-service ./booking-service
COPY api-gateway ./api-gateway
COPY discovery-server ./discovery-server
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx768m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process bootJar :verification:installDist

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 AS runtime
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
CMD ["java", "-jar", "/app/app.jar"]

FROM runtime AS hotel-service
COPY --from=build /workspace/hotel-service/build/libs/hotel-service-0.1.0-SNAPSHOT.jar /app/app.jar

FROM runtime AS booking-service
COPY --from=build /workspace/booking-service/build/libs/booking-service-0.1.0-SNAPSHOT.jar /app/app.jar

FROM runtime AS api-gateway
COPY --from=build /workspace/api-gateway/build/libs/api-gateway-0.1.0-SNAPSHOT.jar /app/app.jar

FROM runtime AS discovery-server
COPY --from=build /workspace/discovery-server/build/libs/discovery-server-0.1.0-SNAPSHOT.jar /app/app.jar

FROM runtime AS fault-proxy
COPY --from=build /workspace/verification/build/install/verification/lib /app/lib
CMD ["java", "-cp", "/app/lib/*", "dev.hotelbooking.verification.FaultProxyKt"]
