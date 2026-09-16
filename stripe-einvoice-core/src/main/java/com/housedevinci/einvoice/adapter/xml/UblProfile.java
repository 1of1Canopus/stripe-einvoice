package com.housedevinci.einvoice.adapter.xml;

/**
 * The EN 16931 profile a document is written and judged under.
 *
 * <p>Every value below was established by <b>running the official validation artefacts</b> over a
 * hand-written document before a line of the writer existed, not by reading a specification and
 * remembering it. The specification identifiers in particular are read out of the stylesheets
 * themselves (the XRechnung one out of the {@code XR-CIUS-ID} variable), so this module and the
 * validator that judges it cannot disagree about what version we claim.
 *
 * <p>The differences that matter are not cosmetic:
 *
 * <ul>
 *   <li><b>XRechnung</b> (Germany's CIUS) adds the {@code BR-DE-*} rules: a buyer reference, a
 *       seller contact with a telephone and an email, payment instructions, and the seller's full
 *       street address are all mandatory on every invoice.
 *   <li><b>Peppol BIS Billing 3.0</b> adds the {@code PEPPOL-EN16931-*} rules: both parties must
 *       carry an electronic address whose scheme is on the Peppol EAS code list. {@code EM} was
 *       removed from that list, so an email address is not an electronic address any more.
 * </ul>
 */
public enum UblProfile {

  /**
   * XRechnung 3.0 (CIUS), the German federal standard. Validated against the KoSIT validator
   * configuration release vendored under {@code reference/schematron/xrechnung/}.
   */
  XRECHNUNG_UBL(
      "xrechnung-ubl",
      "XRechnung 3.0 (UBL)",
      "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
      "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
      true,
      true,
      false),

  /**
   * Peppol BIS Billing 3.0, release 2026.5. Validated against the OpenPeppol schematron vendored
   * under {@code reference/schematron/peppol/}.
   */
  PEPPOL_BIS_UBL(
      "peppol-bis-ubl",
      "Peppol BIS Billing 3.0 (UBL)",
      "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0",
      "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
      false,
      false,
      true);

  private final String id;
  private final String displayName;
  private final String customizationId;
  private final String profileId;
  private final boolean requiresPaymentAndContact;
  private final boolean requiresBuyerReference;
  private final boolean requiresElectronicAddress;

  UblProfile(
      String id,
      String displayName,
      String customizationId,
      String profileId,
      boolean requiresPaymentAndContact,
      boolean requiresBuyerReference,
      boolean requiresElectronicAddress) {
    this.id = id;
    this.displayName = displayName;
    this.customizationId = customizationId;
    this.profileId = profileId;
    this.requiresPaymentAndContact = requiresPaymentAndContact;
    this.requiresBuyerReference = requiresBuyerReference;
    this.requiresElectronicAddress = requiresElectronicAddress;
  }

  /** The value of {@code einvoice.documents.profile} that selects this one. */
  public String id() {
    return id;
  }

  /** The name a human reads in a startup log or a refusal. */
  public String displayName() {
    return displayName;
  }

  /** BT-24, the specification identifier. */
  public String customizationId() {
    return customizationId;
  }

  /** BT-23, the business process identifier. */
  public String profileId() {
    return profileId;
  }

  /** BR-DE-1, BR-DE-2, BR-DE-3..7, BR-DE-16. */
  public boolean requiresPaymentAndContact() {
    return requiresPaymentAndContact;
  }

  /** BR-DE-15. */
  public boolean requiresBuyerReference() {
    return requiresBuyerReference;
  }

  /** PEPPOL-EN16931-R010 and R020, with the scheme checked by PEPPOL-EN16931-CL008. */
  public boolean requiresElectronicAddress() {
    return requiresElectronicAddress;
  }

  /** The value written into {@code DocumentRenderer.RenderedDocument.profile}. */
  public String renderedProfile() {
    return id;
  }

  /**
   * @param id the configured value
   * @return the profile
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@link
   *     com.housedevinci.einvoice.domain.ErrorCodes#CONFIG} naming every value that would work
   */
  public static UblProfile of(String id) {
    for (UblProfile profile : values()) {
      if (profile.id.equalsIgnoreCase(id)) {
        return profile;
      }
    }
    StringBuilder known = new StringBuilder();
    for (UblProfile profile : values()) {
      known.append(known.length() == 0 ? "" : ", ").append(profile.id);
    }
    throw new com.housedevinci.einvoice.domain.EInvoiceException(
        com.housedevinci.einvoice.domain.ErrorCodes.CONFIG,
        "einvoice.documents.profile is one of: " + known);
  }
}
