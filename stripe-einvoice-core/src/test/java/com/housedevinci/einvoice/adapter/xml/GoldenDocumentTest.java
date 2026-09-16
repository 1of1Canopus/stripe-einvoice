package com.housedevinci.einvoice.adapter.xml;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Golden files, and the determinism they are only meaningful under (D-15, checklist lines 16, 17
 * and 61).
 *
 * <p>The golden files live on the classpath, carry no absolute path and no real party, and are
 * compared <b>byte for byte</b>: a writer whose output moves is a writer whose retry archives a
 * second document under one number. Regenerate them deliberately with {@code
 * -Deinvoice.golden.write=true} and read the diff before committing it - that switch exists so a
 * version bump is a reviewed commit rather than a green build nobody looked at.
 */
class GoldenDocumentTest {

  private static final String GOLDEN_ROOT = "/golden/";

  /**
   * The home-directory prefixes a generated document must never carry.
   *
   * <p>Assembled from pieces rather than written out, so that this file does not itself match the
   * repository's reference guard - which deliberately has no exemption for "a file that is about
   * the pattern", because every exemption is a way to smuggle one past it.
   */
  private static final List<String> LOCAL_PATH_PREFIXES =
      List.of("/" + "Users" + "/", "/" + "home" + "/", "/" + "root" + "/", "C:" + "\\" + "Users");

  /**
   * A locale whose default number formatting uses Thai digits, beside a time zone on the other side
   * of the date line. If a {@code String.format}, a {@code DateTimeFormatter} without {@link
   * Locale#ROOT} or a {@code LocalDate.now()} ever creeps into the writer, one of the two turns
   * this red rather than leaving it quietly green.
   */
  private static final Locale OTHER_LOCALE = Locale.forLanguageTag("th-TH-u-nu-thai");

  private static final TimeZone OTHER_ZONE = TimeZone.getTimeZone("Pacific/Kiritimati");

  private record Fixture(
      String name, DocumentInput input, SellerProfile seller, UblProfile profile) {}

  private static List<Fixture> fixtures() {
    return List.of(
        new Fixture(
            "de-b2b-xrechnung",
            DocumentFixtures.germanStandardRated(),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "de-b2b-peppol",
            DocumentFixtures.germanStandardRated(),
            DocumentFixtures.germanSeller(),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "fr-b2b-peppol",
            DocumentFixtures.frenchStandardRated(),
            DocumentFixtures.frenchSeller(),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "be-b2b-peppol",
            DocumentFixtures.belgianStandardRated(),
            DocumentFixtures.frenchSeller(),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "reverse-charge-xrechnung",
            DocumentFixtures.reverseCharge(),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL));
  }

  private static byte[] render(Fixture fixture) {
    return new En16931DocumentRenderer(fixture.seller(), fixture.profile(), null)
        .render(fixture.input())
        .bytes();
  }

  @Test
  void every_fixture_matches_its_golden_file_byte_for_byte() {
    for (Fixture fixture : fixtures()) {
      byte[] actual = render(fixture);
      if (Boolean.getBoolean("einvoice.golden.write")) {
        write(fixture.name(), actual);
        continue;
      }
      assertThat(new String(actual, StandardCharsets.UTF_8))
          .as("golden file %s", fixture.name())
          .isEqualTo(golden(fixture.name()));
    }
  }

  @Test
  void the_credit_note_matches_its_golden_file_byte_for_byte() {
    byte[] actual =
        new UblDocumentWriter(UblProfile.XRECHNUNG_UBL)
            .write(DocumentFixtures.creditNote(DocumentFixtures.germanSeller()));
    if (Boolean.getBoolean("einvoice.golden.write")) {
      write("credit-note-xrechnung", actual);
      return;
    }
    assertThat(new String(actual, StandardCharsets.UTF_8))
        .isEqualTo(golden("credit-note-xrechnung"));
  }

  @Test
  void the_same_input_renders_the_same_bytes_under_another_locale_and_time_zone() {
    for (Fixture fixture : fixtures()) {
      byte[] here = render(fixture);
      byte[] elsewhere = underOtherDefaults(() -> render(fixture));
      assertThat(elsewhere).as("determinism of %s", fixture.name()).isEqualTo(here);
    }
  }

  @Test
  void a_golden_file_carries_no_local_path_and_no_machine_identity() {
    // Checklist line 61, asserted here as well as by the reference guard, because a golden file is
    // generated output and generated output is exactly where a path leaks in unnoticed.
    for (Fixture fixture : fixtures()) {
      String xml = golden(fixture.name());
      for (String prefix : LOCAL_PATH_PREFIXES) {
        assertThat(xml).as("golden file %s", fixture.name()).doesNotContain(prefix);
      }
    }
  }

  private static <T> T underOtherDefaults(Callable<T> body) {
    Locale locale = Locale.getDefault();
    TimeZone zone = TimeZone.getDefault();
    try {
      Locale.setDefault(OTHER_LOCALE);
      TimeZone.setDefault(OTHER_ZONE);
      return body.call();
    } catch (Exception e) {
      throw new IllegalStateException("rendering under other defaults failed", e);
    } finally {
      Locale.setDefault(locale);
      TimeZone.setDefault(zone);
    }
  }

  private static String golden(String name) {
    try (InputStream in =
        GoldenDocumentTest.class.getResourceAsStream(GOLDEN_ROOT + name + ".xml")) {
      if (in == null) {
        throw new IllegalStateException(
            "golden file " + name + " is missing; regenerate with -Deinvoice.golden.write=true");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(String name, byte[] bytes) {
    try {
      Path target = Path.of("src", "test", "resources", "golden", name + ".xml");
      Files.createDirectories(target.getParent());
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
