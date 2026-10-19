package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.IssueDating;
import com.housedevinci.einvoice.domain.LegalNumber;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Render inputs for tests that call a renderer directly, without an allocation. The document is
 * dated on its supply day and carries that day as its delivery date (BT-72): the shape an invoice
 * numbered on the day it was finalised has.
 */
public final class DocumentInputs {

  private DocumentInputs() {}

  public static DocumentInput numberedOnSupplyDay(MappingInput mapping, LegalNumber number) {
    return new DocumentInput(
        mapping,
        number,
        mapping.seriesKey(),
        new IssueDating(
            mapping.supplyDate(),
            mapping.supplyDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
            Optional.of(mapping.supplyDate()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(com.housedevinci.einvoice.domain.DateGapCause.NONE)));
  }

  /** The same render input over another invoice, with the same number and dates. */
  public static DocumentInput reinput(DocumentInput base, SourceInvoice invoice) {
    return new DocumentInput(
        new MappingInput(
            invoice,
            base.unnumbered().seriesKey(),
            base.unnumbered().supplyDate(),
            base.rulePackVersion()),
        base.legalNumber(),
        base.seriesKey(),
        base.dating());
  }
}
