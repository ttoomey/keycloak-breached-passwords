# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Keycloak extensions (Java 21, Maven) that check passwords against the Have I Been Pwned range API. See `README.md` for the layout, HIBP stubs, dev realm and test user.

## Commands

Run Maven in the `dev` service, which mounts the Docker socket Testcontainers needs:

```bash
docker compose run --rm dev mvn package    # build jar into providers/ and run tests
docker compose run --rm dev mvn test -Dtest=EnvironmentSmokeTest#keycloakImportsTheDevRealm
docker compose up keycloak                 # restart it after each rebuild to load the jar
```

## Gotchas

- `docker-compose.yml` and the Testcontainers tests load the same fixtures: `src/test/resources/realm/breached-passwords-dev-realm.json` and `src/test/resources/wiremock/mappings/`. Changing one affects both.
- Versions are pinned in two places each: Keycloak in `pom.xml` (`keycloak.version`) and `docker-compose.yml`; WireMock in `docker-compose.yml` and `TestEnvironment`.
- The realm file name must match the realm name (`<realm>-realm.json`), or Keycloak refuses to import it.
