package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.domain.InboundState;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Security review probes for the issuance preflight. Each one fails on the code under review. */
class CipherProbePr5Test {

  private static SourceInvoice withBuyer(SourceInvoice i, SourceInvoice.SourceParty buyer) {
    return new SourceInvoice(
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
        true);
  }

  /** The French fixture's buyer with no tax id: everything else valid, including the country. */
  private static SourceInvoice buyerWithoutTaxId() {
    SourceInvoice i = DocumentFixtures.frenchStandardRated().invoice();
    SourceInvoice.SourceParty b = i.buyer();
    return withBuyer(
        i,
        new SourceInvoice.SourceParty(
            b.name(),
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            b.city(),
            b.country(),
            null));
  }

  /** The German fixture's buyer with no street, city or post code: BR-DE-8/9/10. */
  private static SourceInvoice buyerWithoutStreet() {
    SourceInvoice i = DocumentFixtures.germanStandardRated().invoice();
    SourceInvoice.SourceParty b = i.buyer();
    return withBuyer(
        i,
        new SourceInvoice.SourceParty(
            b.name(), b.email(), null, null, null, null, b.country(), b.taxId()));
  }

  // ---------------------------------------------------------------------------------------
  // D5-01. The writer's own profile refusals are invisible to the preflight.
  // ---------------------------------------------------------------------------------------

  @Test
  void probe_a_writer_profile_refusal_is_visible_to_the_preflight() {
    List<String> passedButRefused = new ArrayList<>();

    record Case(String name, SourceInvoice invoice, UblProfile profile) {}
    List<Case> cases =
        List.of(
            new Case(
                "peppol, buyer with no tax id (BT-49)",
                buyerWithoutTaxId(),
                UblProfile.PEPPOL_BIS_UBL),
            new Case(
                "xrechnung, buyer with no street (BR-DE-8)",
                buyerWithoutStreet(),
                UblProfile.XRECHNUNG_UBL));

    for (Case c : cases) {
      En16931DocumentRenderer renderer =
          new En16931DocumentRenderer(
              c.profile() == UblProfile.XRECHNUNG_UBL
                  ? DocumentFixtures.germanSeller()
                  : DocumentFixtures.frenchSeller(),
              c.profile(),
              null);
      MappingInput mapping = DocumentFixtures.mapping(c.invoice());
      PreflightReport report = renderer.preflight(mapping);
      String renderCode = null;
      try {
        renderer.render(new DocumentInput(mapping, DocumentFixtures.NUMBER));
      } catch (com.housedevinci.einvoice.domain.EInvoiceException e) {
        renderCode = e.code();
      }
      if (report.verdict() == PreflightReport.Verdict.PASSED && renderCode != null) {
        passedButRefused.add(c.name() + ": preflight PASSED, render refused " + renderCode);
      }
    }

    assertThat(passedButRefused)
        .as(
            "the preflight skips the writer on the claimed invariant that a writer refusal implies"
                + " a mapper refusal; the writer's profile-mandatory rules break it")
        .isEmpty();
  }

  @Test
  void probe_a_buyer_without_a_tax_id_consumes_no_number_under_peppol() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    long counterBefore = harness.seriesCounter();
    harness.source().with(buyerWithoutTaxId());
    String eventId = harness.receive("invoice.finalized", "in_fr_standard");

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWithRenderer(
                new En16931DocumentRenderer(
                    DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null))
            .process(eventId);

