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

  /**
   * Answers, <b>before a number has been allocated</b>, whether this renderer could produce a
   * document for this invoice at all.
   *
   * <p>A refusal that depends on the buyer's own data - a name no XML parser can read, a currency
   * this module has no exponent for, a tax rate no category can be established for - costs a legal
   * number if it is discovered in the render, because the number is allocated first and a number is
   * never reused. Asked here, the same refusal costs nothing: the inbound row records the code and
   * no issuance row, chain entry or archive object is created.
   *
   * <p>Implementations run <b>the render path's own mapping</b> and discard the result, never a
   * second list of checks. A second list is a list that drifts: the pass that allocates the number
   * would screen one thing and the pass that writes the bytes another.
   *
   * <p>This method writes nothing, reads no clock and borrows no connection, so a replay or a
   * duplicate delivery is free.
   *
   * <p>The default answers {@link PreflightReport.Verdict#NOT_SUPPORTED} rather than refusing:
   * refusing by default would stop every third-party renderer written before this method existed
   * from issuing anything at all on upgrade. The renderer this module ships always overrides it,
   * and an application wired to one that does not is told so at startup, in a compliance finding,
   * and on every outcome - never silently treated as a pass.
   */
  default PreflightReport preflight(MappingInput input) {
    return PreflightReport.notSupported();
  }

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
