package com.housedevinci.einvoice.adapter.en16931;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.Totals;
import com.housedevinci.einvoice.domain.en16931.Contact;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PartyIdentifier;
import com.housedevinci.einvoice.domain.en16931.PaymentInstruction;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import com.housedevinci.einvoice.domain.en16931.VatIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The fixture set every writer, determinism and validator test runs against.
 *
 * <p><b>Synthetic parties and identifiers only</b> (D-21, checklist line 61). The SIRENs below are
 * in the 9xx range and carry real Luhn and French VAT check digits, so the identifier validation
 * this module performs is genuinely exercised without any of them belonging to a company that
 * exists. No golden file carries a real customer's data, a real brand, or a path from a machine.
 */
public final class DocumentFixtures {

  /** 23:30 UTC on the last day of a quarter: the timestamp D-15 is about. */
  public static final Instant FINALIZED_AT = Instant.parse("2026-03-31T23:30:00Z");

  /** BT-2 as the seller's tax zone sees that instant. Europe/Berlin puts it in the next day. */
  public static final LocalDate ISSUE_DATE = LocalDate.of(2026, 4, 1);

  public static final SeriesKey SERIES =
      new SeriesKey("seller-1", "MAIN", SeriesKey.CONTINUOUS, Mode.LIVE);

  public static final LegalNumber NUMBER = new LegalNumber("FAC-2026-000042", 42L);

  private DocumentFixtures() {}

  /** A German seller, complete enough for XRechnung's BR-DE-* rules. */
  public static SellerProfile germanSeller() {
    Party party =
        new Party(
            "Nordwind Handels GmbH",
            null,
            new PostalAddress("Speicherstrasse 7", "Haus B", "Hamburg", "20457", null, "DE"),
            VatIdentifier.parse("the seller's VAT identifier", "DE123456789"),
            "27/123/45678",
            new PartyIdentifier("0088", "4260000000004"),
            new PartyIdentifier("9930", "DE123456789"),
            new Contact("Rechnungswesen", "+49 40 5550100", "rechnungen@nordwind.invalid"));
    return new SellerProfile(
        party,
        new PaymentInstruction(
            "58", "DE02120300000000202051", "Nordwind Handels GmbH", "BYLADEM1001"),
        "04011000-1234512345-06");
  }

  /** The same seller with no buyer reference configured: the BR-DE-15 refusal path. */
  public static SellerProfile germanSellerWithoutBuyerReference() {
    SellerProfile complete = germanSeller();
    return new SellerProfile(complete.party(), complete.payment(), null);
  }

  /** A French seller, for the fixtures issued out of France. */
  public static SellerProfile frenchSeller() {
    Party party =
        new Party(
            "Atelier Riviere SAS",
            null,
            new PostalAddress("12 rue des Lilas", null, "Lyon", "69003", null, "FR"),
            VatIdentifier.parse("the seller's VAT identifier", "FR25900000019"),
            null,
            new PartyIdentifier("0002", "900000019"),
            new PartyIdentifier("9957", "FR25900000019"),
            new Contact("Comptabilite", "+33 4 72 00 00 00", "factures@atelier-riviere.invalid"));
    return new SellerProfile(
        party,
        new PaymentInstruction("58", "FR7630006000011234567890189", "Atelier Riviere SAS", null),
        "BON-DE-COMMANDE-9912");
  }

  /** A German B2B invoice at 19 %, for the XRechnung profile with its Leitweg-ID. */
  public static DocumentInput germanStandardRated() {
    SourceInvoice.SourceParty buyer =
        new SourceInvoice.SourceParty(
            "Elbe Maschinenbau AG",
            "kreditoren@elbe-maschinenbau.invalid",
            "Hafenweg 44",
            null,
            "28217",
            "Bremen",
            "DE",
            "DE987654321");
    return input(
        invoice(
            "in_de_standard",
            "STRIPE-DE-0007",
            "eur",
            buyer,
            List.of(
                new SourceInvoice.SourceLine(
                    "1", "Wartungsvertrag, Quartal 1/2026", "txr_de19", 3L, 150_000, 178_500)),
            List.of(new Totals.Bucket("txr_de19", Percentage.of("19"), false, 28_500)),
            List.of(
                new SourceInvoice.SourceTaxTreatment("txr_de19", "DE", "vat", "standard_rated")),
            150_000,
            28_500,
            178_500));
  }

  /** A French B2B invoice at 20 %. */
  public static DocumentInput frenchStandardRated() {
    SourceInvoice.SourceParty buyer =
        new SourceInvoice.SourceParty(
            "Librairie du Pont SARL",
            "compta@librairie-du-pont.invalid",
            "3 quai Saint-Antoine",
            "Batiment C",
            "69002",
            "Lyon",
            "FR",
            "FR49900000027");
    return input(
        invoice(
            "in_fr_standard",
            "STRIPE-FR-0011",
            "eur",
            buyer,
            List.of(
                new SourceInvoice.SourceLine(
                    "1", "Abonnement annuel, formule atelier", "txr_fr20", 1L, 96_000, 115_200)),
            List.of(new Totals.Bucket("txr_fr20", Percentage.of("20"), false, 19_200)),
            List.of(
                new SourceInvoice.SourceTaxTreatment("txr_fr20", "FR", "vat", "standard_rated")),
            96_000,
            19_200,
            115_200));
  }

