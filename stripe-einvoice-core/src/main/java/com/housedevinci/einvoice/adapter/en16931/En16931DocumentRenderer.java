package com.housedevinci.einvoice.adapter.en16931;

import com.housedevinci.einvoice.adapter.xml.UblDocumentWriter;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import com.housedevinci.einvoice.domain.en16931.UnnumberedInvoice;
import com.housedevinci.einvoice.domain.en16931.PartyIdentifier;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import java.util.Objects;

/**
 * The {@link DocumentRenderer} the issuance unit of work has been waiting for: the mapper and the
 * UBL writer, in that order, producing the exact bytes that will be validated, hashed and archived.
 *
 * <p>The property the unit of work depends on is that <b>the same input renders the same bytes</b>,
 * under any time zone and any locale. Nothing on this path reads a clock, a default zone or a
 * default locale; the issue date arrives already converted in the seller's tax zone, and the writer
 * emits canonical form. A determinism test renders each fixture under two zones and two locales and
 * compares bytes.
 */
public final class En16931DocumentRenderer implements DocumentRenderer {

  private final StripeInvoiceMapper mapper;
  private final UblDocumentWriter writer;
  private final UblProfile profile;

  /**
   * @param seller the configured issuing party, already validated for this profile at startup
   * @param profile the target profile
   * @param buyerElectronicAddress BT-49 when it cannot be derived from the buyer's VAT identifier
   */
  public En16931DocumentRenderer(
      SellerProfile seller, UblProfile profile, PartyIdentifier buyerElectronicAddress) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.mapper = new StripeInvoiceMapper(seller, profile, buyerElectronicAddress);
    this.writer = new UblDocumentWriter(profile);
  }

  @Override
  public RenderedDocument render(DocumentInput input) {
    return new RenderedDocument(
        writer.write(model(input)), "xml", profile.renderedProfile());
  }

  /**
   * The mapping, run and thrown away.
   *
   * <p>It is the render path's own first stage - {@link StripeInvoiceMapper#map} - so every screen
   * the render applies is applied here, by construction rather than by agreement. The writer is
   * deliberately not run: every string that reaches it arrived through a screened business term, so
   * a writer refusal implies a mapper refusal. That is an invariant and a probe asserts it.
   */
  @Override
  public PreflightReport preflight(MappingInput input) {
    try {
      mapper.map(input);
      return PreflightReport.passed();
    } catch (EInvoiceException refusal) {
      return PreflightReport.refused(refusal.code());
    }
  }

  /** The semantic model, for a caller that wants to inspect it rather than the bytes. */
  public EnInvoice model(DocumentInput input) {
    UnnumberedInvoice unnumbered = mapper.map(input.unnumbered());
    return unnumbered.numbered(input.legalNumber());
  }

  /** The profile this renderer writes under. */
  public UblProfile profile() {
    return profile;
  }
}
