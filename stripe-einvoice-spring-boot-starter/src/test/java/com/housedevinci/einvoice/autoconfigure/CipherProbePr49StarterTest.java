package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Security review, PR 49 pass 1 (one run per invoice). Install under
 * stripe-einvoice-spring-boot-starter/src/test/java/com/housedevinci/einvoice/autoconfigure/.
 *
 * <p>D-PR49-2. The pool rule bean {@code einvoiceRunPoolRule} carries
 * {@code @ConditionalOnBean(IssuanceUnitOfWork.class)} and is declared in the same configuration
 * class BEFORE {@code einvoiceIssuanceUnitOfWork}. The condition is evaluated when the rule's
 * definition is registered, before the unit of work's is, so it is false and the rule never exists
 * in a started context. RunPoolRuleTest calls the bean method by hand, so it never sees this. The
 * shared test pool has 8 connections: concurrency 3 needs 3 x 3 + 2 = 11 and must refuse startup.
 */
class CipherProbePr49StarterTest {

  private static ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(IssuanceWiringTest.Ports.class)
        .withPropertyValues(
            "einvoice.seller.id=pr49",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root=" + System.getProperty("java.io.tmpdir") + "/einvoice-pr49",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withPropertyValues(properties);
  }

  @Test
  void probe_the_pool_rule_exists_in_a_started_context() {
    runner()
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                    .hasBean("einvoiceRunPoolRule"));
  }

  @Test
  void probe_concurrency_three_on_a_pool_of_eight_refuses_a_started_context() {
    runner("einvoice.issuance.concurrency=3").run(context -> assertThat(context).hasFailed());
  }
}
