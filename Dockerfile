# FROM maven:3.9.6-eclipse-temurin-21 AS build
# WORKDIR /app
# COPY pom.xml .
# COPY src ./src
# RUN mvn clean package -DskipTests

# FROM eclipse-temurin:21-jre-alpine
# WORKDIR /app
# COPY --from=build /app/target/*.jar app.jar
# EXPOSE $PORT
# ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache Maven deps
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

# Skip tests + javadoc (fixes surefire issue)
RUN mvn clean package \
    -Dmaven.test.skip=true \
    -Dmaven.javadoc.skip=true \
    -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy JAR
COPY --from=build /app/target/*.jar app.jar

# Render port
EXPOSE $PORT

ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]