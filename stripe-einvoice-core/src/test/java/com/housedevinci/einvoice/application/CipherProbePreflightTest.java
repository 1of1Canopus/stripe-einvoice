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
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  private static DocumentRenderer germanRenderer() {
    return new En16931DocumentRenderer(
        DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null);
  }

  private static SourceInvoice germanFixture() {
    return DocumentFixtures.germanStandardRated().invoice();
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
  @ParameterizedTest(name = "under {0}")
  @ValueSource(strings = {"peppol", "xrechnung"})
  void probe_every_screened_buyer_field_refuses_before_the_number(String profileName) {
    // Both profiles (D5-01): a value that is harmless under one and fatal under the other is
    // invisible to a single-profile run, and the profile-mandatory terms differ between them.
    boolean peppol = "peppol".equals(profileName);
    SourceInvoice baseline = peppol ? fixture() : germanFixture();
    java.util.function.Supplier<DocumentRenderer> renderer =
        peppol ? CipherProbePreflightTest::realRenderer : CipherProbePreflightTest::germanRenderer;
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
        hostile = (SourceInvoice) replace(baseline, path.split("\\."), 1, HOSTILE);
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
              .unitOfWorkWithRenderer(renderer.get())
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
    verdicts.forEach(
        (path, verdict) -> System.out.println("  " + profileName + " " + path + " : " + verdict));

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

  /**
   * The walk and the rewriter are one control, so the rewriter is tested on every shape the walk
   * can name. A walk that reaches a string the rewriter cannot put a hostile value into would
   * report that path as "refused at the boundary" and look like a pass.
   */
  @Test
  void the_walk_and_the_rewriter_cover_the_same_shapes() {
    List<String> paths = stringPaths(Shaped.class, "Shaped", new ArrayList<>());
    assertThat(paths)
        .contains(
            "Shaped.plain",
            "Shaped.optional",
            "Shaped.strings[]",
            "Shaped.metadata",
            "Shaped.metadata{}",
            "Shaped.array",
            "Shaped.nested.deep");
    for (String path : paths) {
      Object rewritten = replace(SHAPED, path.split("\\."), 1, HOSTILE);
      assertThat(rewritten.toString())
          .as("the rewriter puts a hostile value into %s", path)
          .contains(HOSTILE);
    }
  }

  /**
   * Every container shape a payload record can grow into, so neither half can silently skip one.
   */
  record Shaped(
      String plain,
      Optional<String> optional,
      List<String> strings,
      Map<String, String> metadata,
      String[] array,
      Nested nested) {

    record Nested(Optional<String> deep) {}

    @Override
    public String toString() {
      return plain
          + optional
          + strings
          + metadata
          + java.util.Arrays.toString(array)
          + nested.deep();
    }
  }

  private static final Shaped SHAPED =
      new Shaped(
          "plain",
          Optional.of("optional"),
          List.of("one"),
          Map.of("k", "v"),
          new String[] {"a"},
          new Shaped.Nested(Optional.of("deep")));

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
  // D5-03. A number consumed after the preflight passed says so on its own row.
  // -------------------------------------------------------------------------------------------

  /**
   * The residue in the security notes says the five post-allocation refusals "need an operator
   * void". For a renderer or writer fault that was not true: the row stayed {@code NUMBERED} - a
   * legal number, no document, no reason - and the only thing that ever noticed was the
   * reconciliation sweep's stuck check, hours later and by count rather than by cause.
   *
   * <p>Both catch paths, because they are two branches: this module's own exception type, and a
   * host renderer's unexpected {@code RuntimeException}.
   */
  @ParameterizedTest(name = "a render that throws {0}")
  @ValueSource(strings = {"a typed refusal", "an unexpected runtime exception"})
  void a_render_refusal_records_a_disposition_on_the_numbered_row(String kind) {
    boolean typed = kind.startsWith("a typed");
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_render_fault"));
    DeterministicRenderer renderer = new DeterministicRenderer();
    renderer.breakWith(
        typed
            ? new EInvoiceException(ErrorCodes.RENDER_FAILED, "the writer refused")
            : new IllegalStateException("a bug in somebody else's renderer"));

    String eventId = harness.receive("invoice.finalized", "in_render_fault");
    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWorkWithRenderer(renderer).process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.numberedRows()).isEqualTo(1);
    com.housedevinci.einvoice.domain.Issuance issuance =
        harness.issuance("in_render_fault").orElseThrow();
    assertThat(issuance.state())
        .describedAs("the number's fate is on its own row, not only in the sweep's count")
        .isEqualTo(com.housedevinci.einvoice.domain.IssuanceState.FAILED_VALIDATION);
    // The cause lives on the inbound row, which is where this module has always put a rule id -
    // the issuance row carries the state, the event row carries why. Both are keyed by the same
    // Stripe invoice id, so an operator holding a failed number has one query to the reason.
    assertThat(harness.inbound().lastRuleId(eventId))
        .describedAs("and the event row says which refusal it was, the way a rule id does")
        .contains(ErrorCodes.RENDER_FAILED);
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

  /**
   * Every {@code String} reachable from a record: directly, through a nested record, through an
   * {@code Optional}, through a {@code List} or {@code Set}, through a {@code Map}'s keys and
   * values, and through an array - at any depth.
   *
   * <p><b>Total by construction.</b> A component whose type this walk does not recognise fails the
   * test with its declared type in the message, because a walk that silently skips a shape is
   * default-allow for that shape: the field never appears in the exclusion list, the build stays
   * green, and "a new field without a screen fails the build" stops being true. The shapes it skips
   * on purpose are scalars that cannot carry a string, listed in {@link #CARRIES_NO_STRING}.
   */
  private static List<String> stringPaths(Class<?> type, String prefix, List<Class<?>> visiting) {
    List<String> paths = new ArrayList<>();
    if (visiting.contains(type)) {
      return paths;
    }
    visiting.add(type);
    for (RecordComponent component : type.getRecordComponents()) {
      paths.addAll(
          pathsOf(
              prefix + "." + component.getName(),
              component.getType(),
              component.getGenericType(),
              visiting,
              type.getSimpleName() + "." + component.getName()));
    }
    visiting.remove(type);
    return paths;
  }

  /** One component, or one type argument of one, resolved to the string paths under it. */
  private static List<String> pathsOf(
      String path, Class<?> type, Type generic, List<Class<?>> visiting, String describedAs) {
    List<String> paths = new ArrayList<>();
    if (type == String.class) {
      paths.add(path);
    } else if (type.isRecord()) {
      paths.addAll(stringPaths(type, path, visiting));
    } else if (type.isArray()) {
      // No marker on an array, matching the security review's own expected path names; the
      // rewriter dispatches on the runtime value, which knows it is an array without being told.
      paths.addAll(
          pathsOf(path, type.getComponentType(), type.getComponentType(), visiting, describedAs));
    } else if (Optional.class.isAssignableFrom(type)) {
      // No marker in the path: an Optional is a wrapper around one value, not a collection.
      paths.addAll(pathsOf(path, argument(generic, 0, describedAs), null, visiting, describedAs));
    } else if (Collection.class.isAssignableFrom(type)) {
      paths.addAll(
          pathsOf(path + "[]", argument(generic, 0, describedAs), null, visiting, describedAs));
    } else if (Map.class.isAssignableFrom(type)) {
      // Both halves: Stripe metadata is a Map<String, String> and a hostile key reaches a document
      // exactly as a hostile value does.
      paths.addAll(pathsOf(path, argument(generic, 0, describedAs), null, visiting, describedAs));
      paths.addAll(
          pathsOf(path + "{}", argument(generic, 1, describedAs), null, visiting, describedAs));
    } else if (!CARRIES_NO_STRING.contains(type) && !type.isEnum() && !type.isPrimitive()) {
      throw new AssertionError(
          "this walk does not know how to look for strings inside "
              + describedAs
              + " (declared type "
              + type.getName()
              + "). A shape it does not walk is a shape it cannot hostile-test, and a field of that"
              + " shape would be added without a screen and without this probe noticing. Teach the"
              + " walk the shape, or add the type to CARRIES_NO_STRING with the reason it cannot"
              + " carry one.");
    }
    // A path that ends in a Map's key and a path that ends in its value are the same rewrite
    // target for this probe, so they are de-duplicated rather than tested twice.
    return paths.stream().distinct().toList();
  }

  /** Types that cannot hold a string, so the walk stops at them rather than failing. */
  private static final List<Class<?>> CARRIES_NO_STRING =
      List.of(
          boolean.class,
          byte.class,
          char.class,
          short.class,
          int.class,
          long.class,
          float.class,
          double.class,
          Boolean.class,
          Byte.class,
          Character.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          java.math.BigDecimal.class,
          java.math.BigInteger.class,
          java.time.Instant.class,
          java.time.LocalDate.class,
          java.time.LocalDateTime.class,
          java.time.ZonedDateTime.class,
          java.time.Duration.class,
          java.util.UUID.class);

  private static Class<?> argument(Type generic, int index, String describedAs) {
    if (generic instanceof ParameterizedType parameterized
        && parameterized.getActualTypeArguments().length > index
        && parameterized.getActualTypeArguments()[index] instanceof Class<?> argument) {
      return argument;
    }
    throw new AssertionError(
        "the type argument of "
            + describedAs
            + " is not a plain class, so this walk cannot tell whether a string hides under it."
            + " A wildcard or a type variable on a payload component is a shape to resolve here,"
            + " never one to skip.");
  }

  /**
   * Rebuilds {@code record} with one component - possibly deep inside it - set to {@code value}.
   */
  private static Object replace(Object record, String[] path, int index, String value) {
    String step = path[index];
    boolean intoList = step.endsWith("[]");
    boolean intoMapValue = step.endsWith("{}");
    String name = intoList || intoMapValue ? step.substring(0, step.length() - 2) : step;
    Class<?> type = record.getClass();
    RecordComponent[] components = type.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      arguments[i] = read(record, components[i]);
      if (!components[i].getName().equals(name)) {
        continue;
      }
      arguments[i] = rewrite(arguments[i], path, index, value, intoList, intoMapValue);
    }
    return construct(type, components, arguments);
  }

  /** The value of one component, with the hostile string put wherever the path says. */
  private static Object rewrite(
      Object current,
      String[] path,
      int index,
      String value,
      boolean intoList,
      boolean intoMapValue) {
    boolean last = index == path.length - 1;
    if (current instanceof Optional<?> optional) {
      Object inner = optional.orElse(null);
      if (inner == null || (last && inner instanceof String)) {
        return Optional.of(value);
      }
      return Optional.of(rewrite(inner, path, index, value, intoList, intoMapValue));
    }
    if (intoList && current instanceof Collection<?> collection) {
      List<Object> rebuilt = new ArrayList<>(collection);
      Object first = rebuilt.isEmpty() ? null : rebuilt.get(0);
      Object replaced =
          last || first instanceof String ? value : replace(first, path, index + 1, value);
      if (rebuilt.isEmpty()) {
        rebuilt.add(replaced);
      } else {
        rebuilt.set(0, replaced);
      }
      return current instanceof java.util.Set
          ? java.util.Set.copyOf(rebuilt)
          : List.copyOf(rebuilt);
    }
    if (current != null && current.getClass().isArray()) {
      Object[] rebuilt = ((Object[]) current).clone();
      if (rebuilt.length > 0) {
        rebuilt[0] = value;
        return rebuilt;
      }
      return new String[] {value};
    }
    if (current instanceof Map<?, ?> map) {
      Map<Object, Object> rebuilt = new LinkedHashMap<>(map);
      if (intoMapValue) {
        Object key = rebuilt.keySet().stream().findFirst().orElse("k");
        rebuilt.put(key, value);
      } else {
        rebuilt.put(value, rebuilt.values().stream().findFirst().orElse("v"));
      }
      return Map.copyOf(rebuilt);
    }
    if (last) {
      return value;
    }
    return replace(current, path, index + 1, value);
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
