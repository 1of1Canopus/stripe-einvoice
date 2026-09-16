package com.housedevinci.einvoice.adapter.en16931;

import com.housedevinci.einvoice.adapter.xml.UblDocumentWriter;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
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
    EnInvoice invoice = mapper.map(input);
    return new RenderedDocument(writer.write(invoice), "xml", profile.renderedProfile());
  }

  /** The semantic model, for a caller that wants to inspect it rather than the bytes. */
  public EnInvoice model(DocumentInput input) {
    return mapper.map(input);
  }

  /** The profile this renderer writes under. */
  public UblProfile profile() {
    return profile;
  }
}
