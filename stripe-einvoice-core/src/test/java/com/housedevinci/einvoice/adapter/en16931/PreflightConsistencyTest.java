package com.housedevinci.einvoice.adapter.en16931;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * The two passes, compared - and the shortcut that lets the preflight skip the writer, verified.
 *
 * <p>Probe 3 below is a <b>consistency check, not a screen detector</b>. Both passes run one body,
 * so a screen weakened in the mapper is weakened for both and they go on agreeing; the test that
 * catches a missing screen is the reflection walk over the upstream payload in {@code
 * CipherProbePreflightTest}. This one catches the other failure: a preflight that stopped running
 * the render path at all.
 */
class PreflightConsistencyTest {

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
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "de-hostile-buyer-name",
            hostileBuyerName(DocumentFixtures.germanStandardRated()),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "fr-unknown-currency",
            withCurrency(DocumentFixtures.frenchStandardRated(), "xxx"),
            DocumentFixtures.frenchSeller(),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "fr-no-buyer-country",
            withBuyerCountry(DocumentFixtures.frenchStandardRated(), ""),
            DocumentFixtures.frenchSeller(),
            UblProfile.PEPPOL_BIS_UBL));
  }

  @Test
  void preflight_and_render_agree_on_every_fixture() {
    List<String> disagreements = new ArrayList<>();
    for (Fixture fixture : fixtures()) {
      En16931DocumentRenderer renderer =
          new En16931DocumentRenderer(fixture.seller(), fixture.profile(), null);
      PreflightReport report = renderer.preflight(fixture.input().unnumbered());
      String renderCode = renderFailureCode(renderer, fixture.input());
      boolean passed = report.verdict() == PreflightReport.Verdict.PASSED;
      if (passed != (renderCode == null)) {
        disagreements.add(
            fixture.name() + ": preflight " + report.verdict() + ", render " + renderCode);
      } else if (!passed && !report.code().equals(renderCode)) {
        disagreements.add(
            fixture.name() + ": preflight refused " + report.code() + ", render " + renderCode);
      }
    }
    assertThat(disagreements)
        .as("the pass that spends the number and the pass that writes the bytes reach one verdict")
        .isEmpty();
  }

  /**
   * Probe 8. The preflight decides the same thing in any zone and any locale, because it reads no
   * clock, no default zone and no default locale - the issue date arrives already derived.
   */
  @Test
  void the_verdict_is_the_same_under_two_zones_and_two_locales() {
    TimeZone zone = TimeZone.getDefault();
    Locale locale = Locale.getDefault();
    try {
      List<String> first = verdicts();
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
      Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
      assertThat(verdicts()).isEqualTo(first);
    } finally {
      TimeZone.setDefault(zone);
      Locale.setDefault(locale);
    }
  }

  private static List<String> verdicts() {
    List<String> verdicts = new ArrayList<>();
    for (Fixture fixture : fixtures()) {
      PreflightReport report =
          new En16931DocumentRenderer(fixture.seller(), fixture.profile(), null)
              .preflight(fixture.input().unnumbered());
      verdicts.add(fixture.name() + "=" + report.verdict() + ":" + report.code());
    }
    return verdicts;
  }

  /**
   * Probe 4. The preflight deliberately does not run the writer, on the grounds that every string
   * reaching the writer arrived through a screened business term - so a writer refusal implies a
   * mapper refusal. That is an invariant, and this asserts it rather than restating it: whenever
   * the mapping accepts a generated string, the writer accepts the document it produced.
   *
   * <p>Seeded and printed, so a failure is reproducible.
   */
  @Test
  void probe_a_writer_refusal_implies_a_mapper_refusal() {
    long seed = 20_260_921L;
    System.out.println("probe_a_writer_refusal_implies_a_mapper_refusal seed=" + seed);
    Random random = new Random(seed);
    En16931DocumentRenderer renderer =
        new En16931DocumentRenderer(
            DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null);
    List<String> leaks = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      String generated = generate(random);
      DocumentInput input = hostileBuyerName(DocumentFixtures.germanStandardRated(), generated);
      if (renderer.preflight(input.unnumbered()).verdict() != PreflightReport.Verdict.PASSED) {
        continue;
      }
      String code = renderFailureCode(renderer, input);
      if (code != null) {
        leaks.add("the mapper accepted a value the writer then refused (" + code + ")");
      }
    }
    assertThat(leaks)
        .as("nothing the writer refuses gets past the mapper, which is why preflight skips it")
        .isEmpty();
  }

  /** Characters chosen to sit exactly where an XML writer decides: markup, control, surrogate. */
  private static String generate(Random random) {
    char[] alphabet =
        new char[] {
          'a',
          'Z',
          '0',
          ' ',
          '&',
          '<',
          '>',
          '"',
          '\'',
          '\n',
          '\t',
          '\r',
          (char) 0x00,
          (char) 0x07,
          (char) 0x0b,
          (char) 0x1f,
          (char) 0x85,
          (char) 0xa0,
          (char) 0x200b,
          (char) 0x2028,
          (char) 0xd800,
          (char) 0xdfff,
          (char) 0xfffe,
          (char) 0xffff,
          (char) 0xfeff,
          'e',
          'u'
        };
    int length = 1 + random.nextInt(24);
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet[random.nextInt(alphabet.length)]);
    }
    return out.toString();
  }

  /** The code the render refuses with, or {@code null} when it produced bytes. */
  private static String renderFailureCode(En16931DocumentRenderer renderer, DocumentInput input) {
    try {
      renderer.render(input);
      return null;
    } catch (EInvoiceException refusal) {
      return refusal.code();
    }
  }

  private static DocumentInput hostileBuyerName(DocumentInput base) {
    return hostileBuyerName(base, "Elbe" + '￿' + "AG");
  }

  private static DocumentInput hostileBuyerName(DocumentInput base, String name) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            name,
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            b.city(),
            b.country(),
            b.taxId()));
  }

  private static DocumentInput withBuyerCountry(DocumentInput base, String country) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(), b.email(), b.line1(), b.line2(), b.postalCode(), b.city(), country, null));
  }

  private static DocumentInput withBuyer(DocumentInput base, SourceInvoice.SourceParty buyer) {
    SourceInvoice i = base.invoice();
    return reinput(
        base,
        new SourceInvoice(
            i.id(),
            i.number(),
            i.accountId(),
            i.livemode(),
            i.currency(),
            i.status(),
            i.finalizedAt(),
            buyer,
            i.lines(),
            i.taxBuckets(),
            i.taxTreatments(),
            i.subtotalMinor(),
            i.taxMinor(),
            i.totalMinor(),
            true));
  }

  private static DocumentInput withCurrency(DocumentInput base, String currency) {
    SourceInvoice i = base.invoice();
    return reinput(
        base,
        new SourceInvoice(
            i.id(),
            i.number(),
            i.accountId(),
            i.livemode(),
            currency,
            i.status(),
            i.finalizedAt(),
            i.buyer(),
            i.lines(),
            i.taxBuckets(),
            i.taxTreatments(),
            i.subtotalMinor(),
            i.taxMinor(),
            i.totalMinor(),
            true));
  }

  private static DocumentInput reinput(DocumentInput base, SourceInvoice invoice) {
    return new DocumentInput(
        new com.housedevinci.einvoice.application.MappingInput(
            invoice, base.seriesKey(), base.issueDate(), base.rulePackVersion()),
        base.legalNumber());
  }
}
