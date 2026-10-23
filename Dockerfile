# 빌드는 JDK 21로 bootJar만 만들고, 실행 이미지는 JRE만 둔다. 테스트(Testcontainers)는 이미지 빌드에서 돌리지 않는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# 소스가 바뀌어도 의존성 레이어는 캐시를 쓴다
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY src ./src
RUN ./gradlew bootJar --no-daemon -q && cp build/libs/*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/app.jar app.jar
USER app

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
