package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Security review of the date-order change (D-PS1-27), pass 1. D-PR36-4: the supply date is
 * promised on a renderer's word only. A renderer that answers {@code carriesSupplyDate() == true}
 * and leaves the field out produces bytes that the module's own validator passes as archivable, so
 * an invoice dated on its numbering day is issued with no trace of when the sale happened.
 */
class CipherProbePr36DocumentTest {

  @Test
  void probe_a_document_that_drops_its_supply_date_is_not_archivable() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    assertThat(input.supplyDate()).isPresent();
    byte[] rendered =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(input)
            .bytes();
    // What a host renderer that declares the capability but forgets the field writes.
    byte[] dropped =
        new String(rendered, StandardCharsets.UTF_8)
            .replaceAll("<cac:Delivery>.*?</cac:Delivery>", "")
            .getBytes(StandardCharsets.UTF_8);

    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      assertThat(validator.validate(dropped, input).archivable()).isFalse();
    }
  }

  @Test
  void probe_a_document_dated_other_than_its_numbering_day_is_not_archivable() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    byte[] rendered =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(input)
            .bytes();
    String xml = new String(rendered, StandardCharsets.UTF_8);
    // A 0.1.x-style renderer: BT-2 written as the supply day instead of the numbering day.
    String supply = input.supplyDate().orElseThrow().toString();
    byte[] backdated =
        xml.replaceFirst(
                "<cbc:IssueDate>" + input.issueDate() + "</cbc:IssueDate>",
                "<cbc:IssueDate>" + input.issueDate().minusDays(7) + "</cbc:IssueDate>")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(new String(backdated, StandardCharsets.UTF_8))
        .contains("<cbc:IssueDate>" + input.issueDate().minusDays(7));
    assertThat(supply).isNotEmpty();

    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      assertThat(validator.validate(backdated, input).archivable()).isFalse();
    }
  }
}
