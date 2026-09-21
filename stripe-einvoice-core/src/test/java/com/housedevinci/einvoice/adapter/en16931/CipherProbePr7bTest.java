package com.housedevinci.einvoice.adapter.en16931;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Pass 2: the buyer-controlled terms the branch's own property test does not vary. */
class CipherProbePr7bTest {

  private record Term(String name, BiFunction<DocumentInput, String, DocumentInput> set) {}

  private static final List<Term> TERMS =
      List.of(
          new Term("buyer tax id (BT-48/BT-49)", (b, v) -> buyer(b, 7, v)),
          new Term("buyer country (BT-55)", (b, v) -> buyer(b, 6, v)),
          new Term("buyer email", (b, v) -> buyer(b, 1, v)),
          new Term("buyer line1 (BT-50)", (b, v) -> buyer(b, 2, v)),
          new Term("upstream currency", CipherProbePr7bTest::withCurrency));

  @Test
  void probe_every_buyer_controlled_term_agrees_between_the_passes() {
    Random random = new Random(20_260_922L);
    List<String> leaks = new ArrayList<>();
    int construction = 0;
    int refused = 0;
    int passed = 0;
    for (int i = 0; i < 2_000; i++) {
      String generated = generate(random);
      boolean german = random.nextBoolean();
      DocumentInput base =
          german ? DocumentFixtures.germanStandardRated() : DocumentFixtures.frenchStandardRated();
      SellerProfile seller =
          german ? DocumentFixtures.germanSeller() : DocumentFixtures.frenchSeller();
      UblProfile profile = german ? UblProfile.XRECHNUNG_UBL : UblProfile.PEPPOL_BIS_UBL;
      Term term = TERMS.get(random.nextInt(TERMS.size()));
      DocumentInput input;
      try {
        input = term.set().apply(base, generated);
      } catch (RuntimeException constructionRefusal) {
        construction++;
        continue;
      }
      En16931DocumentRenderer renderer = new En16931DocumentRenderer(seller, profile, null);
      PreflightReport report;
      try {
        report = renderer.preflight(input.unnumbered());
      } catch (RuntimeException thrown) {
        leaks.add(profile.name() + " / " + term.name() + ": preflight threw " + thrown);
        continue;
      }
      if (report.verdict() != PreflightReport.Verdict.PASSED) {
        refused++;
        continue;
      }
      passed++;
      try {
        renderer.render(input);
      } catch (EInvoiceException refusal) {
        leaks.add(
            profile.name()
                + " / "
                + term.name()
                + ": preflight PASSED, render refused "
                + refusal.code()
                + " value="
                + escape(generated));
      } catch (RuntimeException thrown) {
        leaks.add(
            profile.name()
                + " / "
                + term.name()
                + ": preflight PASSED, render threw "
                + thrown.getClass().getSimpleName()
                + " "
                + thrown.getMessage()
                + " value="
                + escape(generated));
      }
    }
    System.out.println(
        "D7-01 census: construction="
            + construction
            + " refused="
            + refused
            + " passed="
            + passed
            + " leaks="
            + leaks.size());
    leaks.stream().distinct().limit(10).forEach(l -> System.out.println("D7-01: " + l));
    assertThat(leaks).as("a term the mapper accepts is a term the writer writes").isEmpty();
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder();
    s.codePoints().forEach(c -> b.append(String.format("\\u%04x", c)));
    return b.toString();
  }

  private static DocumentInput buyer(DocumentInput base, int slot, String value) {
    SourceInvoice.SourceParty b = base.invoice().buyer();
    String[] f = {
      b.name(), b.email(), b.line1(), b.line2(), b.postalCode(), b.city(), b.country(), b.taxId()
    };
    f[slot] = value;
    SourceInvoice.SourceParty replaced =
        new SourceInvoice.SourceParty(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7]);
    SourceInvoice i = base.invoice();
    return reinput(
        base,
        new SourceInvoice(
            i.id(),
            i.number(),
            i.accountId(),
            i.livemode(),
            i.currency(),
            i.status(),
            i.finalizedAt(),
            replaced,
            i.lines(),
            i.taxBuckets(),
            i.taxTreatments(),
            i.subtotalMinor(),
            i.taxMinor(),
            i.totalMinor(),
            true));
  }

  private static DocumentInput withCurrency(DocumentInput base, String currency) {
    SourceInvoice i = base.invoice();
    return reinput(
        base,
        new SourceInvoice(
            i.id(),
            i.number(),
            i.accountId(),
            i.livemode(),
            currency,
            i.status(),
            i.finalizedAt(),
            i.buyer(),
            i.lines(),
            i.taxBuckets(),
            i.taxTreatments(),
            i.subtotalMinor(),
            i.taxMinor(),
            i.totalMinor(),
            true));
  }

  private static DocumentInput reinput(DocumentInput base, SourceInvoice invoice) {
    return new DocumentInput(
        new com.housedevinci.einvoice.application.MappingInput(
            invoice, base.seriesKey(), base.issueDate(), base.rulePackVersion()),
        base.legalNumber());
  }

  private static String generate(Random random) {
    char[] alphabet = {
      'a',
      'Z',
      '0',
      ' ',
      '&',
      '<',
      '>',
      '"',
      '\'',
      '\n',
      '\t',
      '\r',
      (char) 0x00,
      (char) 0x07,
      (char) 0x0b,
      (char) 0x1f,
      (char) 0x85,
      (char) 0xa0,
      (char) 0x200b,
      (char) 0x2028,
      (char) 0xd800,
      (char) 0xdfff,
      (char) 0xfffe,
      (char) 0xffff,
      (char) 0xfeff,
      'D',
      'E',
      'F',
      'R',
      'U'
    };
    int length = 1 + random.nextInt(12);
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet[random.nextInt(alphabet.length)]);
    }
    return out.toString();
  }
}
