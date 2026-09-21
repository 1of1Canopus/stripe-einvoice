package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pre-allocation preflight, against a real PostgreSQL, the real EN 16931 mapper and a real
 * series counter.
 *
 * <p>The claim under test is the one the public text makes: an invoice this module cannot put on a
 * document is refused <b>before</b> the allocator runs, and costs no legal number - no issuance
 * row, no chain entry, no archived object, no advance of the counter, and no further attempt.
 */
class CipherProbePreflightTest {

  /** U+FFFF: a permanent non-character. No XML 1.0 parser will read a document containing it. */
  private static final String HOSTILE = "Elbe" + '￿' + "AG";

  private static final String INVOICE_ID = "in_fr_standard";

  private static DocumentRenderer realRenderer() {
    return new En16931DocumentRenderer(
        DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null);
  }

  private static SourceInvoice fixture() {
    return DocumentFixtures.frenchStandardRated().invoice();
  }

  // -------------------------------------------------------------------------------------------
  // Probe 1. The one this whole change exists for.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_hostile_buyer_name_consumes_no_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    long counterBefore = harness.seriesCounter();
    harness.source().with(withBuyerName(fixture(), HOSTILE));
    String eventId = harness.receive("invoice.finalized", INVOICE_ID);

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWorkWithRenderer(realRenderer()).process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.INVALID);
    assertThat(outcome.legalNumber()).isNull();
    assertThat(outcome.preflight()).contains(PreflightReport.Verdict.REFUSED);
    // The five zeroes.
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.chainedEvents()).isZero();
    assertThat(harness.archive().size()).isZero();
    assertThat(harness.seriesCounter()).isEqualTo(counterBefore);
    harness.moveClockForward(Duration.ofHours(2));
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 2. The detector: the upstream payload's own shape, walked by reflection, default-deny.
  // -------------------------------------------------------------------------------------------

  /**
   * Why this is not a tautology: the enumeration below is derived from {@link SourceInvoice}'s own
   * record shape - which is dictated by the upstream payload - and takes nothing at all from the
   * mapper, no annotation it reads and no constant it shares, so a field added to the payload
   * without a screen in the mapper turns this red on the commit that adds the field.
   *
   * <p>Default-deny: every {@code String} reached by the walk is hostile-tested unless it is named
   * in {@link #NOT_A_DOCUMENT_FIELD} with its reason, and that list is printed on every run.
   *
   * <p>Recursive: the buyer's strings are mostly not top level - the line description, the buyer's
   * address and tax id, the tax treatment - so the walk descends into nested records and into the
   * record elements of lists.
   */
  @Test
  void probe_every_screened_buyer_field_refuses_before_the_number() {
    List<String> paths = stringPaths(SourceInvoice.class, "SourceInvoice", new ArrayList<>());
    assertThat(paths)
        .as("the walk found the fields that are one and two levels down, not only the top level")
        .contains(
            "SourceInvoice.buyer.name",
            "SourceInvoice.buyer.line1",
            "SourceInvoice.buyer.taxId",
            "SourceInvoice.lines[].description",
            "SourceInvoice.taxTreatments[].taxabilityReason");

    Map<String, String> verdicts = new LinkedHashMap<>();
    List<String> numbersConsumed = new ArrayList<>();
    for (String path : paths) {
      if (NOT_A_DOCUMENT_FIELD.containsKey(path)) {
        verdicts.put(path, "EXCLUDED - " + NOT_A_DOCUMENT_FIELD.get(path));
        continue;
      }
      SourceInvoice hostile;
      try {
        hostile = (SourceInvoice) replace(fixture(), path.split("\\."), 1, HOSTILE);
      } catch (EInvoiceException refusedAtTheBoundary) {
        // The invoice cannot even be constructed with that value, so no number was ever at risk.
        verdicts.put(path, "refused by SourceInvoice itself (" + refusedAtTheBoundary.code() + ")");
        continue;
      }
      IssuanceTestHarness harness = IssuanceTestHarness.create();
      long counterBefore = harness.seriesCounter();
      harness.source().with(hostile);
      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWithRenderer(realRenderer())
              .process(harness.receive("invoice.finalized", hostile.id()));
      boolean free =
          harness.numberedRows() == 0
              && harness.chainedEvents() == 0
              && harness.archive().size() == 0
              && harness.seriesCounter() == counterBefore;
      verdicts.put(path, outcome.state() + " " + outcome.code() + (free ? " / no number" : ""));
      if (!free) {
        numbersConsumed.add(path + " -> " + outcome.state() + " " + outcome.code());
      }
    }
    verdicts.forEach((path, verdict) -> System.out.println("  " + path + " : " + verdict));

    assertThat(numbersConsumed)
        .as(
            "every buyer-controlled string either refuses before the allocator or is named in"
                + " NOT_A_DOCUMENT_FIELD with its reason")
        .isEmpty();
  }

  /**
   * The exclusions, each with the reason it is not a document field. Short on purpose: a long list
   * would mean this probe had been argued down rather than satisfied. Printed on every run.
   */
  private static final Map<String, String> NOT_A_DOCUMENT_FIELD =
      Map.of(
          "SourceInvoice.status",
          "the upstream lifecycle word. It routes (finalised, void) and is never written onto a"
              + " document.",
          "SourceInvoice.accountId",
          "the Stripe account the event arrived on. Screened by AllocationRequest when it is"
              + " recorded, never a business term.",
          "SourceInvoice.buyer.email",
          "BT-43 is not written by this edition, so the buyer's email reaches no document. Adding"
              + " the term adds a screen and removes this line.",
          "SourceInvoice.taxTreatments[].country",
          "the seller's own tax-rate configuration. The category is derived from the taxability"
              + " reason and from the two parties' countries, so this value is read by nothing and"
              + " reaches no document.",
          "SourceInvoice.taxTreatments[].taxType",
          "the same: the upstream's word for the kind of tax, read by nothing in this edition and"
              + " written onto no business term.");

  @Test
  void the_exclusion_list_names_only_fields_that_still_exist() {
    List<String> paths = stringPaths(SourceInvoice.class, "SourceInvoice", new ArrayList<>());
    assertThat(paths).containsAll(NOT_A_DOCUMENT_FIELD.keySet());
  }

  // -------------------------------------------------------------------------------------------
  // Probe 6 and P-01. The compatibility path says so, durably.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_renderer_without_preflight_says_so() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_legacy"));
    RendererWithoutPreflight legacy = new RendererWithoutPreflight();

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWithRenderer(legacy)
            .process(harness.receive("invoice.finalized", "in_legacy"));

    // A number is still allocated: a fail-closed default would break every renderer on upgrade.
    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.legalNumber()).isEqualTo("INV-2026-000001");
    // And it is never a silent PASSED.
    assertThat(outcome.preflight()).contains(PreflightReport.Verdict.NOT_SUPPORTED);
  }

  @Test
  void a_renderer_without_preflight_raises_a_compliance_finding_once() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RendererWithoutPreflight legacy = new RendererWithoutPreflight();
    String subject = legacy.getClass().getName();
    harness.source().with(TestInvoices.finalised("in_legacy_1"));
    harness.source().with(TestInvoices.finalised("in_legacy_2"));

    harness
        .unitOfWorkWithRenderer(legacy)
        .process(harness.receive("invoice.finalized", "in_legacy_1"));

    // The durable record, not only a boot log line nobody kept.
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code, ComplianceFinding::subjectId)
        .contains(org.assertj.core.groups.Tuple.tuple(ErrorCodes.PREFLIGHT_NOT_SUPPORTED, subject));

    harness
        .unitOfWorkWithRenderer(legacy)
        .process(harness.receive("invoice.finalized", "in_legacy_2"));

    // Once per application start, not once per invoice.
    assertThat(harness.findingRows(subject)).isEqualTo(1);
  }

  @Test
  void a_renderer_whose_preflight_throws_refuses_and_consumes_no_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    long counterBefore = harness.seriesCounter();
    harness.source().with(TestInvoices.finalised("in_broken_preflight"));
    DocumentRenderer broken =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            throw new IllegalStateException("never reached");
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            throw new IllegalStateException("a host renderer's own bug");
          }
        };

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWithRenderer(broken)
            .process(harness.receive("invoice.finalized", "in_broken_preflight"));

    // Fail closed: the same bug would have thrown in the render, where it costs a number.
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.PREFLIGHT_FAILED);
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.seriesCounter()).isEqualTo(counterBefore);
  }

  // -------------------------------------------------------------------------------------------
  // Probes 9 and 10, and P-03's ruling: a mapping refusal is final.
  // -------------------------------------------------------------------------------------------

  @Test
  void a_mapping_refusal_is_final_and_the_sweeper_never_re_picks_it() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(withBuyerName(fixture(), HOSTILE));
    String eventId = harness.receive("invoice.finalized", INVOICE_ID);
    harness.unitOfWorkWithRenderer(realRenderer()).process(eventId);
    int fetchesAfterTheRefusal = harness.source().fetches();

    // Terminal by enum and absent from the SQL the sweeper runs: two different facts.
    assertThat(harness.inbound().find(eventId).orElseThrow().state().terminal()).isTrue();
    for (Duration elapsed :
        List.of(Duration.ofMinutes(5), Duration.ofHours(4), Duration.ofDays(9))) {
      harness.moveClockForward(elapsed);
      assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
    }
    // And the corollary: no further Stripe call is made for that event.
    assertThat(harness.source().fetches()).isEqualTo(fetchesAfterTheRefusal);

    // A replay through process() returns the recorded outcome without re-running the pipeline.
    // The remedy for a frozen invoice this module cannot map is a corrected upstream object, and
    // therefore a new event; there is deliberately no reprocess of this row on this branch.
    IssuanceUnitOfWork.Outcome replay =
        harness.unitOfWorkWithRenderer(realRenderer()).process(eventId);
    assertThat(replay.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(harness.source().fetches()).isEqualTo(fetchesAfterTheRefusal);
    assertThat(harness.numberedRows()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // The reflection walk and the rewriter. Nothing here is shared with the mapper.
  // -------------------------------------------------------------------------------------------

  private static SourceInvoice withBuyerName(SourceInvoice invoice, String name) {
    SourceInvoice.SourceParty b = invoice.buyer();
    return new SourceInvoice(
        invoice.id(),
        invoice.number(),
        invoice.accountId(),
        invoice.livemode(),
        invoice.currency(),
        invoice.status(),
        invoice.finalizedAt(),
        new SourceInvoice.SourceParty(
            name,
            b.email(),
            b.line1(),
            b.line2(),
            b.postalCode(),
            b.city(),
            b.country(),
            b.taxId()),
        invoice.lines(),
        invoice.taxBuckets(),
        invoice.taxTreatments(),
        invoice.subtotalMinor(),
        invoice.taxMinor(),
        invoice.totalMinor(),
        true);
  }

  /** Every {@code String} reachable from a record, through nested records and lists of records. */
  private static List<String> stringPaths(Class<?> type, String prefix, List<Class<?>> visiting) {
    List<String> paths = new ArrayList<>();
    if (visiting.contains(type)) {
      return paths;
    }
    visiting.add(type);
    for (RecordComponent component : type.getRecordComponents()) {
      String path = prefix + "." + component.getName();
      Class<?> componentType = component.getType();
      if (componentType == String.class) {
        paths.add(path);
      } else if (componentType.isRecord()) {
        paths.addAll(stringPaths(componentType, path, visiting));
      } else if (List.class.isAssignableFrom(componentType)) {
        Class<?> element = elementType(component);
        if (element != null && element.isRecord()) {
          paths.addAll(stringPaths(element, path + "[]", visiting));
        }
      }
    }
    visiting.remove(type);
    return paths;
  }

  private static Class<?> elementType(RecordComponent component) {
    if (component.getGenericType() instanceof ParameterizedType parameterized
        && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
      return element;
    }
    return null;
  }

  /**
   * Rebuilds {@code record} with one component - possibly deep inside it - set to {@code value}.
   */
  private static Object replace(Object record, String[] path, int index, String value) {
    String step = path[index];
    boolean intoList = step.endsWith("[]");
    String name = intoList ? step.substring(0, step.length() - 2) : step;
    Class<?> type = record.getClass();
    RecordComponent[] components = type.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      arguments[i] = read(record, components[i]);
      if (!components[i].getName().equals(name)) {
        continue;
      }
      if (intoList) {
        List<?> original = (List<?>) arguments[i];
        List<Object> rebuilt = new ArrayList<>(original);
        rebuilt.set(0, replace(original.get(0), path, index + 1, value));
        arguments[i] = List.copyOf(rebuilt);
      } else if (index == path.length - 1) {
        arguments[i] = value;
      } else {
        arguments[i] = replace(arguments[i], path, index + 1, value);
      }
    }
    return construct(type, components, arguments);
  }

  private static Object read(Object record, RecordComponent component) {
    try {
      component.getAccessor().setAccessible(true);
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not read " + component.getName(), e);
    }
  }

  private static Object construct(Class<?> type, RecordComponent[] components, Object[] arguments) {
    Class<?>[] parameterTypes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      parameterTypes[i] = components[i].getType();
    }
    try {
      Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (InvocationTargetException thrown) {
      if (thrown.getCause() instanceof EInvoiceException typed) {
        throw typed;
      }
      throw new IllegalStateException("building " + type.getSimpleName() + " failed", thrown);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not build " + type.getSimpleName(), e);
    }
  }
}
