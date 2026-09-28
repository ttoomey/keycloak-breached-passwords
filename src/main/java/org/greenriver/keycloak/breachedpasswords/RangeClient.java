package org.greenriver.keycloak.breachedpasswords;

import java.io.IOException;

/** Fetches one page of the HIBP range API: {@code GET {base-url}/range/{prefix}}. */
interface RangeClient {

  RangeResponse fetch(String prefix) throws IOException;

  record RangeResponse(int status, String body) {}
}
