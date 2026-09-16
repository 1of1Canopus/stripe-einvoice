package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * One Stripe event as this module durably recorded it, before any routing decision was taken
 * (I-01).
 *
 * <p>The row is the audit trail of what arrived and what we did with it. It is deliberately
 * <b>not</b> under the append-only triggers that protect the issuance ledger, and it <b>is</b>
 * purgeable: the issuance row is legal evidence, this is a transport artifact carrying a full
 * invoice payload - buyer name, address, email, tax id, line descriptions - and a table nobody can
 * ever delete from would make the module a permanent copy of every buyer's details (I-07).
 *
 * @param bodySha256 kept after the body itself is nulled, so what arrived stays provable
 * @param bodyPresent whether the raw body is still on the row; see {@link InboundState#keepsBody()}
 */
public record InboundEvent(
    String eventId,
    String type,
    String objectId,
    String apiVersion,
    boolean livemode,
    String stripeAccountId,
    String sellerId,
    Mode mode,
    String signatureKeyId,
    Instant receivedAt,
    Instant updatedAt,
    InboundState state,
    int attempts,
    Instant nextAttemptAt,
    String lastCode,
    String bodySha256,
    boolean bodyPresent) {

  public InboundEvent {
    Identifiers.validate("stripe event id", eventId);
    Identifiers.validate("stripe event type", type, EventIdentity.MAX_TYPE_CHARS);
    Identifiers.validate("stripe object id", objectId);
    if (state == null || mode == null || receivedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an inbound event needs a state, a mode and an arrival time");
    }
    apiVersion = apiVersion == null ? "" : apiVersion;
    stripeAccountId = stripeAccountId == null ? "" : stripeAccountId;
    sellerId = sellerId == null ? "" : sellerId;
    signatureKeyId = signatureKeyId == null ? "" : signatureKeyId;
    lastCode = lastCode == null ? "" : lastCode;
    bodySha256 = bodySha256 == null ? "" : bodySha256;
    updatedAt = updatedAt == null ? receivedAt : updatedAt;
  }

  /** The first record of a verified event: everything else about it is still undecided. */
  public static InboundEvent received(
      EventIdentity identity, Mode mode, String signatureKeyId, byte[] body, Instant now) {
    return new InboundEvent(
        identity.eventId(),
        identity.type(),
        identity.objectId(),
        identity.apiVersion(),
        identity.livemode(),
        identity.accountId(),
        "",
        mode,
        signatureKeyId,
        Timestamps.toStorage(now),
        Timestamps.toStorage(now),
        InboundState.RECEIVED,
        0,
        Timestamps.toStorage(now),
        "",
        Hashes.sha256Hex(body),
        true);
  }

  public Optional<Instant> nextAttempt() {
    return Optional.ofNullable(nextAttemptAt);
  }
}
