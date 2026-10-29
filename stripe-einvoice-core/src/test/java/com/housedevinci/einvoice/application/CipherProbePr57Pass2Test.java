package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.cursor;
import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.issue;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 57, pass 2 on 0b08f51. D-PR57-9: the D-PR57-4 rule ("an empty end after a
 * position is never a completion") assumes a store gives the end in the same answer as the last
 * keys. The port contract does not ask that: it says only that endOfFolder is true when the store
 * has said there is no further key. A store that learns the end on the next call keeps the
 * contract, and with it no cycle ever completes.
 */
class CipherProbePr57Pass2Test {

  /** One key a page, one page a pass, a 30-day cycle alert. */
  private static final ReconciliationSweep.Settings ONE_KEY =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1, 1, Duration.ofDays(30));

  /**
   * D-PR57-9 (LOW). A store that keeps the listAfter contract but reports the end only on the
   * answer after the last key (as a paging store does when the last page is exactly full). One
   * document, one key a pass. Pass 1 examines the key, "more may follow". Pass 2 gets "no key, end
   * of folder" at a position that is not empty, and the walk rewinds. Every cycle then repeats
   * this: the archive is walked again and again and no cycle is ever complete.
   */
  @Test
  void probe_a_store_that_reports_the_end_one_call_late_never_completes_a_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr57_late_end");

    OrphanWalkPathsTest.Scripted lateEnd =
        new OrphanWalkPathsTest.Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            ArchiveListing real = delegate.listAfter(prefix, startAfter, limit);
            // Legal under the contract: "end" is said only once the store has seen nothing more.
            return real.keys().isEmpty() ? real : new ArchiveListing(real.keys(), false);
          }
        };
    for (int pass = 0; pass < 6; pass++) {
      harness.sweepWith(ONE_KEY, lateEnd).sweep();
    }

    assertThat(cursor(harness).lastCompleteCycleAt())
        .as("every key was examined, three times over; a cycle must have completed")
        .isPresent();
  }

  /**
   * D-PR57-9, the other side, so the correction does not reopen D-PR57-4: a store that goes empty
   * at the start too (the volume is gone) still completes nothing.
   */
  @Test
  void a_store_gone_empty_everywhere_still_completes_no_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr57_gone_a");
    issue(harness, "in_pr57_gone_b");
    harness.sweep(ONE_KEY).sweep();
    assertThat(cursor(harness).position()).as("precondition: mid cycle").isNotEmpty();

    OrphanWalkPathsTest.Scripted gone =
        new OrphanWalkPathsTest.Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            return new ArchiveListing(List.of(), true);
          }
        };
    for (int pass = 0; pass < 3; pass++) {
      try {
        harness.sweepWith(ONE_KEY, gone).sweep();
      } catch (RuntimeException expected) {
        // DEI-241 from the empty-archive rule is a correct answer here.
      }
    }

    assertThat(cursor(harness).lastCompleteCycleAt()).isEmpty();
  }
}
