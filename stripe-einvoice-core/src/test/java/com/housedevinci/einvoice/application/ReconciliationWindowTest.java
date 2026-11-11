package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** D-PR60-10: a window that covers nothing is refused for users of core without the starter. */
class ReconciliationWindowTest {

  @Test
  void a_zero_or_negative_window_is_refused() {
    for (Duration window : new Duration[] {Duration.ZERO, Duration.ofDays(-1)}) {
      assertThatThrownBy(() -> new ReconciliationSweep.Settings(window, null, null, 0, 0, 0, null))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }
}
