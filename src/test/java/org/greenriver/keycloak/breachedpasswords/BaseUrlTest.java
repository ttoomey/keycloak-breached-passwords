package org.greenriver.keycloak.breachedpasswords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaseUrlTest {

  @ParameterizedTest
  @ValueSource(strings = {"https://h", "https://h/", "https://h//"})
  void stripsTrailingSlashes(String value) {
    assertEquals("https://h", BaseUrl.parse(value));
  }

  @Test
  void keepsPortAndPath() {
    assertEquals("http://hibp:8080/api", BaseUrl.parse("http://hibp:8080/api/"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        "api.pwnedpasswords.com",
        "/range",
        "ftp://h",
        "file:///etc/passwd",
        "https:///path",
        "https://",
        "not a uri",
        "https://h/?x=1",
        "https://h?",
        "https://h/#f",
        "https://h#",
      })
  void rejectsInvalidValues(String value) {
    assertThrows(IllegalArgumentException.class, () -> BaseUrl.parse(value));
  }
}
