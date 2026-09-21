package com.housedevinci.einvoice.domain;

/**
 * Normalises a validation rule id that came from somebody else's code, without ever refusing it
 * (D9-02).
 *
 * <p>A rule id is a label on a disposition, not a key: it is read by an operator deciding which
 * number to void. It arrives from a host-supplied {@code DocumentValidator} - always somebody
 * else's code in 0.1.0 - which may report {@code "BR-DE-15 (fatal)"}, a sentence, or a hundred
 * characters. Validating it with the identifier charset would be correct for a key and wrong here:
 * the one call site is inside the transaction that records a <em>consumed</em> legal number's burn,
 * and a refusal there throws out of the pipeline and strands the number on a row that is due for
 * ever. Fail closed means the number gets its disposition, so this never throws.
 *
 * <p>What it does instead: every character outside the identifier charset becomes {@code _}, runs
 * of {@code _} collapse, and the result is cut to {@link #MAX_CHARS}. Cutting is safe here in a way
 * it was not for an operator's reason (D9-01): the value is machine-produced, the full text is in
 * the validator's own findings list, and the alternative is losing the disposition entirely.
 */
public final class RuleIds {

  /** The column's width, and the chain's field bound. */
  public static final int MAX_CHARS = 64;

  private RuleIds() {}

  public static String normalise(String ruleId) {
    if (ruleId == null || ruleId.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder(Math.min(ruleId.length(), MAX_CHARS));
    boolean lastWasFiller = false;
    for (int i = 0; i < ruleId.length() && out.length() < MAX_CHARS; i++) {
      char c = ruleId.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_'
              || c == '.'
              || c == ':'
              || c == '+';
      if (ok) {
        out.append(c);
        lastWasFiller = false;
      } else if (!lastWasFiller) {
        out.append('_');
        lastWasFiller = true;
      }
    }
    while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }
}
