package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** One seller with an invoice series and a credit-note series, in both modes. */
final class DocumentSeriesFixture {

  static final SeriesDefinition INVOICES = new SeriesDefinition("INV-{fiscalYear}-", 6, true);
  static final SeriesDefinition CREDITS = new SeriesDefinition("CN-{fiscalYear}-", 6, true);
  static final Instant MARCH_10 = Instant.parse("2026-03-10T10:00:00Z");
  static final Instant MARCH_3 = Instant.parse("2026-03-03T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
  static final IssuanceChain CHAIN =
      IssuanceChain.keyed(Format1.SECRET.getBytes(StandardCharsets.UTF_8), Format1.KEY_ID);
  static final String HASH = "a".repeat(64);

  final DataSource dataSource;
  final String seller;
  final JdbcIssuanceStore store;

  DocumentSeriesFixture(DataSource dataSource, String seller) {
    this.dataSource = dataSource;
    this.seller = seller;
    Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series = new LinkedHashMap<>();
    for (Mode mode : Mode.values()) {
      series.put(new JdbcIssuanceStore.SeriesId(seller, "INV", mode), INVOICES);
      series.put(
          new JdbcIssuanceStore.SeriesId(seller, "CN", mode, DocumentTypeCode.CREDIT_NOTE),
          CREDITS);
    }
    this.store = TestStores.store(dataSource, CHAIN, series, CLOCK);
  }

  static DocumentSeriesFixture shared(String hint) {
    return new DocumentSeriesFixture(
        PostgresSupport.dataSource(), PostgresSupport.freshSeller(hint));
  }

  SeriesKey invoices(Mode mode) {
    return new SeriesKey(seller, "INV", 2026, mode);
  }

  SeriesKey credits(Mode mode) {
    return new SeriesKey(seller, "CN", 2026, mode, DocumentTypeCode.CREDIT_NOTE);
  }

  static AllocationRequest request(SeriesKey key, String sourceId, Instant at) {
    return new AllocationRequest(key.scope(), sourceId, "", "S-" + sourceId, at, "r1");
  }

  /** A credit note's caller date (T9, unchanged); an invoice passes none. */
  static Optional<LocalDate> creditDate(SeriesKey key, Instant at) {
    return key.documentType().isCreditNote()
        ? Optional.of(LocalDate.ofInstant(at, ZoneOffset.UTC))
        : Optional.empty();
  }

  Issuance invoice(String id, Instant at) {
    return store.allocate(request(invoices(Mode.LIVE), id, at));
  }

  Issuance issuedInvoice(String id, Instant at, Mode mode) {
    Issuance numbered = store.allocate(request(invoices(mode), id, at));
    return issue(numbered, mode);
  }

  Issuance issuedInvoice(String id, Instant at) {
    return issuedInvoice(id, at, Mode.LIVE);
  }

  Issuance issue(Issuance numbered, Mode mode) {
    String sha = sha(numbered.stripeInvoiceId());
    store.markArchiving(
        seller,
        mode,
        numbered.stripeInvoiceId(),
        sha,
        new ArchiveKey(mode.wire() + "/" + numbered.legalNumber().value() + "-" + sha + ".xml"));
    return store.markIssued(seller, mode, numbered.stripeInvoiceId());
  }

  Issuance credit(String id, Instant at, long target) {
    return store.allocateTyped(
        request(credits(Mode.LIVE), id, at),
        Optional.of(target),
        creditDate(credits(Mode.LIVE), at));
  }

  static String sha(String seed) {
    return com.housedevinci.einvoice.domain.Hashes.sha256Hex(seed);
  }

  long scalar(String sql) {
    return PostgresSupport.scalarOn(dataSource, sql);
  }

  long nextNumber(String series) {
    return scalar(
        "SELECT coalesce(max(next_number), -1) FROM einvoice_series WHERE seller_id = '"
            + seller
            + "' AND series = '"
            + series
            + "' AND mode = 'live'");
  }

  long rows() {
    return scalar("SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + seller + "'");
  }
}
