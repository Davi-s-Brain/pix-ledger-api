# ---- build ----
FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src ./src
# skipTests: a suíte (Testcontainers) roda no CI/desenvolvedor, não na imagem
RUN ./mvnw -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /app/target/pix-ledger-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]