# keycloak-breached-passwords

Keycloak extensions that check passwords against known data breaches, using
[Have I Been Pwned](https://haveibeenpwned.com/API/v3#PwnedPasswords).
The first piece is a password policy, which checks passwords when they're
set. A login-time check may follow.

**Status:** MVP password policy (`hibp`). No caching, no configurable breach
threshold, English messages only.

## Password policy

Enable it in a realm's password policy, e.g. `length(15) and hibp` (Admin
Console: *Authentication → Policies → Not Breached (Have I Been Pwned)*). It
rejects any password that appears in the Pwned Passwords corpus. Only the
first 5 hex characters of the password's SHA-1 are sent, with
`Add-Padding: true`.

If HIBP can't be reached after 3 attempts (500 ms connect, 1 s read timeout,
250/500 ms backoff), the check fails open: the password is accepted and one
WARN line is logged. A 4xx other than 429, or an unparseable 200, fails open
right away.

| SPI key | Env var | Default |
| --- | --- | --- |
| `base-url` | `KC_SPI_PASSWORD_POLICY__HIBP__BASE_URL` | `https://api.pwnedpasswords.com` |

The base URL is server config, not realm config. Keycloak won't start if it
isn't an absolute `http(s)` URL with a host and no query or fragment.

## Layout

| Path | Purpose |
| --- | --- |
| `.github/workflows/` | `tests.yml` runs the tests on PRs and `main`; `release.yml` publishes a release on a `v*` tag. |
| `Dockerfile` | Build/test container (Maven 3.9, JDK 21). |
| `docker-compose.yml` | `dev` (one-off Maven commands), `shell` (long-running Maven shell), `keycloak` (Keycloak 26.7.4 with the built jar), `hibp` (WireMock standing in for the HIBP API). |
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

# Long-running build shell, e.g. for VS Code "Attach to Running Container"
docker compose up -d shell
```

- Admin console: http://localhost:8080 (`admin` / `admin`)
- Test user: http://localhost:8080/realms/breached-passwords-dev/account, signing in as
  `hibp-tester@example.com` / `temporary-password-for-dev`. The password is
  temporary, so Keycloak forces a password change, which runs the realm's
  password policy.
- HIBP stub: `http://hibp:8080` from Keycloak, `http://localhost:8081` from
  the host. Compose points the policy's `base-url` at the former.

Data is ephemeral. `start-dev` uses an in-container H2 database, and the realm
is re-imported whenever the container is recreated.

## Tests

Tests use Testcontainers
([testcontainers-keycloak](https://github.com/dasniko/testcontainers-keycloak))
to start Keycloak and WireMock. The `dev` service
mounts the Docker socket and sets `TESTCONTAINERS_HOST_OVERRIDE` so this works
inside the container. `mvn test` on the host also works if Docker is
available there.

`TestEnvironment` starts both containers once per test run, with the provider
loaded from `target/classes`. `EnvironmentSmokeTest` checks the tooling.
`HibpPasswordPolicyTest` drives the login theme's Update Password form.
`BreachCheckerTest` and `BaseUrlTest` are plain unit tests.

### HIBP stubs

The stubs mirror the real
[range API](https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange):
`GET /range/{first 5 hex chars of SHA-1}` returns `SUFFIX:COUNT` lines.

| Password | Prefix | Stub result |
| --- | --- | --- |
| `password` | `5BAA6` | Pwned: suffix `1E4C9B93F3F0682250B6CF8331B7EE68FD8`, count 10434004 |
| `correct-horse-battery-staple` | `DD606` | Pwned: suffix `CD49BBBD06B4C2606FC2449F8FB87975786`, count 386 |
| `hibp-outage-simulation-password` | `F8CBE` | HTTP 503 |
| `hibp-timeout-simulation-password` | `C02ED` | 200 with padding only, after 1.5 s (past the 1 s read timeout) |
| anything else | any | 200 with padding entries only (not pwned) |

`password` fails the dev realm's `length(15)` rule before HIBP is checked, so
use `correct-horse-battery-staple` to try the pwned path by hand.

## Dev realm

`breached-passwords-dev-realm.json` defines the `breached-passwords-dev`
realm. Keycloak requires the file name to match the realm name. It has only
what's needed to exercise a password policy:

- A `passwordPolicy` of built-in rules plus `hibp`, so the HIBP check runs
  alongside other policies.
- Email as username, which the `notUsername` / `notEmail` rules depend on.
- The test user described above.

Importing the realm checks the test user's password against HIBP too. When
compose starts, WireMock may not be ready yet. The check then fails open
after about 5 s and logs one WARN, which only slows startup.

## Releasing

Set `<version>` in `pom.xml` to the release version (no `-SNAPSHOT`), commit,
then push a matching tag, e.g. `git tag v1.0.0 && git push origin v1.0.0`.
The release workflow runs the tests and attaches
`keycloak-breached-passwords-<version>.jar` and its `.sha256` to a GitHub
Release. In prod, copy the jar into a custom Keycloak image and run
`kc.sh build`. Leave `base-url` unset there so it uses the real API.

A Keycloak upgrade doesn't usually need a new jar. Bump the Keycloak version
(see below) in a PR; if the tests pass with no code changes, the existing jar
still works.

## Known constraints

- `PasswordPolicyProvider` is on Keycloak's internal SPI
  (`keycloak-server-spi-private`). Keycloak logs a startup warning for it,
  and upgrades may change the interface.
- The Keycloak version is set in two places: `keycloak.version` in `pom.xml`
  and the image tag in `docker-compose.yml`. The WireMock image tag also
  appears in both `docker-compose.yml` and `TestEnvironment`.
- The HIBP request uses Keycloak's shared HTTP client, which keeps Apache
  HttpClient's automatic retries. A connection reset can resend a request up
  to 3 more times within one of our attempts. Timeouts and HTTP errors
  aren't resent.
- There's no overall deadline. The worst case is usually about 5.25 s, but a
  slow-dripping server or a slow DNS lookup can take longer.
