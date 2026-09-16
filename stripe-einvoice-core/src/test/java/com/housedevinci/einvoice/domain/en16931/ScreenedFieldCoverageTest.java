package com.housedevinci.einvoice.domain.en16931;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.EInvoiceException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checklist line 9: the test that catches the field somebody adds next.
 *
 * <p>Screening every value is a property of the model, not of the writer, and a property that is
 * held by everybody remembering to call one function is not held. This walks every {@code String}
 * component of every record in the EN 16931 model by reflection, hands that component a value with
 * a C0 control character in it, and requires the record's own constructor to refuse. A new field
 * that reaches the bytes without going through {@code ScreenedText} turns this red on the commit
 * that adds it, rather than on an invoice a year later.
 *
 * <p>Components that are not free text are listed by name, each with the reason it is exempt - a
 * code list value, a scheme identifier, an ISO country - and every one of those has its own
 * validation with its own test. The exemption list is short on purpose: a long one would mean the
 * test had been argued down rather than satisfied.
 */
class ScreenedFieldCoverageTest {

  /** A C0 control: survives schema validation, fails a business rule at the recipient. */
  private static final String HOSTILE = "Elbe" + (char) 0x07 + "AG";

  /**
   * Components validated by something other than the free-text screener, with the reason. Each one
   * is a closed value set or a checksummed identifier, and each has its own test elsewhere.
   */
  private static final List<String> VALIDATED_OTHERWISE =
      List.of(
          // ISO 3166-1 alpha-2, checked against the JDK's own list (CountryCodeTest).
          "PostalAddress.country",
          // ISO 4217, checked against the explicit exponent table (MoneyTest).
          "Money.currency",
          "EnInvoice.currency",
          // UNTDID 4461, digits only, checked in PaymentInstruction.
          "PaymentInstruction.meansCode",
          // An IBAN with its mod-97 check, or an account identifier under another means code.
          "PaymentInstruction.accountIdentifier",
          // An ISO 6523 or EAS code, checked in PartyIdentifier.
          "PartyIdentifier.scheme",
          // A VAT identifier with its per-country shape and, where one exists, its check digit.
          "VatIdentifier.value");

  private static final List<Class<?>> MODEL =
      List.of(
          Contact.class,
          DocumentLine.class,
          EnInvoice.class,
          Money.class,
          Party.class,
          PartyIdentifier.class,
          PaymentInstruction.class,
          PostalAddress.class,
          TaxSubtotal.class,
          VatIdentifier.class);

  @Test
  void every_string_component_of_the_model_refuses_a_control_character() {
    List<String> unscreened = new ArrayList<>();
    for (Class<?> type : MODEL) {
      RecordComponent[] components = type.getRecordComponents();
      assertThat(components).as("%s is a record", type.getSimpleName()).isNotNull();
      for (int i = 0; i < components.length; i++) {
        if (components[i].getType() != String.class) {
          continue;
        }
        String name = type.getSimpleName() + "." + components[i].getName();
        if (VALIDATED_OTHERWISE.contains(name)) {
          continue;
        }
        if (!refusesHostileValue(type, i)) {
          unscreened.add(name);
        }
      }
    }
    assertThat(unscreened)
        .as(
            "every free-text component either screens its value or is listed, by name and with a"
                + " reason, as validated another way")
        .isEmpty();
  }

  @Test
  void the_exemption_list_names_only_components_that_still_exist() {
    // An exemption that outlives its field is an exemption nobody will notice has stopped
    // applying, which is how a list like this rots into a blanket.
    List<String> known = new ArrayList<>();
    for (Class<?> type : MODEL) {
      for (RecordComponent component : type.getRecordComponents()) {
        known.add(type.getSimpleName() + "." + component.getName());
      }
    }
    assertThat(known).containsAll(VALIDATED_OTHERWISE);
  }

