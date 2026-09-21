package com.housedevinci.einvoice.adapter.en16931;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.en16931.Party;
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
            UblProfile.PEPPOL_BIS_UBL),
        // D5-01. One profile-mandatory rule per fixture, each varying exactly ONE field: the
        // earlier `fr-no-buyer-country` cleared the tax id in the same call, so the mapper's
        // country refusal fired first and hid the fact that BT-49 was refused only by the writer.
        // A fixture that varies two fields cannot tell you which one the verdict came from.
        new Fixture(
            "peppol-buyer-without-a-tax-id (BT-49)",
            withBuyerTaxId(DocumentFixtures.frenchStandardRated(), null),
            DocumentFixtures.frenchSeller(),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "peppol-seller-without-an-electronic-address (BT-34)",
            DocumentFixtures.frenchStandardRated(),
            sellerWithoutElectronicAddress(DocumentFixtures.frenchSeller()),
            UblProfile.PEPPOL_BIS_UBL),
        new Fixture(
            "xrechnung-seller-without-a-buyer-reference (BR-DE-15)",
            DocumentFixtures.germanStandardRated(),
            DocumentFixtures.germanSellerWithoutBuyerReference(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "xrechnung-buyer-without-a-street (BR-DE-8)",
            withBuyerStreet(DocumentFixtures.germanStandardRated(), null),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "xrechnung-buyer-without-a-city (BR-DE-9)",
            withBuyerCity(DocumentFixtures.germanStandardRated(), null),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "xrechnung-buyer-without-a-post-code (BR-DE-10)",
            withBuyerPostCode(DocumentFixtures.germanStandardRated(), null),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "xrechnung-seller-without-payment-instructions (BR-DE-1)",
            DocumentFixtures.germanStandardRated(),
            sellerWithoutPayment(DocumentFixtures.germanSeller()),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "xrechnung-seller-without-a-contact (BR-DE-2)",
            DocumentFixtures.germanStandardRated(),
            sellerWithoutContact(DocumentFixtures.germanSeller()),
            UblProfile.XRECHNUNG_UBL),
        new Fixture(
            "reverse-charge-buyer-without-a-vat-identifier (BR-AE-*)",
            withBuyerTaxId(DocumentFixtures.reverseCharge(), null),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL));
  }

  private static SellerProfile sellerWithoutElectronicAddress(SellerProfile seller) {
    Party p = seller.party();
    return new SellerProfile(
        new Party(
            p.name(),
            p.tradingName(),
            p.address(),
            p.vatIdentifier(),
            p.taxRegistrationIdentifier(),
            p.legalIdentifier(),
            null,
            p.contact()),
        seller.payment(),
        seller.defaultBuyerReference());
  }

  private static SellerProfile sellerWithoutContact(SellerProfile seller) {
    Party p = seller.party();
    return new SellerProfile(
        new Party(
            p.name(),
            p.tradingName(),
            p.address(),
            p.vatIdentifier(),
            p.taxRegistrationIdentifier(),
            p.legalIdentifier(),
            p.electronicAddress(),
            null),
        seller.payment(),
        seller.defaultBuyerReference());
  }

  private static SellerProfile sellerWithoutPayment(SellerProfile seller) {
    return new SellerProfile(seller.party(), null, seller.defaultBuyerReference());
  }

  private static DocumentInput withBuyerTaxId(DocumentInput base, String taxId) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            b.city(),
            b.country(),
            taxId));
  }

  private static DocumentInput withBuyerStreet(DocumentInput base, String line1) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            line1,
            b.line2(),
            b.postalCode(),
            b.city(),
            b.country(),
            b.taxId()));
  }

  private static DocumentInput withBuyerCity(DocumentInput base, String city) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            city,
            b.country(),
            b.taxId()));
  }

  private static DocumentInput withBuyerPostCode(DocumentInput base, String postalCode) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            b.line2(),
            postalCode,
            b.city(),
            b.country(),
            b.taxId()));
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
   * D5-01, one assertion per profile-mandatory rule per profile: each of these is refused by the
   * <b>preflight</b>, before an allocator has run, rather than by the writer afterwards. The
   * agreement test above says the two passes match; this one says what they match on, because two
   * passes that both said PASSED would satisfy the first and lose a legal number to the second.
   */
  @Test
  void every_profile_mandatory_rule_refuses_before_the_number() {
    List<String> notRefused = new ArrayList<>();
    for (Fixture fixture : fixtures()) {
      if (!PROFILE_RULE_FIXTURES.contains(fixture.name())) {
        continue;
      }
      PreflightReport report =
          new En16931DocumentRenderer(fixture.seller(), fixture.profile(), null)
              .preflight(fixture.input().unnumbered());
      if (report.verdict() != PreflightReport.Verdict.REFUSED || !"DEI-221".equals(report.code())) {
        notRefused.add(fixture.name() + " -> " + report.verdict() + " " + report.code());
      }
    }
    assertThat(notRefused)
        .as("every profile-mandatory term is refused by the pass that runs before the allocator")
        .isEmpty();
  }

  /** The nine fixtures that exist to exercise one profile-mandatory rule each. */
  private static final List<String> PROFILE_RULE_FIXTURES =
      List.of(
          "peppol-buyer-without-a-tax-id (BT-49)",
          "peppol-seller-without-an-electronic-address (BT-34)",
          "xrechnung-seller-without-a-buyer-reference (BR-DE-15)",
          "xrechnung-buyer-without-a-street (BR-DE-8)",
          "xrechnung-buyer-without-a-city (BR-DE-9)",
          "xrechnung-buyer-without-a-post-code (BR-DE-10)",
          "xrechnung-seller-without-payment-instructions (BR-DE-1)",
          "xrechnung-seller-without-a-contact (BR-DE-2)",
          "reverse-charge-buyer-without-a-vat-identifier (BR-AE-*)");

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
   * Probe 4, widened by D5-01. The preflight does not run the writer, on the invariant that every
   * string reaching the writer arrived through a screened business term - so whenever the mapping
   * accepts a generated string, the writer accepts the document it produced.
   *
   * <p>It now varies <b>every screened string term</b> the upstream controls and runs under
   * <b>both</b> profiles, because the first version varied one field on one fixture under one
   * profile, and the thing it missed - a rule that is fatal under Peppol and harmless under
   * XRechnung - lives exactly in the gap between those.
   *
   * <p>Seeded and printed, so a failure is reproducible.
   */
  @Test
  void probe_a_writer_refusal_implies_a_mapper_refusal() {
    long seed = 20_260_921L;
    System.out.println("probe_a_writer_refusal_implies_a_mapper_refusal seed=" + seed);
    Random random = new Random(seed);
    List<String> leaks = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      String generated = generate(random);
      boolean german = random.nextBoolean();
      DocumentInput base =
          german ? DocumentFixtures.germanStandardRated() : DocumentFixtures.frenchStandardRated();
      SellerProfile seller =
          german ? DocumentFixtures.germanSeller() : DocumentFixtures.frenchSeller();
      UblProfile profile = german ? UblProfile.XRECHNUNG_UBL : UblProfile.PEPPOL_BIS_UBL;
      Term term = TERMS.get(random.nextInt(TERMS.size()));
      DocumentInput input = term.set().apply(base, generated);
      En16931DocumentRenderer renderer = new En16931DocumentRenderer(seller, profile, null);
      if (renderer.preflight(input.unnumbered()).verdict() != PreflightReport.Verdict.PASSED) {
        continue;
      }
      String code = renderFailureCode(renderer, input);
      if (code != null) {
        leaks.add(
            profile.name()
                + " / "
                + term.name()
                + ": the mapper accepted a value the writer then refused ("
                + code
                + ")");
      }
    }
    assertThat(leaks)
        .as("nothing the writer refuses gets past the mapper, which is why preflight skips it")
        .isEmpty();
  }

  /** One upstream-controlled string term, and how to put a value in it. */
  private record Term(
      String name, java.util.function.BiFunction<DocumentInput, String, DocumentInput> set) {}

  private static final List<Term> TERMS =
      List.of(
          new Term("buyer name (BT-44)", PreflightConsistencyTest::hostileBuyerName),
          new Term("buyer street (BT-50)", PreflightConsistencyTest::withBuyerStreet),
          new Term("buyer street 2 (BT-51)", PreflightConsistencyTest::withBuyerStreet2),
          new Term("buyer city (BT-52)", PreflightConsistencyTest::withBuyerCity),
          new Term("buyer post code (BT-53)", PreflightConsistencyTest::withBuyerPostCode),
          new Term("line item name (BT-153)", PreflightConsistencyTest::withLineDescription),
          new Term("line identifier (BT-126)", PreflightConsistencyTest::withLineId),
          new Term("upstream invoice number", PreflightConsistencyTest::withUpstreamNumber));

  private static DocumentInput withBuyerStreet2(DocumentInput base, String line2) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            line2,
            b.postalCode(),
            b.city(),
            b.country(),
            b.taxId()));
  }

  private static DocumentInput withLineDescription(DocumentInput base, String description) {
    return withLines(
        base,
        line ->
            new SourceInvoice.SourceLine(
                line.id(),
                description,
                line.taxRateId(),
                line.quantity(),
                line.netMinor(),
                line.grossMinor()));
  }

  private static DocumentInput withLineId(DocumentInput base, String id) {
    return withLines(
        base,
        line ->
            new SourceInvoice.SourceLine(
                id,
                line.description(),
                line.taxRateId(),
                line.quantity(),
                line.netMinor(),
                line.grossMinor()));
  }

  private static DocumentInput withLines(
      DocumentInput base, java.util.function.UnaryOperator<SourceInvoice.SourceLine> change) {
    SourceInvoice i = base.invoice();
    List<SourceInvoice.SourceLine> lines = new ArrayList<>(i.lines());
    lines.set(0, change.apply(lines.get(0)));
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
            i.buyer(),
            List.copyOf(lines),
            i.taxBuckets(),
            i.taxTreatments(),
            i.subtotalMinor(),
            i.taxMinor(),
            i.totalMinor(),
            true));
  }

  private static DocumentInput withUpstreamNumber(DocumentInput base, String number) {
    SourceInvoice i = base.invoice();
    return reinput(
        base,
        new SourceInvoice(
            i.id(),
            number,
            i.accountId(),
            i.livemode(),
            i.currency(),
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

  /** One field. The tax id stays: clearing it here is what hid D5-01 for a whole review pass. */
  private static DocumentInput withBuyerCountry(DocumentInput base, String country) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    return withBuyer(
        base,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            b.city(),
            country,
            b.taxId()));
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
