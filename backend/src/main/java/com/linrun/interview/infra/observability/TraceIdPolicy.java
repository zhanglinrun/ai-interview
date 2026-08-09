package com.linrun.interview.infra.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/** Server-side validation and generation policy for transport trace IDs. */
public final class TraceIdPolicy {

  public static final int MAX_LENGTH = 64;
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private TraceIdPolicy() {
  }

  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  public static String acceptOrCreate(String requested) {
    return isValid(requested) ? requested : generate();
  }

  public static String generate() {
    return UUID.randomUUID().toString();
  }
}
