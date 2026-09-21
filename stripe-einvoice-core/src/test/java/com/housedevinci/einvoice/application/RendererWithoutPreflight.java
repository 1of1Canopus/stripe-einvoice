package com.housedevinci.einvoice.application;

/**
 * A third-party renderer written before the port had a preflight: it implements {@code render} and
 * inherits the default answer. The compatibility path, and the only thing in this repository that
 * takes it.
 */
public final class RendererWithoutPreflight implements DocumentRenderer {

  private final DeterministicRenderer delegate = new DeterministicRenderer();

  @Override
  public RenderedDocument render(DocumentInput input) {
    return delegate.render(input);
  }
}
