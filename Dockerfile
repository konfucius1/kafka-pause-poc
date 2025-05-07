FROM gradle:8.5-jdk21 AS build

WORKDIR /app

COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradlew ./
COPY gradle ./gradle
COPY build.gradle ./
COPY settings.gradle ./

COPY src ./src

RUN ./gradlew clean build --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]