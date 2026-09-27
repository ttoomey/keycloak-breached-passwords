# keycloak-breached-passwords

Keycloak extensions that check passwords against known data breaches, using
[Have I Been Pwned](https://haveibeenpwned.com/API/v3#PwnedPasswords).
The first planned piece is a password policy, which checks passwords when
they're set. A login-time check may follow.

**Status:** build and test scaffolding only; there's no provider code yet.

## Layout

| Path | Purpose |
| --- | --- |
| `Dockerfile` | Build/test container (Maven 3.9, JDK 21). |
| `docker-compose.yml` | `dev` (Maven), `keycloak` (Keycloak 26.7.4 with the built jar), `hibp` (WireMock standing in for the HIBP API). |
| `providers/` | `mvn package` writes the jar here; it's mounted into Keycloak's `providers/`. |
| `src/test/resources/realm/` | Dev realm. It's imported by the `keycloak` service and by the tests. |
| `src/test/resources/wiremock/mappings/` | HIBP stubs. They're loaded by the `hibp` service and by the tests. |

## Usage

```bash
# Build the jar into providers/ and run the tests
docker compose run --rm dev mvn package

# Start Keycloak and the HIBP stub
docker compose up -d keycloak

# Load a rebuilt jar
docker compose restart keycloak
```

- Admin console: http://localhost:8080 (`admin` / `admin`)
- Test user: http://localhost:8080/realms/breached-passwords-dev/account, signing in as
  `hibp-tester@example.com` / `temporary-password-for-dev`. The password is
  temporary, so Keycloak forces a password change, which runs the realm's
  password policy.
- HIBP stub: `http://hibp:8080` from Keycloak, `http://localhost:8081` from
  the host. Configure the provider's API base URL to the former.

Data is ephemeral. `start-dev` uses an in-container H2 database, and the realm
is re-imported whenever the container is recreated.

## Tests

Tests use Testcontainers
([testcontainers-keycloak](https://github.com/dasniko/testcontainers-keycloak))
to start Keycloak and WireMock. The `dev` service
mounts the Docker socket and sets `TESTCONTAINERS_HOST_OVERRIDE` so this works
inside the container. `mvn test` on the host also works if Docker is
available there.

`EnvironmentSmokeTest` only checks the tooling. Once the provider exists, add
`.withProviderClassesFrom("target/classes")` to the `KeycloakContainer`.

### HIBP stubs

The stubs mirror the real
[range API](https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange):
`GET /range/{first 5 hex chars of SHA-1}` returns `SUFFIX:COUNT` lines.

| Password | Prefix | Stub result |
| --- | --- | --- |
| `password` | `5BAA6` | Pwned: suffix `1E4C9B93F3F0682250B6CF8331B7EE68FD8`, count 10434004 |
| `correct-horse-battery-staple` | `DD606` | Pwned: suffix `CD49BBBD06B4C2606FC2449F8FB87975786`, count 386 |
| `hibp-outage-simulation-password` | `F8CBE` | HTTP 503 |
| anything else | any | 200 with padding entries only (not pwned) |

`password` fails the dev realm's `length(15)` rule before HIBP is checked, so
use `correct-horse-battery-staple` to try the pwned path by hand.

## Dev realm

`dev-realm.json` defines the `breached-passwords-dev` realm. It has only
what's needed to exercise a password policy:

- A sample `passwordPolicy` of built-in rules, so the HIBP check runs
  alongside other policies.
- Email as username, which the `notUsername` / `notEmail` rules depend on.
- The test user described above.

Add the HIBP policy to `passwordPolicy` only after the provider exists.
Importing a realm that names an unknown policy fails.

## Known constraints

- `PasswordPolicyProvider` is on Keycloak's internal SPI
  (`keycloak-server-spi-private`). Keycloak logs a startup warning for it,
  and upgrades may change the interface.
- The Keycloak version is set in two places: `keycloak.version` in `pom.xml`
  and the image tag in `docker-compose.yml`. The WireMock image tag also
  appears in both `docker-compose.yml` and `EnvironmentSmokeTest`.
