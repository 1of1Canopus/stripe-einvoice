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
 */
public interface NumberVoider {

  Issuance voidUnused(VoidRequest request);
}
