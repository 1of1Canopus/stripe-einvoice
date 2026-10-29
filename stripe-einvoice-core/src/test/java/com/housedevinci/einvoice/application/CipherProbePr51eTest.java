package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 51 pass 1 (fix/reserved-codes-and-stuck-check, head c44435e).
 *
 * <p>D-PR51-1 (LOW). PR 51 moved the stuck check before the Stripe list call so a refused key or an
 * outage cannot skip it. The other checks that need no Stripe call still run after it: the archive
 * directions (us to archive: missing and drift; archive to us: orphans) and the
 * unfinished-reprocess check. A refused key skips every one of them for as long as it lasts.
 * Example: the operator revokes the restricted key on Friday; on Saturday someone deletes an
 * archived invoice and drops a foreign object in the archive; no pass names either until the key is
 * fixed. Property: every check runs in every pass; a failure in one check (Stripe, archive) never
 * skips another; the pass still fails, so the sweeper still WARNs and DEI-274 still fires.
 *
 * <p>Placement: copy into
 * stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/.
 */
class CipherProbePr51eTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** Red on c44435e: the list call throws before the archive directions run. */
  @Test
  void probe_a_refused_stripe_key_skips_the_archive_checks_of_the_same_pass() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String gone = "in_pr51e_gone_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(gone));
    harness.unitOfWork().process(harness.receive("invoice.finalized", gone));
    harness.archive().forget(harness.issuance(gone).orElseThrow().archiveKey());
    String orphan =
        "live/" + harness.sellerId() + "/2026/INV-2026-000099-" + "d".repeat(64) + ".xml";
    harness.archive().putDirectly(orphan, "<Invoice/>".getBytes(StandardCharsets.UTF_8));
    harness.moveClockForward(Duration.ofHours(1));
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_ACCESS_REFUSED, "status 401"));

    assertThatThrownBy(() -> harness.sweep(SETTINGS).sweep())
        .as("the pass still fails, so the sweeper WARNs and DEI-274 stays the health signal")
        .isInstanceOf(EInvoiceException.class);

    assertThat(harness.openFindings())
        .as("a check that needs no Stripe call is not skipped by a refused key")
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_ARCHIVE_MISSING);
              assertThat(f.subjectId()).isEqualTo(gone);
            })
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_ORPHAN_OBJECT);
              assertThat(f.subjectId()).isEqualTo(orphan);
            });
  }

  /**
   * Guard, green on c44435e. It pins the fix's other half: moving the Stripe list call to the end
   * would let an archive outage skip the missing-issuance check, whose window then moves past the
   * invoice (the D-PR50-B1 pattern on DEI-270).
   */
  @Test
  void guard_an_archive_outage_does_not_skip_the_missing_issuance_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String lost = "in_pr51e_lost_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(lost));
    harness.moveClockForward(Duration.ofHours(1));
    ReconciliationSweep sweep =
        new ReconciliationSweep(
            harness.source(),
            harness.store(),
            harness.inbound(),
            new DownArchive(),
            harness.findings(),
            harness.reprocessLedger(),
            harness.orphanCursors(),
            Clock.fixed(harness.now(), ZoneOffset.UTC),
            SETTINGS,
            harness.unitOfWork().configuration());

    assertThatThrownBy(sweep::sweep).isInstanceOf(EInvoiceException.class);

    assertThat(harness.openFindings())
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_MISSING_ISSUANCE);
              assertThat(f.subjectId()).isEqualTo(lost);
            });
  }

  /**
   * D-PR51-2 (INFO). {@code IssuanceReader} is public in 0.1.0. PR 51 adds the abstract method
   * {@code openOlderThan} with no default, so a host that implements the port no longer compiles
   * (this branch had to add it to the delegate in CipherProbePr11Test). Every earlier port method
   * in this CHANGELOG says so ("Implementers must add it." or a BREAKING CHANGE bullet); this one
   * does not, and the commit has no BREAKING CHANGE footer. Red on c44435e.
   */
  @Test
  void probe_the_changelog_does_not_tell_implementers_to_add_open_older_than() throws Exception {
    String changelog = Files.readString(Path.of("../CHANGELOG.md"), StandardCharsets.UTF_8);
    String unreleased = changelog.substring(0, changelog.indexOf("## [0.1.0]"));
    Matcher entry =
        Pattern.compile("(?s)\\n- \\*\\*.*?(?=\\n- \\*\\*|\\n### |\\z)").matcher(unreleased);
    String found = null;
    while (entry.find()) {
      if (entry.group().contains("IssuanceReader.openOlderThan")) {
        found = entry.group();
      }
    }
    assertThat(found).as("the CHANGELOG entry that adds the port method").isNotNull();
    assertThat(found.replaceAll("\\s+", " "))
        .as("a new abstract method on a released port tells implementers")
        .containsAnyOf("Implementers must add it", "BREAKING CHANGE");
  }

  /** An archive whose every read fails, as a mount that went away. */
  private static final class DownArchive implements ArchiveStore {

    private static EInvoiceException down() {
      return new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "probe: archive down");
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
