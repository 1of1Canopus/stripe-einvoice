package com.housedevinci.einvoice.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The only six things this module ever reads from a webhook body (D-02).
 *
 * <p>Everything that reaches a document is re-fetched from the Stripe API: the payload's embedded
 * object is a paginated, partially expanded copy, and mapping it produces documents that balance
 * while itemising a fraction of the sale. So the body yields an identity and a routing decision,
 * and nothing else - a test asserts no mapper can reach it.
 *
 * @param eventId Stripe's own event id: the idempotency key for everything downstream
 * @param type the event type, matched against the two this module subscribes to
 * @param apiVersion checked against the pin; a skew is recorded and refused, never deserialised
 *     leniently (D-02)
 * @param livemode part of the routing decision, never metadata (D-01)
 * @param accountId the connected account, or blank for the platform account
 * @param objectId the id of the object to re-fetch
 */
public record EventIdentity(
    String eventId,
    String type,
    String apiVersion,
    boolean livemode,
    String accountId,
    String objectId) {

  /** Long enough for {@code 2026-01-01.preview-feature}, short enough to stay a version. */
  public static final int MAX_TYPE_CHARS = 64;

  public static final int MAX_API_VERSION_CHARS = 64;

  public EventIdentity {
    Identifiers.validate("stripe event id", eventId);
    Identifiers.validate("stripe event type", type, MAX_TYPE_CHARS);
    Identifiers.validate("stripe object id", objectId);
    accountId =
        accountId == null || accountId.isBlank()
            ? ""
            : Identifiers.validate("stripe account id", accountId);
    apiVersion =
        apiVersion == null || apiVersion.isBlank()
            ? ""
            : Identifiers.validate("stripe api version", apiVersion, MAX_API_VERSION_CHARS);
  }

  /**
   * Reads the identity out of a signature-verified body with the strict reader (I-12).
   *
   * <p>A body that gets this far is provably from Stripe, so a failure here is "not readable at
   * all" rather than "not ours": the caller answers 400 and writes nothing, because a record keyed
   * on an id we could not read is a record we cannot attribute.
   */
  public static EventIdentity from(byte[] body) {
    Map<String, Object> event = StrictJson.parseObject(body);
    String eventId = required(StrictJson.stringAt(event, "id"), "id");
    String type = required(StrictJson.stringAt(event, "type"), "type");
    String objectId =
        required(StrictJson.stringAt(event, "data", "object", "id"), "data.object.id");
    boolean livemode =
        StrictJson.booleanAt(event, "livemode")
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.INBOUND_UNREADABLE,
                        "the event carries no boolean 'livemode', which is part of the routing"
                            + " decision and cannot be defaulted (D-01)"));
    String account = StrictJson.stringAt(event, "account").orElse("");
    String apiVersion = StrictJson.stringAt(event, "api_version").orElse("");
    try {
      return new EventIdentity(eventId, type, apiVersion, livemode, account, objectId);
    } catch (EInvoiceException invalid) {
      // An identifier that will not go in a bind parameter is an unreadable body, not a business
      // outcome: same 400, same "nothing durable is written".
      throw new EInvoiceException(ErrorCodes.INBOUND_UNREADABLE, invalid.getMessage());
    }
  }

  private static String required(Optional<String> value, String field) {
    return value.orElseThrow(
        () ->
            new EInvoiceException(
                ErrorCodes.INBOUND_UNREADABLE,
                "the event carries no string '" + field + "', so it cannot be attributed"));
  }
}
