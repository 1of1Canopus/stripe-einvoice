package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import org.junit.jupiter.api.Test;

/**
 * The crash table, one test per row: the process dies immediately after each phase and the retry
 * has to arrive at one document under one number, or at nothing at all.
 *
 * <p>A {@link KillSignal} is a plain unchecked throwable that no handler in the module catches, so
 * it leaves the unit of work exactly where a dying JVM would: mid-flight, with whatever is
 * committed committed and nothing else.
 */
class IssuanceCrashTest {

  /**
   * Not an {@code EInvoiceException}, and deliberately not a {@code RuntimeException} either
   * (D2-04): once a host-supplied renderer, validator or archive store's {@code RuntimeException}
   * is caught and mapped to a stable code, a plain {@code RuntimeException} is no longer a faithful
   * stand-in for "the process died here" - a real crash never runs a catch block at all, of any
   * kind, so the honest simulation is a throwable ordinary exception handling does not reach. An
   * {@code Error} is exactly that: nothing in this module, before or after D2-04, catches one.
   */
  static final class KillSignal extends Error {
    private static final long serialVersionUID = 1L;

    KillSignal() {
      super("the process died here");
    }
  }

  @Test
  void a_kill_between_claim_and_render_resumes_on_the_same_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_k1"));
    String eventId = harness.receive("invoice.finalized", "in_k1");
    harness.renderer().breakWith(new KillSignal());

    assertThatThrownBy(() -> harness.unitOfWork().process(eventId)).isInstanceOf(KillSignal.class);

    Issuance afterCrash = harness.issuance("in_k1").orElseThrow();
    assertThat(afterCrash.state()).isEqualTo(IssuanceState.NUMBERED);
    assertThat(harness.archive().size()).isZero();

    harness.renderer().heal();
    IssuanceUnitOfWork.Outcome retried = harness.unitOfWork().process(eventId);

    assertThat(retried.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(retried.legalNumber()).isEqualTo(afterCrash.legalNumber().value());
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_kill_between_render_and_write_writes_identical_bytes() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_k2"));
    String eventId = harness.receive("invoice.finalized", "in_k2");
    harness.archive().failWith(new KillSignal());

    assertThatThrownBy(() -> harness.unitOfWork().process(eventId)).isInstanceOf(KillSignal.class);

    Issuance afterCrash = harness.issuance("in_k2").orElseThrow();
    // The row predicted the object before the PUT, so an orphan would be one indexed query away.
    assertThat(afterCrash.state()).isEqualTo(IssuanceState.ARCHIVING);
    assertThat(afterCrash.documentHash()).isPresent();
    assertThat(afterCrash.archiveObjectKey()).isPresent();
    assertThat(harness.archive().size()).isZero();

    harness.archive().heal();
    harness.unitOfWork().process(eventId);

    Issuance issued = harness.issuance("in_k2").orElseThrow();
    assertThat(issued.state()).isEqualTo(IssuanceState.ISSUED);
    // The same hash and the same key as the row predicted before the crash: the re-render is
    // byte-identical, which is the whole reason the key can carry the content hash.
    assertThat(issued.documentSha256()).isEqualTo(afterCrash.documentSha256());
    assertThat(issued.archiveKey()).isEqualTo(afterCrash.archiveKey());
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_kill_between_write_and_issue_completes_on_retry_with_one_document() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_k3"));
    String eventId = harness.receive("invoice.finalized", "in_k3");
    harness.archive().failAfterWriteWith(new KillSignal());

    assertThatThrownBy(() -> harness.unitOfWork().process(eventId)).isInstanceOf(KillSignal.class);

    assertThat(harness.issuance("in_k3").orElseThrow().state()).isEqualTo(IssuanceState.ARCHIVING);
    assertThat(harness.archive().size()).isEqualTo(1);

    harness.archive().heal();
    IssuanceUnitOfWork.Outcome retried = harness.unitOfWork().process(eventId);

    assertThat(retried.state()).isEqualTo(InboundState.COMPLETED);
    // The retry's write-once PUT saw identical bytes and succeeded. One object, one number.
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  @Test
  void a_kill_during_the_chain_append_leaves_anchor_and_trail_consistent() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_k4"));
    String eventId = harness.receive("invoice.finalized", "in_k4");
    harness.archive().failAfterWriteWith(new KillSignal());
    assertThatThrownBy(() -> harness.unitOfWork().process(eventId)).isInstanceOf(KillSignal.class);
    harness.archive().heal();

    long anchorBefore = harness.anchorRowCount();
    // P4 runs the state update and the chain append, and then the process dies before the commit.
    harness.killDuringChainAppend("in_k4");

    assertThat(harness.issuance("in_k4").orElseThrow().state()).isEqualTo(IssuanceState.ARCHIVING);
    assertThat(harness.chainedEvents()).isZero();
    assertThat(harness.anchorRowCount()).isEqualTo(anchorBefore);

    IssuanceUnitOfWork.Outcome retried = harness.unitOfWork().process(eventId);

    assertThat(retried.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.issuance("in_k4").orElseThrow().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.anchorRowCount()).isEqualTo(anchorBefore + 1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_kill_before_anything_was_claimed_leaves_no_number_at_all() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_k5"));
    String eventId = harness.receive("invoice.finalized", "in_k5");
    harness.source().breakWith(new KillSignal());

    assertThatThrownBy(() -> harness.unitOfWork().process(eventId)).isInstanceOf(KillSignal.class);

    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.issuance("in_k5")).isEmpty();

    harness.source().heal();
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.numberedRows()).isEqualTo(1);
  }
}