  /** Builds the record with a hostile value in one component and every other one plausible. */
  private static boolean refusesHostileValue(Class<?> type, int hostileIndex) {
    Constructor<?> constructor = canonicalConstructor(type);
    Object[] arguments = new Object[constructor.getParameterCount()];
    Parameter[] parameters = constructor.getParameters();
    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = i == hostileIndex ? HOSTILE : plausible(parameters[i].getType());
    }
    constructor.setAccessible(true);
    try {
      constructor.newInstance(arguments);
      return false;
    } catch (InvocationTargetException thrown) {
      // Only a refusal that names the hostile component counts. Accepting "something threw" would
      // let this test pass because a neighbouring component was null, which is the defect
      // checklist line 59 is about: a probe green under a stronger-sounding name than it earns.
      Throwable cause = thrown.getCause();
      if (!(cause instanceof EInvoiceException typed)) {
        throw new IllegalStateException(
            "building " + type.getSimpleName() + " threw something other than a typed refusal",
            cause);
      }
      String message = typed.getMessage() == null ? "" : typed.getMessage();
      if (message.contains("must not be null") || message.contains("is required")) {
        throw new IllegalStateException(
            "building "
                + type.getSimpleName()
                + " failed on a neighbouring component rather than on the hostile one, so this"
                + " test would have passed without screening anything: "
                + message);
      }
      return true;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not build " + type.getSimpleName(), e);
    }
  }

  private static Constructor<?> canonicalConstructor(Class<?> type) {
    RecordComponent[] components = type.getRecordComponents();
    Class<?>[] parameterTypes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      parameterTypes[i] = components[i].getType();
    }
    try {
      return type.getDeclaredConstructor(parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(type.getSimpleName() + " has no canonical constructor", e);
    }
  }

  /**
   * A value every record will accept, so that the only thing a constructor can object to is the
   * hostile component. A {@code null} here would make the test green for the wrong reason.
   */
  private static Object plausible(Class<?> type) {
    if (type == String.class) {
      return "Plausible";
    }
    if (type == BigDecimal.class) {
      return BigDecimal.ONE;
    }
    if (type == boolean.class) {
      return Boolean.FALSE;
    }
    if (type == LocalDate.class) {
      return LocalDate.of(2026, 4, 1);
    }
    if (type == List.class) {
      return List.of();
    }
    if (type == PostalAddress.class) {
      return new PostalAddress("Hafenweg 44", null, "Bremen", "28217", null, "DE");
    }
    if (type == Money.class) {
      return Money.ofMinor(10_000, "EUR");
    }
    if (type == Contact.class) {
      return new Contact("Buchhaltung", "+49 40 5550100", "rechnungen@example.invalid");
    }
    if (type == PartyIdentifier.class) {
      return new PartyIdentifier("9930", "DE123456789");
    }
    if (type == VatIdentifier.class) {
      return VatIdentifier.parse("a VAT identifier", "DE123456789");
    }
    if (type == Party.class) {
      return new Party(
          "Elbe Maschinenbau AG",
          null,
          new PostalAddress("Hafenweg 44", null, "Bremen", "28217", null, "DE"),
          null,
          null,
          null,
          null,
          null);
    }
    if (type == PaymentInstruction.class) {
      return new PaymentInstruction("58", "DE02120300000000202051", null, null);
    }
    if (type == TaxCategory.class) {
      return TaxCategory.STANDARD;
    }
    if (type == DocumentTypeCode.class) {
      return DocumentTypeCode.COMMERCIAL_INVOICE;
    }
    if (type == VatexCode.class) {
      return null;
    }
    if (type == com.housedevinci.einvoice.domain.Percentage.class) {
      return com.housedevinci.einvoice.domain.Percentage.of("19");
    }
    throw new IllegalStateException(
        "no plausible value is registered for "
            + type.getName()
            + ", so a component of that type"
            + " would be handed null and this test would pass without screening anything");
  }
}
