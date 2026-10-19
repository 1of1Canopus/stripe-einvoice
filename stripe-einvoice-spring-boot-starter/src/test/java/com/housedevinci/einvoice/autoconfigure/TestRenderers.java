package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import java.util.function.Function;

/**
 * Test renderers that declare they write the supply date (D-PS1-44), so the intake wiring check and
 * the unit of work let them number invoices. The preflight stays the port's default.
 */
public final class TestRenderers {

  private TestRenderers() {}

  public static DocumentRenderer supplying(
      Function<DocumentInput, DocumentRenderer.RenderedDocument> render) {
    return new DocumentRenderer() {
      @Override
      public boolean carriesSupplyDate() {
        return true;
      }

      @Override
      public RenderedDocument render(DocumentInput input) {
        return render.apply(input);
      }
    };
  }
}
