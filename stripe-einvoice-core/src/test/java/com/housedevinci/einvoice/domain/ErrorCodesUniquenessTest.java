package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

  /** Code to reserved name, the shape {@link #violations} compares. */
  static Map<String, String> reservedNames() {
    Map<String, String> names = new HashMap<>();
    ReservedErrorCodes.RESERVED.forEach((code, reservation) -> names.put(code, reservation.name()));
    return names;
  }

  /**
   * Every way one code could carry two meanings, or one meaning two codes: a value twice in the
   * free table, a reserved code taken by another name, a reserved name defined under another code,
   * and one name reserved under two codes.
   */
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
    Map<String, String> codeByReservedName = new HashMap<>();
    new TreeMap<>(reserved)
        .forEach(
            (code, name) -> {
              String other = codeByReservedName.putIfAbsent(name, code);
              if (other != null) {
                found.add(name + " is reserved under both " + other + " and " + code);
              }
              String defined = codes.get(name);
              if (defined != null && !defined.equals(code)) {
                found.add(name + " is reserved as " + code + " and defined as " + defined);
              }
            });
    return found;
  }

  @Test
  void error_codes_are_unique_across_the_free_and_pro_tables() {
    assertThat(violations(freeCodes(), reservedNames())).isEmpty();
    assertThat(freeCodes()).containsEntry("INBOUND_RUN_IN_PROGRESS", "DEI-217");
    assertThat(freeCodes()).containsEntry("RUN_IN_HOST_TRANSACTION", "DEI-218");
  }

  /** D-PR49-1: the joined-run refusal's code cannot be taken by another name. */
  @Test
  void the_joined_run_refusal_code_is_reserved_under_its_own_name() {
    Map<String, String> mistaken = new HashMap<>(freeCodes());
    mistaken.put("SOME_LATER_CODE", "DEI-218");

    assertThat(reservedNames()).containsEntry("DEI-218", "RUN_IN_HOST_TRANSACTION");
    assertThat(violations(mistaken, reservedNames()))
        .contains("DEI-218 is reserved for RUN_IN_HOST_TRANSACTION and taken by SOME_LATER_CODE");
  }

  /** D-ORPE-8: the original mistake, a free DEI-214 for the busy answer, is caught. */
  @Test
  void probe_a_free_code_reusing_a_reserved_pro_code_passes_the_uniqueness_test() {
    Map<String, String> mistaken = new HashMap<>(freeCodes());
    mistaken.put("INBOUND_RUN_IN_PROGRESS", "DEI-214");

    assertThat(violations(mistaken, reservedNames()))
        .containsExactly(
            "DEI-214 is reserved for EVENT_AWAITING_KIND and taken by"
                + " INBOUND_RUN_IN_PROGRESS");
  }

  @Test
  void a_duplicate_value_inside_the_free_table_is_caught() {
    Map<String, String> duplicated = new HashMap<>(freeCodes());
    duplicated.put("SECOND_MEANING", ErrorCodes.STORE_UNAVAILABLE);

    assertThat(violations(duplicated, reservedNames())).hasSize(1);
  }

  /**
   * D-PR49-3: every code a reviewed design reserves is on the list under its design's name. The
   * archive marker design first reserved DEI-247, which PR 42 had given to the row lock release.
   */
  @Test
  void every_code_a_reviewed_design_reserves_is_on_the_list() {
    assertThat(reservedNames())
        .containsEntry("DEI-244", "RENDER_NOT_REPRODUCIBLE")
        .containsEntry("DEI-246", "ARCHIVE_UNMARKED")
        .containsEntry("DEI-232", "ARCHIVE_FOREIGN")
        .containsEntry("DEI-281", "RECON_OBJECT_FOR_DISPOSED_NUMBER")
        .containsEntry("DEI-266", "INBOUND_CEILING_REACHED")
        .containsEntry("DEI-282", "RECON_STUCK_INBOUND")
        .containsEntry("DEI-129", "NUMBER_IN_FLIGHT_WITHOUT_KIND")
        .containsEntry("DEI-630", "PRO_SCHEMA_VERSION_UNREADABLE")
        .doesNotContainKey("DEI-247");
    assertThat(ReservedErrorCodes.RESERVED.get("DEI-232").source()).contains("archive root marker");
    ReservedErrorCodes.RESERVED.forEach(
        (code, reservation) -> assertThat(reservation.source()).as(code).isNotBlank());
  }

  /** D-PR49-3: the marker design's first reservation of DEI-247 is caught. */
  @Test
  void probe_the_archive_marker_reserving_the_row_lock_code_passes_the_uniqueness_test() {
    Map<String, String> asFirstDesigned = new HashMap<>(reservedNames());
    asFirstDesigned.remove("DEI-232");
    asFirstDesigned.put("DEI-247", "ARCHIVE_FOREIGN");

    assertThat(violations(freeCodes(), asFirstDesigned))
        .containsExactly(
            "DEI-247 is reserved for ARCHIVE_FOREIGN and taken by ROW_LOCK_RELEASE_FAILED");
  }

  /** One meaning under two codes is a duplicate meaning too: built elsewhere, or reserved twice. */
  @Test
  void a_reserved_name_built_or_reserved_under_another_code_is_caught() {
    Map<String, String> builtElsewhere = new HashMap<>(freeCodes());
    builtElsewhere.put("ARCHIVE_UNMARKED", "DEI-253");
    Map<String, String> reservedTwice = new HashMap<>(reservedNames());
    reservedTwice.put("DEI-254", "ARCHIVE_UNMARKED");

    assertThat(violations(builtElsewhere, reservedNames()))
        .containsExactly("ARCHIVE_UNMARKED is reserved as DEI-246 and defined as DEI-253");
    assertThat(violations(freeCodes(), reservedTwice))
        .containsExactly("ARCHIVE_UNMARKED is reserved under both DEI-246 and DEI-254");
  }
}
