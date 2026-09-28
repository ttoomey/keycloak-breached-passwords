package org.greenriver.keycloak.breachedpasswords;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PasswordPolicyProviderFactory;

/**
 * Registers the {@code hibp} password policy. The HIBP base URL is server config ({@code
 * KC_SPI_PASSWORD_POLICY__HIBP__BASE_URL}), not realm config, so realm admins can't point it
 * elsewhere.
 */
public final class HibpPasswordPolicyProviderFactory implements PasswordPolicyProviderFactory {

  static final String ID = "hibp";
  static final String BASE_URL_KEY = "base-url";
  static final String DEFAULT_BASE_URL = "https://api.pwnedpasswords.com";

  private String baseUrl;

  @Override
  public PasswordPolicyProvider create(KeycloakSession session) {
    return new HibpPasswordPolicyProvider(
        session, new BreachChecker(new KeycloakRangeClient(session, baseUrl), Thread::sleep));
  }

  @Override
  public void init(Config.Scope config) {
    // Throws on an invalid URL, which stops Keycloak from starting.
    baseUrl = BaseUrl.parse(config.get(BASE_URL_KEY, DEFAULT_BASE_URL));
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayName() {
    return "Not Breached (Have I Been Pwned)";
  }

  @Override
  public String getConfigType() {
    return null;
  }

  @Override
  public String getDefaultConfigValue() {
    return null;
  }

  @Override
  public boolean isMultiplSupported() {
    return false;
  }
}
