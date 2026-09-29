# AgentRelease Lab — platform image.
# Build with JDK 21 (Temurin), matching the pinned toolchain.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN apt-get update -qq && apt-get install -y -qq maven \
 && mvn -B -DskipTests package \
 && cp target/agentrelease-lab-platform-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
