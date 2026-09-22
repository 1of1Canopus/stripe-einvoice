package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Issuance;

/**
 * Records that an allocated number will never carry a document.
 *
 * <p>This is the one operation that can consume a number without producing a legal document, so it
 * is deliberately narrow (N-04):
 *
 * <ul>
 *   <li>It is a <b>service method, never an HTTP endpoint</b>. This module is a library and cannot
 *       authenticate anyone (Decision 3); a module that auto-configured a void endpoint would hand
 *       an unauthenticated caller a way to burn a series. The host application protects it.
 *   <li>It is legal only from {@code NUMBERED}, {@code ARCHIVING} or {@code FAILED_VALIDATION}, and
 *       is refused from {@code ISSUED} in two independent places - the enum and the database
 *       trigger - because voiding an issued number is an attempt to unpublish a legal document.
 *   <li>It <b>deletes nothing</b> and re-allocates nothing: the number stays in the series report
 *       forever, with its reason, chained.
 * </ul>
 *
 * *
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
public interface NumberVoider {

  Issuance voidUnused(VoidRequest request);
}
