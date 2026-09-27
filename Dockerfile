# Build/test container: JDK 21 + Maven. Keycloak itself runs from the
# upstream image in docker-compose.yml.
FROM maven:3.9-eclipse-temurin-21

WORKDIR /workspace
