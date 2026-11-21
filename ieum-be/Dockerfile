# Railway·Render 공용. 저장소 최상위에 ieum-be/ 폴더가 있으므로 서비스의 Root Directory를 ieum-be로 둔다.

# 1) 빌드: 의존성을 먼저 받아 두면 소스만 바뀐 재배포가 빨라진다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 2) 실행: JRE만, root가 아닌 사용자로
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
# 포트는 호스팅이 넣어 주는 PORT를 쓴다 (application.yaml: server.port=${PORT:8080})
EXPOSE 8080
# 무료 플랜(512MB 안팎)에 맞춰 힙을 컨테이너 메모리의 75%로
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
