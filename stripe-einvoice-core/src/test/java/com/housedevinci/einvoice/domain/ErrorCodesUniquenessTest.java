package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T15 (D-ORPE-1, D-ORPE-8): one stable code, one meaning. A code is what a host alerts on, so two
 * meanings for one code break every alert built on it.
 */
class ErrorCodesUniquenessTest {

  /** Every public constant of {@link ErrorCodes}: name to value. */
  static Map<String, String> freeCodes() {
    Map<String, String> codes = new HashMap<>();
    for (Field field : ErrorCodes.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers)
          && Modifier.isPublic(modifiers)
          && field.getType() == String.class) {
        try {
          codes.put(field.getName(), (String) field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
    return codes;
  }

  static List<String> violations(Map<String, String> codes, Map<String, String> reserved) {
    List<String> found = new ArrayList<>();
    Map<String, String> byValue = new HashMap<>();
    codes.forEach(
        (name, value) -> {
          String other = byValue.putIfAbsent(value, name);
          if (other != null) {
            found.add(value + " is both " + other + " and " + name);
          }
          String reservedName = reserved.get(value);
          if (reservedName != null && !reservedName.equals(name)) {
            found.add(value + " is reserved for " + reservedName + " and taken by " + name);
          }
        });
    return found;
  }

  @Test
  void error_codes_are_unique_across_the_free_and_pro_tables() {
    assertThat(violations(freeCodes(), ReservedErrorCodes.RESERVED)).isEmpty();
    assertThat(freeCodes()).containsEntry("INBOUND_RUN_IN_PROGRESS", "DEI-217");
  }

  /** D-ORPE-8: the original mistake, a free DEI-214 for the busy answer, is caught. */
  @Test
  void probe_a_free_code_reusing_a_reserved_pro_code_passes_the_uniqueness_test() {
    Map<String, String> mistaken = new HashMap<>(freeCodes());
    mistaken.put("INBOUND_RUN_IN_PROGRESS", "DEI-214");

    assertThat(violations(mistaken, ReservedErrorCodes.RESERVED))
        .containsExactly(
            "DEI-214 is reserved for EVENT_AWAITING_KIND and taken by"
                + " INBOUND_RUN_IN_PROGRESS");
  }

  @Test
  void a_duplicate_value_inside_the_free_table_is_caught() {
    Map<String, String> duplicated = new HashMap<>(freeCodes());
    duplicated.put("SECOND_MEANING", ErrorCodes.STORE_UNAVAILABLE);

    assertThat(violations(duplicated, ReservedErrorCodes.RESERVED)).hasSize(1);
  }
}
