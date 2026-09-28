package org.greenriver.keycloak.breachedpasswords;

/** Seam for the backoff delay, so tests can record it instead of waiting. */
@FunctionalInterface
interface Sleeper {

  void sleep(long millis) throws InterruptedException;
}
