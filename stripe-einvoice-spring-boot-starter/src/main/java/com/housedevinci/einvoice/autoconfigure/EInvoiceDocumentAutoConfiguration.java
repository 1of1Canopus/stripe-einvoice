package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.validation.En16931DocumentValidator;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The EN 16931 writer and the official validator chain.
 *
 * <p><b>Its own auto-configuration, ordered before the issuance one, on purpose.</b> The issuance
 * pipeline is {@code @ConditionalOnBean} on these two, and {@code @ConditionalOnBean} inside a
 * single configuration class depends on the order the bean methods happen to be evaluated in, which
 * is not the order they are written in. Declaring them here, with {@code before}, makes the
 * dependency an ordering the framework guarantees rather than one that held on the machine it was
 * tried on.
 */
@AutoConfiguration(before = EInvoiceIssuanceAutoConfiguration.class)
@EnableConfigurationProperties(EInvoiceProperties.class)
public class EInvoiceDocumentAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(EInvoiceDocumentAutoConfiguration.class);

  /**
   * The EN 16931 writer, and the seller profile it writes from.
   *
   * <p>The profile is built and <b>fully validated here</b>, at startup: a seller whose
   * configuration cannot produce a document under the chosen profile fails the context by property
   * name, rather than on the first real invoice, after a number has been allocated.
   *
   * <p>Conditional on {@code einvoice.seller.name}, which is the one field no document can be
   * written without. A host that only wants the numbering API configures no seller identity, gets
   * no renderer, and keeps exactly the behaviour it had before this increment; a host that
   * configured intake but no seller identity is caught by {@link IssuanceIntakeWiringCheck}, which
   * names the property rather than leaving Stripe posting to a 404 for three days.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "einvoice.seller", name = "name")
  public DocumentRenderer einvoiceDocumentRenderer(EInvoiceProperties properties) {
    UblProfile profile = UblProfile.of(properties.getDocuments().getProfile());
    log.info(
        "einvoice: documents are rendered as {} and judged by the vendored official rules for it",
        profile.displayName());
    return new En16931DocumentRenderer(
        SellerProfiles.build(properties, profile),
        profile,
        SellerProfiles.buyerElectronicAddress(properties));
  }

  /**
   * The official validator chain for the same profile.
   *
   * <p>It is a {@code @Bean} with a destroy method because it owns a bounded worker pool: a
   * validation that runs past its wall-clock bound is abandoned and reported NOT_EVALUATED, which
   * the unit of work treats as a refusal, and the pool must not outlive the context.
   *
   * <p>If no XSLT 2.0 processor is on the classpath this bean still starts, and every validation
   * reports NOT_EVALUATED. That is deliberate and it is loud: the startup check below says so by
   * name, and an application in that state issues <em>nothing</em> rather than archiving a document
   * no rule ever read.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "einvoice.seller", name = "name")
  public DocumentValidator einvoiceDocumentValidator(EInvoiceProperties properties) {
    EInvoiceProperties.Documents documents = properties.getDocuments();
    UblProfile profile = UblProfile.of(documents.getProfile());
    En16931DocumentValidator validator =
        new En16931DocumentValidator(
            profile,
            documents.getXsltProcessor(),
            documents.getValidationTimeout(),
            documents.getValidationConcurrency());
    if (validator.processorAvailable()) {
      log.info(
          "einvoice: the {} rules will run under {}; {} vendored stylesheets, checksums verified"
              + " before each is compiled",
          profile.displayName(),
          documents.getXsltProcessor(),
          validator.chain().size());
    } else {
      log.warn(
          "einvoice: no XSLT 2.0 processor named {} is on the classpath, so the EN 16931 rules"
              + " cannot run and THIS APPLICATION WILL ISSUE NO INVOICE. Add a processor to the"
              + " application (see the documents page) or set"
              + " einvoice.documents.xslt-processor to one that is present. Nothing is archived"
              + " unvalidated: an unevaluated rule is not a passed rule.",
          documents.getXsltProcessor());
    }
    return validator;
  }
}
