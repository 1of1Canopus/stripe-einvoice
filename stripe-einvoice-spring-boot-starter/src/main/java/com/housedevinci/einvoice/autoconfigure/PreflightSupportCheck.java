package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightSupport;
import com.housedevinci.einvoice.domain.Mode;
import org.springframework.beans.factory.InitializingBean;

/**
 * Finds out at startup, not on the first invoice, whether the wired renderer implements a
 * pre-allocation preflight.
 *
 * <p>Two paths reach the same record, because one event is not a control: a renderer that does not
 * override the port method at all is detected here, before a single invoice is processed, and a
 * renderer that overrides it and answers {@code NOT_SUPPORTED} anyway is detected by the unit of
 * work the first time it answers. Both call {@link PreflightSupport}, which raises the finding once
 * per application start.
 *
 * <p>The check is a method lookup, not a call: calling a preflight with a synthetic invoice would
 * tell us what that renderer thinks of that invoice, which is not the question.
 */
public class PreflightSupportCheck implements InitializingBean {

  private final DocumentRenderer renderer;
  private final PreflightSupport support;
  private final EInvoiceProperties properties;

  public PreflightSupportCheck(
      DocumentRenderer renderer, PreflightSupport support, EInvoiceProperties properties) {
    this.renderer = renderer;
    this.support = support;
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    if (!overridesPreflight(renderer)) {
      support.notSupported(
          properties.getSeller().getId(),
          Mode.of(properties.getMode()),
          renderer.getClass().getName());
    }
  }

  /** True when this renderer supplies its own preflight rather than inheriting the default. */
  static boolean overridesPreflight(DocumentRenderer renderer) {
    try {
      return !renderer
          .getClass()
          .getMethod("preflight", MappingInput.class)
          .isDefault();
    } catch (NoSuchMethodException e) {
      // A renderer compiled against an older port. It cannot have overridden what it never saw.
      return false;
    }
  }
}
