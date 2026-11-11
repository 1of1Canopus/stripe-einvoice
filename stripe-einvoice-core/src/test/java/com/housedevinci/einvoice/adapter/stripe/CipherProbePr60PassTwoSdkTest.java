package com.housedevinci.einvoice.adapter.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.stripe.StripeClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 60, pass two, D-PR60-9. The classpath check is tested only as a static
 * method. Nothing on the build classpath shows that the constructor calls it, or that a failed read
 * refuses. Both probes need the seam the fix names; they fail until it exists.
 */
class CipherProbePr60PassTwoSdkTest {

  /** A stripe-java without the constant, with another type, an instance field, a null. */
  static final class NoField {}

  static final class IntegerField {
    public static final Integer API_VERSION = 7;
  }

  static final class InstanceField {
    public final String API_VERSION = StripeApiInvoiceSource.SDK_API_VERSION;
  }

  static final class NullField {
    public static String API_VERSION = null;
  }

  /** FAILS until the constructor takes the runtime version through a seam the test can reach. */
  @Test
  void probe_a_source_over_another_classpath_sdk_is_refused_on_the_build_classpath()
      throws Exception {
    Constructor<StripeApiInvoiceSource> seam =
        StripeApiInvoiceSource.class.getDeclaredConstructor(
            StripeClient.class, String.class, Duration.class, String.class);
    seam.setAccessible(true);
    StripeClient client = StripeClient.builder().setApiKey("rk_test_" + "0".repeat(24)).build();
    assertThatThrownBy(
            () ->
                seam.newInstance(
                    client,
                    StripeApiInvoiceSource.SDK_API_VERSION,
                    Duration.ofDays(30),
                    "2026-11-30.fig"))
        .isInstanceOf(InvocationTargetException.class)
        .cause()
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  /** FAILS until the read takes the class as a parameter. Every unreadable shape refuses. */
  @Test
  void probe_an_unreadable_classpath_sdk_is_refused() throws Exception {
    Method read = StripeApiInvoiceSource.class.getDeclaredMethod("sdkApiVersionOf", Class.class);
    read.setAccessible(true);
    for (Class<?> shape :
        List.of(NoField.class, IntegerField.class, InstanceField.class, NullField.class)) {
      String runtime = (String) read.invoke(null, shape);
      assertThat(runtime)
          .as(shape.getSimpleName())
          .isNotEqualTo(StripeApiInvoiceSource.SDK_API_VERSION);
      assertThatThrownBy(
              () ->
                  StripeApiInvoiceSource.requireClasspathSdk(
                      StripeApiInvoiceSource.SDK_API_VERSION, runtime))
          .as(shape.getSimpleName())
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }
}
