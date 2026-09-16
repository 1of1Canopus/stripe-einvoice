package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.xml.UblDocumentWriter;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import com.housedevinci.einvoice.domain.en16931.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Credit notes: the type codes and the sign convention, and nothing beyond them (D-13).
 *
 * <p>A refund is a 381 with <b>positive</b> amounts. It is not a 380 with negative ones - several
 * profiles reject negative line amounts outright, and a document encoded that way reconciles
 * arithmetically at our end and means nothing legally at the recipient's.
 */
class CreditNoteValidationTest {

  @Test
  void a_credit_note_is_a_credit_note_document_with_positive_amounts() {
    EnInvoice creditNote = DocumentFixtures.creditNote(DocumentFixtures.germanSeller());
    byte[] bytes = new UblDocumentWriter(UblProfile.XRECHNUNG_UBL).write(creditNote);
    String xml = new String(bytes, StandardCharsets.UTF_8);
    assertThat(xml).startsWith("<CreditNote ");
    assertThat(xml).contains("<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>");
    assertThat(xml).doesNotContain(">-");
    // BT-25: the invoice this one corrects, or it corrects nothing anybody can find.
    assertThat(xml).contains("<cac:BillingReference>");
    assertThat(xml).contains(DocumentFixtures.NUMBER.value());
  }

  @Test
  void a_credit_note_is_clean_under_both_official_chains() {
    EnInvoice creditNote = DocumentFixtures.creditNote(DocumentFixtures.germanSeller());
    for (UblProfile profile : UblProfile.values()) {
      byte[] bytes = new UblDocumentWriter(profile).write(creditNote);
      try (En16931DocumentValidator validator =
          new En16931DocumentValidator(
              profile, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2)) {
        En16931DocumentValidator.Detail report = validator.validateInDetail(bytes, true);
        assertThat(report.findings())
            .as("%s findings on the credit note", profile.displayName())
            .allMatch(finding -> !finding.fatal());
        assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.PASSED);
      }
    }
  }

  @Test
  void a_negative_total_on_an_invoice_type_is_refused() {
    assertThatThrownBy(
            () ->
                DocumentTypeCode.COMMERCIAL_INVOICE.requireSignConvention(
                    Money.parse("-1.00", "EUR")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED);
  }

  @Test
  void a_corrective_type_without_the_document_it_corrects_is_refused() {
    EnInvoice creditNote = DocumentFixtures.creditNote(DocumentFixtures.germanSeller());
    assertThatThrownBy(
            () ->
                new EnInvoice(
                    creditNote.number(),
                    creditNote.issueDate(),
                    null,
                    DocumentTypeCode.CORRECTED_INVOICE,
                    creditNote.currency(),
                    creditNote.buyerReferenceValue().orElse(null),
                    null,
                    null,
                    null,
                    null,
                    creditNote.seller(),
                    creditNote.buyer(),
                    creditNote.payment(),
                    creditNote.lines(),
                    creditNote.taxSubtotals(),
                    creditNote.lineTotal(),
                    creditNote.taxExclusiveTotal(),
                    creditNote.taxTotal(),
                    creditNote.taxInclusiveTotal(),
                    creditNote.payableTotal()))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("it corrects nothing anyone can find");
  }
}
