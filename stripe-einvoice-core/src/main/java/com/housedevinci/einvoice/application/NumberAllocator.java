package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Issuance;

/**
 * Allocates the legal number, and binds it to one Stripe invoice for all time.
 *
 * <p>The contract every implementation owes, in the order it matters:
 *
 * <ol>
 *   <li><b>Resume, never re-allocate.</b> A request for a Stripe invoice that already has a number
 *       returns that number, in whatever state the issuance is. Only a miss reaches the counter.
 *   <li><b>One transaction.</b> The counter increment and the issuance insert commit together, so a
 *       rollback or a crash leaves the counter where it was. No {@code SEQUENCE}, no {@code
 *       nextval}, no {@code @GeneratedValue}: those are non-transactional by design and every
 *       failed attempt would leave a permanent hole (D-03, checklist line 23).
 *   <li><b>A backstop that is a constraint, not a hope.</b> Two threads that miss together race to
 *       insert; the loser's unique violation becomes {@link
 *       com.housedevinci.einvoice.domain.ErrorCodes#ISSUANCE_ALREADY_CLAIMED} and its counter
 *       increment rolls back with it, so no gap.
 * </ol>
 */
public interface NumberAllocator {

  Issuance allocate(AllocationRequest request);
}
