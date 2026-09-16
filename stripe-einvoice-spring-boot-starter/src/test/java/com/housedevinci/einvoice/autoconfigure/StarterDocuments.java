package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.en16931.Contact;
import com.housedevinci.einvoice.domain.en16931.DocumentLine;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import com.housedevinci.einvoice.domain.en16931.Money;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PartyIdentifier;
import com.housedevinci.einvoice.domain.en16931.PaymentInstruction;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import com.housedevinci.einvoice.domain.en16931.TaxCategory;
import com.housedevinci.einvoice.domain.en16931.TaxSubtotal;
import com.housedevinci.einvoice.domain.en16931.VatIdentifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One structurally perfect document for the starter's own tests.
 *
 * <p>It exists so that a test about wiring is never decided by a malformed stub: handing a
 * validator {@code <Invoice/>} and reading FAILED proves the schema works, not the thing under
 * test. Synthetic parties and identifiers only (D-21).
 */
final class StarterDocuments {

  private StarterDocuments() {}

  static EnInvoice peppolInvoice() {
    Money net = Money.ofMinor(96_000, "eur");
    Money tax = Money.ofMinor(19_200, "eur");
    Money gross = Money.ofMinor(115_200, "eur");
    Party seller =
        new Party(
            "Atelier Riviere SAS",
            null,
            new PostalAddress("12 rue des Lilas", null, "Lyon", "69003", null, "FR"),
            VatIdentifier.parse("the seller's VAT identifier", "FR25900000019"),
            null,
            new PartyIdentifier("0002", "900000019"),
            new PartyIdentifier("9957", "FR25900000019"),
            new Contact("Comptabilite", "+33 4 72 00 00 00", "factures@atelier-riviere.invalid"));
    Party buyer =
        new Party(
            "Librairie du Pont SARL",
            null,
            new PostalAddress("3 quai Saint-Antoine", null, "Lyon", "69002", null, "FR"),
            VatIdentifier.parse("the buyer's VAT identifier", "FR49900000027"),
            null,
            null,
            new PartyIdentifier("9957", "FR49900000027"),
            null);
    return new EnInvoice(
        "INV-2026-000001",
        LocalDate.of(2026, 4, 1),
        null,
        DocumentTypeCode.COMMERCIAL_INVOICE,
        "EUR",
        "BON-DE-COMMANDE-9912",
        null,
        null,
        null,
        "STRIPE-FR-0011",
        seller,
        buyer,
        new PaymentInstruction("58", "FR7630006000011234567890189", null, null),
        List.of(
            new DocumentLine(
                "1",
                BigDecimal.ONE,
                DocumentLine.UNIT_PIECE,
                net,
                "Abonnement annuel, formule atelier",
                null,
                TaxCategory.STANDARD,
                Percentage.of("20"),
                null)),
        List.of(new TaxSubtotal(TaxCategory.STANDARD, Percentage.of("20"), net, tax, null)),
        net,
        net,
        tax,
        gross,
        gross);
  }
}