    System.out.println(
        "D5-01: outcome="
            + outcome.state()
            + " "
            + outcome.code()
            + " preflight="
            + outcome.preflight()
            + " numbered="
            + harness.numberedRows()
            + " counter="
            + counterBefore
            + "->"
            + harness.seriesCounter());

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.seriesCounter()).isEqualTo(counterBefore);
  }

  // ---------------------------------------------------------------------------------------
  // D5-02. The P-02 reflection walk is default-allow on the container types it does not know.
  // ---------------------------------------------------------------------------------------

  /** A payload shape the walk must reach, or the "a new field fails the build" claim is false. */
  public record Shaped(
      String plain,
      Optional<String> optional,
      List<String> strings,
      Map<String, String> metadata,
      String[] array,
      Nested nested) {
    public record Nested(Optional<String> deep) {}
  }

  @Test
  @SuppressWarnings("unchecked")
  void probe_the_screen_detector_reaches_every_string_in_the_payload_shape() throws Exception {
    Class<?> probe =
        Class.forName("com.housedevinci.einvoice.application.CipherProbePreflightTest");
    Method walk = probe.getDeclaredMethod("stringPaths", Class.class, String.class, List.class);
    walk.setAccessible(true);
    List<String> paths =
        (List<String>) walk.invoke(null, Shaped.class, "Shaped", new ArrayList<Class<?>>());
    System.out.println("D5-02: the walk reached " + paths);

    assertThat(paths)
        .as(
            "a String hidden in an Optional, a List<String>, a Map or an array is never reached,"
                + " never hostile-tested and never named in the exclusion list, so the detector is"
                + " silently default-allow for those shapes")
        .contains(
            "Shaped.plain",
            "Shaped.optional",
            "Shaped.strings[]",
            "Shaped.metadata",
            "Shaped.array",
            "Shaped.nested.deep");
  }

  // ---------------------------------------------------------------------------------------
  // D5-01/D5-03. The buyer-without-tax-id fixture is now refused at the preflight, before a
  // number is ever allocated - it covers the preflight refusal, not a consumed number's
  // disposition. See CipherProbePr7bDispositionTest for a preflight that PASSES and a render
  // that then refuses, which is what D5-03 is actually about (D7-01).
  // ---------------------------------------------------------------------------------------

  @Test
  void probe_a_buyer_with_no_tax_id_is_refused_at_the_preflight_with_no_number_allocated() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(buyerWithoutTaxId());
    String eventId = harness.receive("invoice.finalized", "in_fr_standard");
    harness
        .unitOfWorkWithRenderer(
            new En16931DocumentRenderer(
                DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null))
        .process(eventId);

    var issuance = harness.issuance("in_fr_standard");
    System.out.println(
        "D5-01: numbered="
            + harness.numberedRows()
            + " state="
            + issuance.map(i -> i.state().name()).orElse("<none>"));
    assertThat(issuance.map(i -> i.state().name()).orElse("<none>"))
        .as(
            "a buyer with no tax id is refused at the preflight, before a number is ever"
                + " allocated - no row is ever created, so it cannot be left in NUMBERED")
        .isNotEqualTo("NUMBERED");
  }

  // ---------------------------------------------------------------------------------------
  // D5-04. The determinism rule the "same verdict twice" claim rests on names five methods and
  // misses the overloads that read the environment just as effectively.
  // ---------------------------------------------------------------------------------------

  @Test
  void probe_the_determinism_rule_refuses_every_environment_reader() {
    // The branch's own rule, called rather than copied (the copy this probe first carried could
    // not flip green when the rule was tightened, which is how a rule with a gap survives the test
    // written to find it). Applied to an import of one class in the mapper's package that reads
    // the environment through the overloads the rule omitted.
    com.tngtech.archunit.core.domain.JavaClasses classes =
        new com.tngtech.archunit.core.importer.ClassFileImporter()
            .importClasses(
                com.housedevinci.einvoice.adapter.en16931.CipherNonDeterministicFixture.class);
    com.tngtech.archunit.lang.ArchRule rule =
        com.housedevinci.einvoice.DeterminismRules.preflightPath();

    AssertionError refused = null;
    try {
      rule.check(classes);
    } catch (AssertionError e) {
      refused = e;
    }
    System.out.println(
        "D5-04: rule refused the fixture = "
            + (refused != null)
            + (refused == null ? "" : " because " + refused.getMessage()));
    assertThat(refused)
        .as(
            "LocalDate.now(ZoneId), ZonedDateTime.now(), OffsetDateTime.now(),"
                + " System.currentTimeMillis() and Locale.getDefault(Category) all read the"
                + " environment and all pass the rule that is supposed to keep the preflight and"
                + " the render on one verdict")
        .isNotNull();
  }
}
