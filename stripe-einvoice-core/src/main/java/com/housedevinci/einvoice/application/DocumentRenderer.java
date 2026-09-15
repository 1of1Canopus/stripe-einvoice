package com.housedevinci.einvoice.application;

/**
 * Turns one mapped invoice into the exact bytes that will be archived.
 *
 * <p>The EN 16931 writers (XRechnung, Peppol BIS Billing 3.0 UBL) arrive with their own design and
 * their own validator suites. This port is what the issuance unit of work depends on, so the unit
 * of work can be built, tested and reviewed against the property that matters to it: <b>the same
 * input renders the same bytes</b>, under any time zone and any locale (D-15). A renderer that does
 * not is a renderer whose retry writes a second document under the same number.
 *
 * <p>There is deliberately no default implementation. An application with no renderer bean issues
 * nothing and says so at startup, rather than archiving something this module made up.
 */
public interface DocumentRenderer {

  /**
   * @return the bytes and the file extension; the extension goes into the content-addressed key
   */
  RenderedDocument render(DocumentInput input);

  /** What a renderer produced. Bytes, never a stream: they are hashed, validated and stored. */
  record RenderedDocument(byte[] bytes, String extension, String profile) {

    public RenderedDocument {
      if (bytes == null || bytes.length == 0) {
        throw new com.housedevinci.einvoice.domain.EInvoiceException(
            com.housedevinci.einvoice.domain.ErrorCodes.RENDER_FAILED,
            "a renderer returned no bytes");
      }
      bytes = bytes.clone();
      extension =
          com.housedevinci.einvoice.domain.Identifiers.validate("document extension", extension, 8);
      profile = profile == null ? "" : profile;
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
