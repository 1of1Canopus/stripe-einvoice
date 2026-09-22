package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.NumberVoider;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records that an allocated number will never carry a document. <b>Privileged.</b>
 *
 * <p>This module ships <b>no HTTP endpoint</b> for it, and never will (N-04). It is a library: it
 * cannot authenticate anyone, and an auto-configured void endpoint would hand an unauthenticated
 * caller a way to burn a numbering series one number at a time. If a host application wants to
 * expose voiding, it writes that endpoint itself, behind its own authorization - the same way it
 * would expose any other privileged accounting operation.
 *
 * <p>What is recorded: the reason, screened and length-bounded, the failing validation rule id when
 * there was one, and a chained event. Nothing is deleted, and the number is never re-allocated.
 *
 * <p><b>A void is irreversible, and unrecoverable inside this module.</b> One Stripe invoice maps
 * to one number for all time, so this ends the module's involvement with that sale: every later
 * event for the invoice is recorded terminally with {@code DEI-122}, the reconciliation sweep
 * reports it under {@code DEI-278} instead of re-enqueueing it, and no path allocates a second
 * number. An operator who voids by mistake cannot undo it here. The remedy is upstream - correct
 * the data, void the Stripe invoice, raise a new one - and when the sale is already paid that
 * remedy needs a credit note, which this edition does not produce. Say so in whatever admin action
 * you wire this to, because the operator reads that and not this file.
 */
public class IssuanceVoidService {

  private static final Logger log = LoggerFactory.getLogger(IssuanceVoidService.class);

  private final NumberVoider voider;
  private final EInvoiceProperties properties;

  public IssuanceVoidService(NumberVoider voider, EInvoiceProperties properties) {
    this.voider = voider;
    this.properties = properties;
  }

  /**
   * @param reason mandatory, screened, at most {@link VoidRequest#MAX_REASON_CHARS} characters
   * @param ruleId the validation rule that failed, or blank
   */
  public Issuance voidUnused(String stripeInvoiceId, String reason, String ruleId) {
    Mode mode = Mode.of(properties.getMode());
    Issuance voided =
        voider.voidUnused(
            new VoidRequest(properties.getSeller().getId(), mode, stripeInvoiceId, reason, ruleId));
    // Ids and the number only. The reason is operator-supplied text on a path that also carries
    // buyer-adjacent context, and a log line is a copy that outlives the ledger (checklist 45).
    log.info(
        "einvoice: number {} voided unused (seller={} mode={} rule={})",
        voided.legalNumber().value(),
        voided.seriesKey().sellerId(),
        mode.wire(),
        voided.voidedRuleId().orElse("-"));
    return voided;
  }
}
