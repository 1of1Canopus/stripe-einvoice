package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssueDating;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SupplyPeriod;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * What a renderer and a validator are given: the {@link MappingInput} the preflight already
 * approved, plus what the allocator fixed under the series lock - the legal number, its series and
 * its dates.
 *
 * <p>The composition is the point. The render input <b>is</b> the preflight input plus the
 * allocation, so "both passes saw the same invoice" is a fact about the types. The issue date
 * (BT-2) is not in the preflight input at all: it is the numbering day, and it exists only once a
 * number does (D-PS1-27).
 *
 * @param unnumbered everything that existed before the number did
 * @param legalNumber BT-1
 * @param seriesKey the series the number was given in, with the fiscal year of its issue day
 * @param dating BT-2 and the supply date or period, as the allocator recorded them
 */
public record DocumentInput(
    MappingInput unnumbered, LegalNumber legalNumber, SeriesKey seriesKey, IssueDating dating) {

  public DocumentInput {
    if (unnumbered == null || legalNumber == null || seriesKey == null || dating == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "a document needs a mapping input, a legal number, its series and its dates");
    }
  }

  /**
   * The render input for an allocated number. A row written before 0.2.0 carries no dating; its
   * BT-2 was {@code finalized_at} in the tax zone, and that is what it keeps when it is resumed,
   * with the same day as its supply date.
   */
  public static DocumentInput numbered(MappingInput mapping, Issuance issuance, ZoneId taxZone) {
    IssueDating dating =
        issuance
            .dating()
            .orElseGet(
                () -> {
                  LocalDate legacy = issuance.issueDateIn(taxZone);
                  return new IssueDating(
                      legacy,
                      issuance.allocatedAt(),
                      Optional.of(legacy),
                      Optional.empty(),
                      Optional.empty(),
                      Optional.empty(),
                      Optional.empty());
                });
    return new DocumentInput(mapping, issuance.legalNumber(), issuance.seriesKey(), dating);
  }

  public SourceInvoice invoice() {
    return unnumbered.invoice();
  }

  /** BT-2: the day the number was given, in the seller's tax zone (D-PS1-27). */
  public LocalDate issueDate() {
    return dating.issueDate();
  }

  /** BT-72, a proxy for the supply date: the day of {@code finalized_at} in the tax zone. */
  public Optional<LocalDate> supplyDate() {
    return dating.supplyDate();
  }

  /** BG-14, when every line names the same service period. */
  public Optional<SupplyPeriod> supplyPeriod() {
    return dating.supplyPeriod();
  }

  /**
   * Recorded on the issuance and inside the hashed material, so a re-validation years from now uses
   * the pack that was in force.
   */
  public String rulePackVersion() {
    return unnumbered.rulePackVersion();
  }
}
