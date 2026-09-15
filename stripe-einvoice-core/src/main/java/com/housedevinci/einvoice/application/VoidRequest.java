package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.ScreenedText;

/**
 * An operator's decision that an allocated number will never carry a document.
 *
 * <p>The reason is mandatory and is screened like every other free-text value that reaches a
 * chained row and an auditor-facing report (N-04): it is not ours, it is not trusted, and it is
 * never interpolated into anything.
 *
 * @param ruleId the validation rule whose failure caused this void, when there was one. Its rate is
 *     the honest measure of how close to gap-free this module runs, so it is recorded rather than
 *     left to be inferred from prose.
 */
public record VoidRequest(
    String sellerId, Mode mode, String stripeInvoiceId, String reason, String ruleId) {

  /** The reason's bound. Long enough for a sentence, short enough to stay a reason. */
  public static final int MAX_REASON_CHARS = 500;

  public VoidRequest {
    Identifiers.validate("seller id", sellerId, 64);
    Identifiers.validate("stripe invoice id", stripeInvoiceId);
    if (mode == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a void needs the mode it applies to");
    }
    reason = ScreenedText.screen("void reason", reason, MAX_REASON_CHARS);
    ruleId =
        ruleId == null || ruleId.isBlank() ? "" : Identifiers.validate("void rule id", ruleId, 64);
  }
}
