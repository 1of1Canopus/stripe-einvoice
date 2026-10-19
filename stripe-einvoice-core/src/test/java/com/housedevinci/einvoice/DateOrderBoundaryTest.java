package com.housedevinci.einvoice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T-cause-no-caller and the code table (D-PS1-27 design 5.6, round 2 item 2).
 *
 * <p>Why a number came late is derived from stored history by one class, and no caller can pass it:
 * {@code AllocationRequest} has no component for it, and no class outside the domain and the
 * deriver names a cause.
 */
class DateOrderBoundaryTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.housedevinci.einvoice");

  @Test
  void an_allocation_request_carries_no_cause_and_no_issue_date() {
    for (RecordComponent component : AllocationRequest.class.getRecordComponents()) {
      assertThat(component.getType()).isNotEqualTo(DateGapCause.class);
      assertThat(component.getName().toLowerCase(Locale.ROOT))
          .doesNotContain("cause")
          .doesNotContain("issuedate")
          .doesNotContain("fiscalyear");
    }
  }

  @Test
  void only_the_cause_deriver_chooses_a_cause() {
    for (DateGapCause cause : DateGapCause.values()) {
      noClasses()
          .that()
          .resideOutsideOfPackage("com.housedevinci.einvoice.domain..")
          .and()
          .doNotHaveFullyQualifiedName("com.housedevinci.einvoice.adapter.jdbc.JdbcDateGapCauses")
          .should()
          .getField(DateGapCause.class, cause.name())
          .because("the cause of a late number comes from stored history only (design 5.6)")
          .check(CLASSES);
    }
  }

  @Test
  void the_application_layer_never_handles_a_cause() {
    noClasses()
        .that()
        .resideInAPackage("com.housedevinci.einvoice.application..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(DateGapCause.class.getName())
        .check(CLASSES);
  }

  @Test
  void every_error_code_is_unique_and_the_date_order_codes_exist() throws IllegalAccessException {
    List<String> codes = new ArrayList<>();
    for (Field field : ErrorCodes.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        codes.add((String) field.get(null));
      }
    }
    Set<String> unique = new HashSet<>(codes);
    assertThat(unique).hasSameSizeAs(codes);
    assertThat(codes).contains("DEI-130", "DEI-131", "DEI-132", "DEI-137", "DEI-280");
    // DEI-124 to DEI-126 and DEI-129 are reserved by the credit-note slice's code table.
    assertThat(codes).doesNotContain("DEI-124", "DEI-125", "DEI-126", "DEI-129");
  }
}
