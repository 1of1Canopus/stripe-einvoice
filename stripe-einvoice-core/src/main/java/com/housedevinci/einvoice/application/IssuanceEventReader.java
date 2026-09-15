package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.IssuanceEvent;
import java.util.List;

/** Pages the chained issuance log in sequence order, for the verifier and for an auditor export. */
public interface IssuanceEventReader {

  List<IssuanceEvent> readAfter(long sequenceExclusive, int limit);

  /** Issuance rows whose disposition is settled, for the verifier's cross-check. */
  List<DisposedIssuance> disposedIssuances();

  /**
   * The current state of a disposed issuance row, as the mapping table holds it.
   *
   * @param legalNumber the rendered number, which is what the log and the row must agree on
   */
  record DisposedIssuance(String sellerId, String mode, String legalNumber, String state) {}
}
