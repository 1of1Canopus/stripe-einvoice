package com.housedevinci.einvoice.domain;

import java.util.Optional;

/**
 * The issuance log's head as the store last wrote it, in a separate row from the log itself, so
 * trimming the tail or truncating the table leaves a mismatch the verifier reports.
 */
public interface IssuanceAnchor {

  /**
   * @param headHash hash of the newest row
   * @param rowCount number of rows in the log
   * @param keyed whether this log is keyed from row 1 or unkeyed forever. Set once, at the first
   *     append, and immutable afterwards: it is the external, attacker-unwritable record of what
   *     every row's {@code chain_version} ought to be, because that column is itself part of what a
   *     table-owning attacker rewrites.
   */
  record Anchor(String headHash, long rowCount, boolean keyed) {}

  Optional<Anchor> anchor();
}
