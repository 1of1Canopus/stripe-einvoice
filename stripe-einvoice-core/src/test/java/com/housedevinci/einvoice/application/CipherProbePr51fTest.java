package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 51 pass 2 (fix/reserved-codes-and-stuck-check, head 96af207).
 *
 * <p>Two variants of D-PR51-1 (green on 96af207) and one new finding, D-PR51-3 (LOW): the pass
 * keeps the first failure and attaches later ones with {@code addSuppressed}. When two checks throw
 * the same exception instance, {@code first.addSuppressed(first)} throws IllegalArgumentException
 * ("Self-suppression not permitted") from inside the catch. It escapes the pass: the checks after
 * it do not run and the pass ends with an exception that carries no DEI code. Example: an archive
 * adapter that throws one preallocated "archive unavailable" exception (a circuit breaker in open
 * state often does) makes both archive checks throw the same instance.
 */
class CipherProbePr51fTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** Variant (a1): the archive is down AND the Stripe key is refused, in the same pass. */
  @Test
  void variant_archive_down_and_key_refused_every_other_check_still_runs() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String issued = "in_pr51f_both_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    harness.moveClockForward(Duration.ofHours(1));
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_ACCESS_REFUSED, "status 401"));
    Set<String> called = ConcurrentHashMap.newKeySet();

    ReconciliationSweep sweep =
        sweep(
            harness,
            spy(IssuanceReader.class, harness.store(), called, null),
            new DownArchive(false),
            called);

    assertThatThrownBy(sweep::sweep)
        .isInstanceOfSatisfying(
            EInvoiceException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCodes.STRIPE_ACCESS_REFUSED);
              assertThat(e.getSuppressed())
                  .anySatisfy(
                      s ->
                          assertThat(((EInvoiceException) s).code())
                              .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE));
            });
    assertThat(called)
        .as("key refused, stuck, series read and unfinished reprocess all ran")
        .contains("accessRefused", "openOlderThan", "inSeries", "unfinished");
  }

  /** Variant (a2): the series read fails; nothing in the archive may be reported foreign. */
  @Test
  void variant_series_read_failure_reports_no_orphan() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String issued = "in_pr51f_read_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    harness.moveClockForward(Duration.ofHours(1));
    assertThat(harness.archive().list("live/" + harness.sellerId(), 100)).isNotEmpty();
    Set<String> called = ConcurrentHashMap.newKeySet();

    ReconciliationSweep sweep =
        sweep(
            harness,
            spy(IssuanceReader.class, harness.store(), called, "inSeries"),
            harness.archive(),
            called);

    assertThatThrownBy(sweep::sweep).isInstanceOf(EInvoiceException.class);
    assertThat(harness.openFindings())
        .noneSatisfy(f -> assertThat(f.code()).isEqualTo(ErrorCodes.RECON_ORPHAN_OBJECT));
    assertThat(called).contains("unfinished");
  }

  /**
   * D-PR51-4 (INFO). Red on 96af207: when the series read fails, both archive checks are skipped,
   * but the WARN names only 'archive to us'. An operator reading the log for why DEI-271 and
   * DEI-272 stopped sees no line for 'us to archive'.
   */
  @Test
  void probe_a_failed_series_read_names_only_one_of_the_two_skipped_checks() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String issued = "in_pr51f_log_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    harness.moveClockForward(Duration.ofHours(1));
    Set<String> called = ConcurrentHashMap.newKeySet();
    ReconciliationSweep sweep =
        sweep(
            harness,
            spy(IssuanceReader.class, harness.store(), called, "inSeries"),
            harness.archive(),
            called);
    java.io.PrintStream original = System.err;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    try {
      System.setErr(
          new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
      assertThatThrownBy(sweep::sweep).isInstanceOf(EInvoiceException.class);
    } finally {
      System.setErr(original);
    }
    String log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    // D-PR52-3, C10: the orphan walk no longer needs the series rows, so it runs; only the
    // us-to-archive check is skipped, and it is the one the log names (QUESTIONS 61).
    assertThat(log).as("the skipped us-to-archive check is named").contains("'us to archive'");
    assertThat(called).as("the orphan walk ran its lookup").contains("knownKeys");
  }

  /**
   * D-PR51-3. Red on 96af207: IllegalArgumentException escapes; unfinished reprocess never runs.
   */
  @Test
  void probe_one_shared_exception_instance_aborts_the_rest_of_the_pass() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String issued = "in_pr51f_shared_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    harness.moveClockForward(Duration.ofHours(1));
    Set<String> called = ConcurrentHashMap.newKeySet();

    ReconciliationSweep sweep = sweep(harness, harness.store(), new DownArchive(true), called);

    assertThatThrownBy(sweep::sweep)
        .as("the pass ends with the archive's own coded failure")
        .isInstanceOfSatisfying(
            EInvoiceException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE));
    assertThat(called)
        .as("the unfinished-reprocess check still runs after two archive failures")
        .contains("unfinished");
  }

  private static ReconciliationSweep sweep(
      IssuanceTestHarness harness,
      IssuanceReader reader,
      ArchiveStore archive,
      Set<String> called) {
    return new ReconciliationSweep(
        harness.source(),
        reader,
        spy(InboundEventStore.class, harness.inbound(), called, null),
        archive,
        harness.findings(),
        spy(ReprocessLedger.class, harness.reprocessLedger(), called, null),
        harness.orphanCursors(),
        Clock.fixed(harness.now(), ZoneOffset.UTC),
        SETTINGS,
        harness.unitOfWork().configuration());
  }

  @SuppressWarnings("unchecked")
  private static <T> T spy(Class<T> type, T target, Set<String> called, String failing) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, args) -> {
              called.add(m.getName());
              if (m.getName().equals(failing)) {
                throw new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "probe: read failed");
              }
              try {
                return m.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** An archive whose every read fails; {@code shared} throws one preallocated instance. */
  private static final class DownArchive implements ArchiveStore {
    private static final EInvoiceException SHARED =
        new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "probe: circuit open");
    private final boolean shared;

    DownArchive(boolean shared) {
      this.shared = shared;
    }

    private EInvoiceException down() {
      return shared
          ? SHARED
          : new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "probe: archive down");
    }

    @Override
    public boolean supportsAtomicCreate() {
      return true;
    }

    @Override
    public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
      throw down();
    }

    @Override
    public Optional<byte[]> get(ArchiveKey key) {
      throw down();
    }

    @Override
    public com.housedevinci.einvoice.application.ArchiveListing listAfter(
        String prefix, String startAfter, int limit) {
      throw down();
    }

    @Override
    public String describe() {
      return "probe-down-archive";
    }
  }
}
