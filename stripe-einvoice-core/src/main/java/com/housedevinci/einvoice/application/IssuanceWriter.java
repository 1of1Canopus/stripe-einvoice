package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;

/**
 * The two database phases of the two-store write (design §5, P3 and P4), and the failure states.
 *
 * <p>P3 writes {@code ARCHIVING} with the already-computed content hash and archive key
 * <b>before</b> the object store is touched (I-05), so every object in the archive has a row that
 * predicted it: orphan detection is an indexed query rather than a bucket walk, and a crash between
 * the write and the commit is attributable rather than inferred.
 *
 * <p>P4 writes {@code ISSUED} and appends the chained disposition in one transaction. Only {@code
 * ISSUED} means a legal document exists.
 */
public interface IssuanceWriter {

  /** P3: record what we are about to write, with its hash and its key, both write-once. */
  Issuance markArchiving(
      String sellerId, Mode mode, String stripeInvoiceId, String documentSha256, ArchiveKey key);

  /** P4: the document exists. Appends the chained disposition in the same transaction. */
  Issuance markIssued(String sellerId, Mode mode, String stripeInvoiceId);

  /**
   * A failure state on the issuance row. {@code FAILED_ARCHIVE} is retryable up to the ceiling;
   * {@code FAILED_VALIDATION} is not.
   *
   * <p>{@code FAILED_VALIDATION} appends a chained event carrying {@code ruleId} - the failing
   * schematron rule, or the render refusal's code - in the same transaction (D7-03). Without it the
   * gap that a burned number leaves in the issued sequence had no explanation in the tamper-evident
   * record, only on a row that can legitimately change. {@code FAILED_ARCHIVE} is not chained: it
   * is retryable rather than a disposition, and its eventual fate - {@code ISSUED} or {@code
   * VOID_UNUSED} - is chained.
   */
  Issuance markFailed(
      String sellerId, Mode mode, String stripeInvoiceId, IssuanceState state, String ruleId);
}
