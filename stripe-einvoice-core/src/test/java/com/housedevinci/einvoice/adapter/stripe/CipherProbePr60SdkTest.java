package com.housedevinci.einvoice.adapter.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 60, D-PR60-6. Stripe.API_VERSION is a compile-time constant, so
 * SDK_API_VERSION is the version of the stripe-java this module was COMPILED against, not the one
 * on the host's classpath. Run: compile at 34.0.0, then surefire:test with
 * -Dstripe-java.version=34.1.0.
 */
class CipherProbePr60SdkTest {

  private static String runtimeSdkVersion() throws Exception {
    return (String) com.stripe.Stripe.class.getField("API_VERSION").get(null);
  }

  /** FAILS when the classpath SDK is not the compile-time one. */
  @Test
  void probe_the_pin_is_the_compiled_sdk_version_not_the_one_on_the_classpath() throws Exception {
    System.out.println(
        "PR60-SDK compiled=" + StripeApiInvoiceSource.SDK_API_VERSION + " runtime=" + runtimeSdkVersion());
    assertThat(StripeApiInvoiceSource.SDK_API_VERSION).isEqualTo(runtimeSdkVersion());
  }

  /**
   * With the compiled SDK on the classpath the source is built. With another one, it is refused
   * with CONFIG rather than built. Green in both states since the fix.
   */
  @Test
  void probe_a_source_whose_sdk_speaks_another_version_is_built_rather_than_refused()
      throws Exception {
    StripeClient client = StripeClient.builder().setApiKey("rk_test_" + "0".repeat(24)).build();
    if (StripeApiInvoiceSource.SDK_API_VERSION.equals(runtimeSdkVersion())) {
      assertThat(new StripeApiInvoiceSource(client, StripeApiInvoiceSource.SDK_API_VERSION))
          .isNotNull();
      return;
    }
    assertThatThrownBy(
            () -> new StripeApiInvoiceSource(client, StripeApiInvoiceSource.SDK_API_VERSION))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }
}
