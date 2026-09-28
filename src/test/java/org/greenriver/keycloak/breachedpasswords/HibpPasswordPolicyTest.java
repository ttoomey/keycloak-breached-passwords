package org.greenriver.keycloak.breachedpasswords;

import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.REALM;
import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.hibpUrl;
import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.keycloak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Drives the login theme's Update Password form, which is what the account console and the
 * forced change at login both use.
 */
class HibpPasswordPolicyTest {

  static final String BREACHED_MESSAGE =
      "This password isn't safe to use. It was found in a publicly available list of leaked"
          + " passwords.";

  // Above the ~5.25 s fail-open worst case.
  static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

  static final ObjectMapper json = new ObjectMapper();

  String username;
  String temporaryPassword;

  @BeforeAll
  static void start() {
    TestEnvironment.start();
  }

  @BeforeEach
  void createUser() throws Exception {
    username = "user-" + UUID.randomUUID() + "@example.com";
    temporaryPassword = "temporary-" + UUID.randomUUID();

    CredentialRepresentation credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(temporaryPassword);
    credential.setTemporary(true);

    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEmail(username);
    user.setEmailVerified(true);
    user.setFirstName("Test");
    user.setLastName("User");
    user.setEnabled(true);
    user.setCredentials(List.of(credential));

    try (Response response = keycloak.getKeycloakAdminClient().realm(REALM).users().create(user)) {
      assertEquals(201, response.getStatus());
    }

    // Setting the temporary password also called HIBP.
    wiremock("DELETE", "/__admin/requests", null);
  }

  @Test
  void breachedPasswordIsRejected() throws Exception {
    Browser browser = new Browser();
    Document form = browser.signIn(username, temporaryPassword);

    HttpResponse<String> response = browser.updatePassword(form, "correct-horse-battery-staple");

    assertEquals(200, response.statusCode());
    Document page = Jsoup.parse(response.body());
    assertNotNull(updatePasswordForm(page), "expected the Update Password form again");
    assertTrue(page.text().contains(BREACHED_MESSAGE), page::text);

    // The password wasn't changed, so the temporary one still signs in.
    assertNotNull(updatePasswordForm(new Browser().signIn(username, temporaryPassword)));
  }

  @Test
  void cleanPasswordIsAccepted() throws Exception {
    assertAccepted(randomPassword());
  }

  @Test
  void outageFailsOpen() throws Exception {
    assertAccepted("hibp-outage-simulation-password");

    assertEquals(3, requestCount("/range/F8CBE", Map.of()));
  }

  @Test
  void timeoutFailsOpen() throws Exception {
    assertAccepted("hibp-timeout-simulation-password");

    assertEquals(3, requestCount("/range/C02ED", Map.of()));
  }

  @Test
  void requestsPaddingAndIdentifiesItself() throws Exception {
    String password = randomPassword();
    String path = "/range/" + BreachChecker.Hash.of(password).prefix();

    assertAccepted(password);

    assertEquals(1, requestCount(path, Map.of()));
    assertEquals(
        1,
        requestCount(
            path,
            Map.of("Add-Padding", "true", "User-Agent", "keycloak-breached-passwords/1.0")));
  }

  private void assertAccepted(String newPassword) throws Exception {
    Browser browser = new Browser();
    Document form = browser.signIn(username, temporaryPassword);

    HttpResponse<String> response = browser.updatePassword(form, newPassword);

    assertEquals(302, response.statusCode(), response::body);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertTrue(location.startsWith(Browser.redirectUri()), location);
    assertTrue(location.contains("code="), location);
  }

  private static String randomPassword() {
    return "clean-" + UUID.randomUUID();
  }

  private static Element updatePasswordForm(Document page) {
    Element input = page.selectFirst("form input[name=password-new]");
    return input == null ? null : input.closest("form");
  }

  private static int requestCount(String url, Map<String, String> headers) throws Exception {
    ObjectNode pattern = json.createObjectNode().put("method", "GET").put("url", url);
    if (!headers.isEmpty()) {
      ObjectNode matchers = pattern.putObject("headers");
      headers.forEach((name, value) -> matchers.putObject(name).put("equalTo", value));
    }
    String body = wiremock("POST", "/__admin/requests/count", json.writeValueAsString(pattern));
    return json.readTree(body).get("count").asInt();
  }

  private static String wiremock(String method, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(hibpUrl() + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response::body);
    return response.body();
  }

  /** A cookie-keeping client that walks the login pages without following redirects. */
  static final class Browser {

    private final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    static String redirectUri() {
      return keycloak.getAuthServerUrl() + "/realms/" + REALM + "/account/";
    }

    /** Signs in and returns the Update Password page that the temporary password forces. */
    Document signIn(String username, String password) throws Exception {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("client_id", "account-console");
      query.put("redirect_uri", redirectUri());
      query.put("response_type", "code");
      query.put("scope", "openid");
      query.put("state", UUID.randomUUID().toString());
      // account-console requires PKCE. The code is never exchanged, so any valid challenge works.
      query.put("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
      query.put("code_challenge_method", "S256");
      String authUrl =
          keycloak.getAuthServerUrl()
              + "/realms/"
              + REALM
              + "/protocol/openid-connect/auth?"
              + encode(query);

      HttpResponse<String> loginPage = send(HttpRequest.newBuilder(URI.create(authUrl)).GET());
      assertEquals(200, loginPage.statusCode(), loginPage::body);
      Element loginForm = Jsoup.parse(loginPage.body()).selectFirst("form#kc-form-login");
      assertNotNull(loginForm, loginPage::body);

      HttpResponse<String> afterLogin =
          post(loginForm.attr("action"), Map.of("username", username, "password", password));
      // Keycloak redirects to the required-action page.
      while (afterLogin.statusCode() == 302) {
        URI next =
            afterLogin.uri().resolve(afterLogin.headers().firstValue("Location").orElseThrow());
        afterLogin = send(HttpRequest.newBuilder(next).GET());
      }
      assertEquals(200, afterLogin.statusCode(), afterLogin::body);
      Document page = Jsoup.parse(afterLogin.body());
      assertNotNull(updatePasswordForm(page), afterLogin::body);
      return page;
    }

    HttpResponse<String> updatePassword(Document page, String newPassword) throws Exception {
      Element form = updatePasswordForm(page);
      return post(
          form.attr("action"),
          Map.of("password-new", newPassword, "password-confirm", newPassword));
    }

    private HttpResponse<String> post(String url, Map<String, String> fields) throws Exception {
      return send(
          HttpRequest.newBuilder(URI.create(url))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(encode(fields))));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
      return http.send(request.timeout(HTTP_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(Map<String, String> fields) {
      return fields.entrySet().stream()
          .map(
              e ->
                  URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                      + "="
                      + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
          .collect(Collectors.joining("&"));
    }
  }
}