  /** A Belgian B2B invoice at 21 %, with two lines under one rate. */
  public static DocumentInput belgianStandardRated() {
    SourceInvoice.SourceParty buyer =
        new SourceInvoice.SourceParty(
            "Scheldebrug Logistiek BV",
            "facturen@scheldebrug.invalid",
            "Havenlaan 210",
            null,
            "2030",
            "Antwerpen",
            "BE",
            "BE0123456749");
    return input(
        invoice(
            "in_be_standard",
            "STRIPE-BE-0003",
            "eur",
            buyer,
            List.of(
                new SourceInvoice.SourceLine(
                    "1", "Platformlicentie, maand april", "txr_be21", 1L, 40_000, 48_400),
                new SourceInvoice.SourceLine(
                    "2", "Aanvullende gebruikers", "txr_be21", 4L, 20_000, 24_200)),
            List.of(new Totals.Bucket("txr_be21", Percentage.of("21"), false, 12_600)),
            List.of(
                new SourceInvoice.SourceTaxTreatment("txr_be21", "BE", "vat", "standard_rated")),
            60_000,
            12_600,
            72_600));
  }

  /** A cross-border supply inside the EU where the buyer accounts for the VAT. */
  public static DocumentInput reverseCharge() {
    SourceInvoice.SourceParty buyer =
        new SourceInvoice.SourceParty(
            "Librairie du Pont SARL",
            "compta@librairie-du-pont.invalid",
            "3 quai Saint-Antoine",
            null,
            "69002",
            "Lyon",
            "FR",
            "FR49900000027");
    return input(
        invoice(
            "in_reverse_charge",
            "STRIPE-RC-0002",
            "eur",
            buyer,
            List.of(
                new SourceInvoice.SourceLine(
                    "1", "Beratungsleistung, Maerz 2026", "txr_rc0", 1L, 250_000, 250_000)),
            List.of(new Totals.Bucket("txr_rc0", Percentage.of("0"), false, 0)),
            List.of(new SourceInvoice.SourceTaxTreatment("txr_rc0", "DE", "vat", "reverse_charge")),
            250_000,
            0,
            250_000));
  }

  /**
   * A credit note, built at the model level.
   *
   * <p>There is no Stripe object behind it on purpose. This edition's totals reconciliation refuses
   * an invoice whose total is not positive (PR 2), so no Stripe credit note reaches the mapper yet;
   * what D-13 asks for is that the <b>type code, the sign convention and the writer path</b> exist
   * and are exercised, and those are exercised from here down. The amounts are positive and the
   * type carries the sign, which is the whole point: a 380 with negative amounts is what several
   * profiles reject outright.
   */
  public static com.housedevinci.einvoice.domain.en16931.EnInvoice creditNote(
      SellerProfile seller) {
    Party buyer =
        new Party(
            "Elbe Maschinenbau AG",
            null,
            new PostalAddress("Hafenweg 44", null, "Bremen", "28217", null, "DE"),
            VatIdentifier.parse("the buyer's VAT identifier", "DE987654321"),
            null,
            null,
            new PartyIdentifier("9930", "DE987654321"),
            null);
    com.housedevinci.einvoice.domain.en16931.Money net =
        com.housedevinci.einvoice.domain.en16931.Money.ofMinor(50_000, "eur");
    com.housedevinci.einvoice.domain.en16931.Money tax =
        com.housedevinci.einvoice.domain.en16931.Money.ofMinor(9_500, "eur");
    com.housedevinci.einvoice.domain.en16931.Money gross =
        com.housedevinci.einvoice.domain.en16931.Money.ofMinor(59_500, "eur");
    return new com.housedevinci.einvoice.domain.en16931.EnInvoice(
        "AVO-2026-000003",
        ISSUE_DATE,
        null,
        com.housedevinci.einvoice.domain.en16931.DocumentTypeCode.CREDIT_NOTE,
        "EUR",
        seller.defaultBuyerReferenceValue().orElse(null),
        null,
        NUMBER.value(),
        ISSUE_DATE,
        "STRIPE-CN-0001",
        seller.party(),
        buyer,
        seller.payment(),
        List.of(
            new com.housedevinci.einvoice.domain.en16931.DocumentLine(
                "1",
                java.math.BigDecimal.ONE,
                com.housedevinci.einvoice.domain.en16931.DocumentLine.UNIT_PIECE,
                net,
                "Gutschrift zu Wartungsvertrag, Quartal 1/2026",
                null,
                com.housedevinci.einvoice.domain.en16931.TaxCategory.STANDARD,
                Percentage.of("19"),
                null)),
        List.of(
            new com.housedevinci.einvoice.domain.en16931.TaxSubtotal(
                com.housedevinci.einvoice.domain.en16931.TaxCategory.STANDARD,
                Percentage.of("19"),
                net,
                tax,
                null)),
        net,
        net,
        tax,
        gross,
        gross);
  }

  private static SourceInvoice invoice(
      String id,
      String number,
      String currency,
      SourceInvoice.SourceParty buyer,
      List<SourceInvoice.SourceLine> lines,
      List<Totals.Bucket> buckets,
      List<SourceInvoice.SourceTaxTreatment> treatments,
      long subtotal,
      long tax,
      long total) {
    return new SourceInvoice(
        id,
        number,
        "",
        true,
        currency,
        "paid",
        FINALIZED_AT,
        buyer,
        lines,
        buckets,
        treatments,
        subtotal,
        tax,
        total,
        true);
  }

  private static DocumentInput input(SourceInvoice invoice) {
    return new DocumentInput(
        invoice,
        SERIES,
        NUMBER,
        ISSUE_DATE,
        TaxTreatmentRules.PACK_ID + ":" + TaxTreatmentRules.PACK_VERSION);
  }
}
