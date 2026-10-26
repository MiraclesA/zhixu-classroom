FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY server/pom.xml ./pom.xml
COPY server/src ./src
RUN mvn -B package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/classroom-1.0.0.jar /app/app.jar
EXPOSE 8097
ENTRYPOINT ["java","-jar","/app/app.jar"]
