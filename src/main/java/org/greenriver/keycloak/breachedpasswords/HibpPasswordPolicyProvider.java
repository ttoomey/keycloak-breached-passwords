package org.greenriver.keycloak.breachedpasswords;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PolicyError;

/** Rejects passwords that appear in the HIBP Pwned Passwords corpus. */
final class HibpPasswordPolicyProvider implements PasswordPolicyProvider {

  static final String ERROR_MESSAGE = "invalidPasswordBreachedMessage";

  private final KeycloakSession session;
  private final BreachChecker checker;

  HibpPasswordPolicyProvider(KeycloakSession session, BreachChecker checker) {
    this.session = session;
    this.checker = checker;
  }

  @Override
  public PolicyError validate(RealmModel realm, UserModel user, String password) {
    return check(password, realm);
  }

  @Override
  public PolicyError validate(String username, String password) {
    return check(password, session.getContext().getRealm());
  }

  private PolicyError check(String password, RealmModel realm) {
    String realmName = realm == null ? null : realm.getName();
    return checker.isBreached(password, realmName) ? new PolicyError(ERROR_MESSAGE) : null;
  }

  @Override
  public Object parseConfig(String value) {
    return null;
  }

  @Override
  public void close() {}
}
