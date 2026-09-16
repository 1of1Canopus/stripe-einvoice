package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.util.Locale;
import java.util.Optional;

/**
 * BG-16, the payment instructions. Mandatory for XRechnung (BR-DE-1), optional elsewhere.
 *
 * <p>The account identifier is validated as an IBAN when the means code says a credit transfer is
 * meant, including the ISO 7064 mod-97 check: an IBAN with a transposed pair is a payment that does
 * not arrive, printed on a legal document by us.
 *
 * @param meansCode BT-81, UNTDID 4461. 58 is SEPA credit transfer, 30 a plain credit transfer, 57 a
 *     standing order, 59 a SEPA direct debit, 48 a card, 97 a clearing between partners.
 * @param accountIdentifier BT-84, the IBAN or other account identifier
 * @param accountName BT-85, optional
 * @param serviceProviderIdentifier BT-86, the BIC, optional
 */
public record PaymentInstruction(
    String meansCode,
    String accountIdentifier,
    String accountName,
    String serviceProviderIdentifier) {

  /** The means codes for which BT-84 is read as an IBAN. */
  private static final java.util.Set<String> IBAN_MEANS = java.util.Set.of("30", "58", "59");

  public PaymentInstruction {
    meansCode = ScreenedText.screen("payment means code", meansCode, 4);
    for (int i = 0; i < meansCode.length(); i++) {
      char c = meansCode.charAt(i);
      if (c < '0' || c > '9') {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED,
            "a payment means code is a UNTDID 4461 numeric code, for example 58 for a SEPA credit"
                + " transfer");
      }
    }
    accountIdentifier =
        ScreenedText.screen(
            "payment account identifier", accountIdentifier, BusinessTerms.PAYMENT_ACCOUNT);
    if (IBAN_MEANS.contains(meansCode)) {
      accountIdentifier = requireIban(accountIdentifier);
    }
    accountName =
        accountName == null || accountName.isBlank()
            ? null
            : ScreenedText.screen(
                "payment account name", accountName, BusinessTerms.PAYMENT_ACCOUNT);
    serviceProviderIdentifier =
        serviceProviderIdentifier == null || serviceProviderIdentifier.isBlank()
            ? null
            : ScreenedText.screen(
                "payment service provider identifier", serviceProviderIdentifier, 16);
  }

  public Optional<String> accountNameValue() {
    return Optional.ofNullable(accountName);
  }

  public Optional<String> serviceProviderIdentifierValue() {
    return Optional.ofNullable(serviceProviderIdentifier);
  }

  /**
   * The ISO 13616 shape and the ISO 7064 mod-97 check digit.
   *
   * @throws EInvoiceException {@link ErrorCodes#IDENTIFIER_MALFORMED} on either
   */
  private static String requireIban(String value) {
    String iban = value.replace(" ", "").toUpperCase(Locale.ROOT);
    if (iban.length() < 15 || iban.length() > 34) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "an IBAN is between 15 and 34 characters (property: einvoice.seller.payment.account-id)");
    }
    for (int i = 0; i < iban.length(); i++) {
      char c = iban.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
      if (!ok) {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED, "an IBAN is letters and digits only");
      }
    }
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    java.math.BigInteger numeric = java.math.BigInteger.ZERO;
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      int digit = c >= 'A' ? c - 'A' + 10 : c - '0';
      numeric =
          numeric
              .multiply(java.math.BigInteger.valueOf(digit > 9 ? 100 : 10))
              .add(java.math.BigInteger.valueOf(digit));
    }
    if (!numeric.mod(java.math.BigInteger.valueOf(97)).equals(java.math.BigInteger.ONE)) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "an IBAN that fails its own mod-97 check is a payment that does not arrive, printed on a"
              + " legal document (property: einvoice.seller.payment.account-id)");
    }
    return iban;
  }

  @Override
  public String toString() {
    // Never the account identifier: it is the seller's bank account.
    return "PaymentInstruction[meansCode=" + meansCode + "]";
  }
}
