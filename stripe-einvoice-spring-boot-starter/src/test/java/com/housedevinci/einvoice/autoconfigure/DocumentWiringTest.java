package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.validation.En16931DocumentValidator;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the seller profile has to carry before this application may issue anything, and what it is
 * told when it does not.
 *
 * <p>Every refusal below happens at <b>startup</b> and names <b>a property</b>. The alternative is
 * a context that starts, allocates a legal number on the first real invoice, and then fails in a
 * worker thread with a rule id in German - by which point a number is consumed and an operator is
 * reading a stack trace instead of a configuration key.
 */
class DocumentWiringTest {

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EInvoiceDocumentAutoConfiguration.class))
        .withPropertyValues(seller())
        .withPropertyValues(properties);
  }

  /** A complete French seller: enough for Peppol, and enough for XRechnung but for BT-10. */
  private static String[] seller() {
    return new String[] {
      "einvoice.seller.id=docs",
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.seller.name=Atelier Riviere SAS",
      "einvoice.seller.vat-id=FR25900000019",
      "einvoice.seller.legal-id=900000019",
      "einvoice.seller.legal-id-scheme=0002",
      "einvoice.seller.electronic-address=FR25900000019",
      "einvoice.seller.electronic-address-scheme=9957",
      "einvoice.seller.address.line1=12 rue des Lilas",
      "einvoice.seller.address.city=Lyon",
      "einvoice.seller.address.postal-code=69003",
      "einvoice.seller.address.country=FR",
      "einvoice.seller.contact.name=Comptabilite",
      "einvoice.seller.contact.telephone=+33 4 72 00 00 00",
      "einvoice.seller.contact.email=factures@atelier-riviere.invalid",
      "einvoice.seller.payment.means-code=58",
      "einvoice.seller.payment.account-id=FR7630006000011234567890189"
    };
  }

  @Test
  void a_configured_seller_gets_a_writer_and_a_validator_for_the_chosen_profile() {
    runner("einvoice.documents.profile=peppol-bis-ubl")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DocumentRenderer.class);
              assertThat(context).hasSingleBean(DocumentValidator.class);
              assertThat(
                      ((En16931DocumentRenderer) context.getBean(DocumentRenderer.class)).profile())
                  .isEqualTo(UblProfile.PEPPOL_BIS_UBL);
              En16931DocumentValidator validator =
                  (En16931DocumentValidator) context.getBean(DocumentValidator.class);
              assertThat(validator.chain()).hasSize(2);
              assertThat(validator.processorAvailable()).isTrue();
            });
  }

  @Test
  void an_application_with_no_seller_name_gets_no_writer_and_keeps_the_numbering_api() {
    // A numbering-only host configures no seller identity. It must keep working exactly as it did
    // before documents existed, rather than failing on fields it has no reason to set.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EInvoiceDocumentAutoConfiguration.class))
        .withPropertyValues(
            "einvoice.seller.id=docs",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(DocumentRenderer.class);
              assertThat(context).doesNotHaveBean(DocumentValidator.class);
            });
  }

  @Test
  void a_host_supplied_writer_backs_ours_off() {
    runner()
        .withUserConfiguration(HostWriter.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(DocumentRenderer.class);
              assertThat(context.getBean(DocumentRenderer.class))
                  .isNotInstanceOf(En16931DocumentRenderer.class);
            });
  }

  @Test
  void an_unknown_profile_is_refused_and_the_message_names_the_ones_that_work() {
    runner("einvoice.documents.profile=factur-x")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.documents.profile is one of");
              assertThat(rootCause(context)).contains("xrechnung-ubl", "peppol-bis-ubl");
            });
  }

  @Test
  void xrechnung_without_a_buyer_reference_fails_startup_naming_the_property() {
    // BR-DE-15. The buyer reference comes from configuration and from nothing a buyer can write.
    runner("einvoice.documents.profile=xrechnung-ubl")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.documents.buyer-reference");
              assertThat(rootCause(context)).contains("BR-DE-15");
            });
  }

  @Test
  void xrechnung_with_a_buyer_reference_starts() {
    runner(
            "einvoice.documents.profile=xrechnung-ubl",
            "einvoice.documents.buyer-reference=04011000-1234512345-06")
        .run(context -> assertThat(context).hasSingleBean(DocumentRenderer.class));
  }

  @Test
  void xrechnung_without_a_seller_contact_fails_startup_naming_the_properties() {
    runner(
            "einvoice.documents.profile=xrechnung-ubl",
            "einvoice.documents.buyer-reference=PO-1",
            "einvoice.seller.contact.name=",
            "einvoice.seller.contact.telephone=",
            "einvoice.seller.contact.email=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.contact");
              assertThat(rootCause(context)).contains("BR-DE-2");
            });
  }

  @Test
  void xrechnung_without_payment_instructions_fails_startup_naming_the_properties() {
    runner(
            "einvoice.documents.profile=xrechnung-ubl",
            "einvoice.documents.buyer-reference=PO-1",
            "einvoice.seller.payment.account-id=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.payment.account-id");
              assertThat(rootCause(context)).contains("BR-DE-1");
            });
  }

  @Test
  void peppol_without_a_seller_electronic_address_fails_startup_naming_the_property() {
    // PEPPOL-EN16931-R020, and the scheme must be on the EAS code list (CL008). A seller with no
    // VAT identifier and no configured address has nothing to derive one from.
    runner(
            "einvoice.documents.profile=peppol-bis-ubl",
            "einvoice.seller.vat-id=",
            "einvoice.seller.electronic-address=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.electronic-address");
              assertThat(rootCause(context)).contains("PEPPOL-EN16931-R020");
            });
  }

  @Test
  void a_mistyped_seller_vat_identifier_fails_startup_and_the_message_carries_no_value() {
    // The seller's identifiers come from a properties file, which makes them no less capable of
    // being mistyped than a buyer's are of being hostile. FR25900000018 is one digit off.
    runner("einvoice.seller.vat-id=FR25900000018")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.vat-id");
              assertThat(rootCause(context)).doesNotContain("FR25900000018");
            });
  }

  @Test
  void a_mistyped_iban_fails_startup_rather_than_printing_a_payment_that_never_arrives() {
    runner("einvoice.seller.payment.account-id=FR7630006000011234567890188")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("mod-97");
              assertThat(rootCause(context)).doesNotContain("FR7630006000011234567890188");
            });
  }

  @Test
  void an_identifier_with_no_scheme_is_refused_because_nobody_could_resolve_it() {
    runner("einvoice.seller.legal-id-scheme=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.legal-id-scheme");
            });
  }

  @Test
  void no_xslt_processor_starts_the_context_and_refuses_every_document() {
    // Deliberate, and loud: the alternative is refusing to start, which would take the numbering
    // API down with it, or starting silently, which would archive documents no rule ever read.
    runner("einvoice.documents.xslt-processor=com.example.NoSuchProcessor")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              En16931DocumentValidator validator =
                  (En16931DocumentValidator) context.getBean(DocumentValidator.class);
              assertThat(validator.processorAvailable()).isFalse();
              // A document that is structurally perfect, so nothing but the missing processor can
              // decide the verdict. Handing it a stub would let a schema failure stand in for the
              // property under test.
              byte[] bytes =
                  new com.housedevinci.einvoice.adapter.xml.UblDocumentWriter(
                          UblProfile.PEPPOL_BIS_UBL)
                      .write(StarterDocuments.peppolInvoice());
              DocumentValidator.Report report = validator.validate(bytes, null);
              assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
              assertThat(report.archivable()).isFalse();
              assertThat(report.ruleId())
                  .isEqualTo(En16931DocumentValidator.NOT_EVALUATED_NO_PROCESSOR);
            });
  }

  @Test
  void a_validation_timeout_outside_its_bounds_is_refused_at_startup() {
    runner("einvoice.documents.validation-timeout=0s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.documents.validation-timeout");
            });
  }

  @Test
  void every_startup_refusal_carries_a_stable_code() {
    runner("einvoice.seller.address.country=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context)).contains("einvoice.seller.address.country");
              assertThat(codeOf(context)).isEqualTo(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
            });
  }

  private static String rootCause(
      org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
    Throwable failure = context.getStartupFailure();
    StringBuilder text = new StringBuilder();
    while (failure != null) {
      text.append(failure.getMessage()).append('\n');
      failure = failure.getCause();
    }
    return text.toString();
  }

  private static String codeOf(
      org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
    Throwable failure = context.getStartupFailure();
    while (failure != null) {
      if (failure instanceof com.housedevinci.einvoice.domain.EInvoiceException typed) {
        return typed.code();
      }
      failure = failure.getCause();
    }
    return "";
  }

  /** A host that brings its own format. The ports are public so nobody waits for us. */
  @Configuration(proxyBeanMethods = false)
  static class HostWriter {

    @Bean
    DocumentRenderer hostRenderer() {
      return input ->
          new DocumentRenderer.RenderedDocument(
              "<HostFormat/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "xml", "host");
    }
  }
}
