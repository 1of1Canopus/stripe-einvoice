package com.housedevinci.einvoice.adapter.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/**
 * The released 0.1.0 format, rebuilt independently of the production code.
 *
 * <p>The upgrade probes need a database that 0.1.0 wrote: the 0.1.0 schema (a verbatim copy of the
 * released script, {@code schema-postgresql-0.1.0.sql}), rows written by the 0.1.0 statements, and
 * chained events hashed with the 0.1.0 canonical form. The canonical form here is a copy of 0.1.0's
 * {@code IssuanceChain.canonical}, kept separate on purpose: if the production dual-read ever
 * drifted from what 0.1.0 actually wrote, a probe that reused the production code would drift with
 * it and stay green.
 */
final class Format1 {

  static final String SECRET = "0123456789abcdef0123456789abcdef";
  static final String KEY_ID = "k1";

  // The 0.1.0 statements, verbatim from JdbcIssuanceStore at v0.1.0.
  static final String CREATE_SERIES_ROW =
      "INSERT INTO einvoice_series"
          + " (seller_id, series, fiscal_year, mode, prefix, width, next_number, updated_at)"
          + " VALUES (?,?,?,?,?,?,1,?) ON CONFLICT DO NOTHING";
  static final String ALLOCATE =
      "UPDATE einvoice_series SET next_number = next_number + 1, updated_at = ?"
          + " WHERE seller_id = ? AND series = ? AND fiscal_year = ? AND mode = ?"
          + " AND next_number <= (power(10, width)::bigint - 1)"
          + " RETURNING next_number - 1, prefix, width";
  static final String INSERT_ISSUANCE =
      "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, stripe_account_id,"
          + " stripe_number, series, fiscal_year, legal_number, counter, issued_at, allocated_at,"
          + " rule_pack_version, state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";
  static final String INSERT_EVENT =
      "INSERT INTO einvoice_issuance_event (ts, seller_id, mode, stripe_account_id, series,"
          + " fiscal_year, legal_number, counter, stripe_invoice_id, stripe_number, state,"
          + " issued_at, document_sha256, archive_key, rule_pack_version, void_reason,"
          + " void_rule_id, chain_version, key_id, prev_hash, hash)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private Format1() {}

  static void installSchema(DataSource ds) {
    String sql;
    try (var in =
        Format1.class.getResourceAsStream(
            "/com/housedevinci/einvoice/schema-postgresql-0.1.0.sql")) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    PostgresSupport.executeOn(ds, sql);
  }

  /** One numbered row the way 0.1.0 wrote it; returns the legal number. */
  static String number(
      Connection c, String seller, String series, int year, String invoiceId, Instant issuedAt)
      throws SQLException {
    seed(c, seller, series, year, issuedAt);
    return allocateAndInsert(c, seller, series, year, invoiceId, issuedAt);
  }

  /** The 0.1.0 series seed on its own. */
  static void seed(Connection c, String seller, String series, int year, Instant issuedAt)
      throws SQLException {
    Timestamp now = Timestamp.from(issuedAt);
    try (PreparedStatement ps = c.prepareStatement(CREATE_SERIES_ROW)) {
      ps.setString(1, seller);
      ps.setString(2, series);
      ps.setInt(3, year);
      ps.setString(4, "live");
      ps.setString(5, "INV-" + year + "-");
      ps.setInt(6, 6);
      ps.setTimestamp(7, now);
      ps.executeUpdate();
    }
  }

  /** The 0.1.0 counter statement and issuance insert, on a series row that already exists. */
  static String allocateAndInsert(
      Connection c, String seller, String series, int year, String invoiceId, Instant issuedAt)
      throws SQLException {
    Timestamp now = Timestamp.from(issuedAt);
    long counter;
    String number;
    try (PreparedStatement ps = c.prepareStatement(ALLOCATE)) {
      ps.setTimestamp(1, now);
      ps.setString(2, seller);
      ps.setString(3, series);
      ps.setInt(4, year);
      ps.setString(5, "live");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        counter = rs.getLong(1);
        number = rs.getString(2) + String.format("%0" + rs.getInt(3) + "d", counter);
      }
    }
    try (PreparedStatement ps = c.prepareStatement(INSERT_ISSUANCE)) {
      int i = 1;
      ps.setString(i++, seller);
      ps.setString(i++, "live");
      ps.setString(i++, invoiceId);
      ps.setString(i++, "");
      ps.setString(i++, "STRIPE-" + counter);
      ps.setString(i++, series);
      ps.setInt(i++, year);
      ps.setString(i++, number);
      ps.setLong(i++, counter);
      ps.setTimestamp(i++, now);
      ps.setTimestamp(i++, now);
      ps.setString(i++, "fr-2026.1");
      ps.setString(i, "NUMBERED");
      ps.executeQuery().close();
    }
    return number;
  }

  /**
   * Moves a row to {@code state} and appends its 0.1.0 chained event ({@code ei2h}) and anchor,
   * exactly as 0.1.0 did. Returns the new head hash.
   */
  static String dispose(
      Connection c,
      String seller,
      String series,
      int year,
      String invoiceId,
      String number,
      long counter,
      String state,
      String sha,
      String key,
      String voidReason,
      Instant at,
      Instant issuedAt,
      String prev,
      long rowCount)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE einvoice_issuance SET state = ?, document_sha256 = ?, archive_key = ?,"
                + " void_reason = ? WHERE seller_id = ? AND mode = 'live' AND stripe_invoice_id = ?")) {
      ps.setString(1, state.equals("ISSUED") ? "ARCHIVING" : state);
      ps.setString(2, sha);
      ps.setString(3, key);
      ps.setString(4, voidReason);
      ps.setString(5, seller);
      ps.setString(6, invoiceId);
      ps.executeUpdate();
    }
    if (state.equals("ISSUED")) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE einvoice_issuance SET state = 'ISSUED'"
                  + " WHERE seller_id = ? AND mode = 'live' AND stripe_invoice_id = ?")) {
        ps.setString(1, seller);
        ps.setString(2, invoiceId);
        ps.executeUpdate();
      }
    }
    String stripeNumber = "STRIPE-" + counter;
    String canonical =
        "ei2h"
            + field(KEY_ID)
            + field(at.toString())
            + field(seller)
            + field("live")
            + field("")
            + field(series)
            + field(Integer.toString(year))
            + field(number)
            + field(Long.toString(counter))
            + field(invoiceId)
            + field(stripeNumber)
            + field(state)
            + field(issuedAt.toString())
            + field(sha)
            + field(key)
            + field("fr-2026.1")
            + field(voidReason)
            + field("");
    String hash = hmac(canonical + "|" + prev.length() + ":" + prev);
    try (PreparedStatement ps = c.prepareStatement(INSERT_EVENT)) {
      int i = 1;
      ps.setTimestamp(i++, Timestamp.from(at));
      ps.setString(i++, seller);
      ps.setString(i++, "live");
      ps.setString(i++, "");
      ps.setString(i++, series);
      ps.setInt(i++, year);
      ps.setString(i++, number);
      ps.setLong(i++, counter);
      ps.setString(i++, invoiceId);
      ps.setString(i++, stripeNumber);
      ps.setString(i++, state);
      ps.setTimestamp(i++, Timestamp.from(issuedAt));
      ps.setString(i++, sha);
      ps.setString(i++, key);
      ps.setString(i++, "fr-2026.1");
      ps.setString(i++, voidReason);
      ps.setString(i++, "");
      ps.setString(i++, "ei2h");
      ps.setString(i++, KEY_ID);
      ps.setString(i++, prev);
      ps.setString(i, hash);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO einvoice_issuance_anchor (id, head_hash, row_count, updated_at, keyed)"
                + " VALUES (1, ?, ?, ?, true) ON CONFLICT (id) DO UPDATE SET"
                + " head_hash = EXCLUDED.head_hash, row_count = EXCLUDED.row_count,"
                + " updated_at = EXCLUDED.updated_at")) {
      ps.setString(1, hash);
      ps.setLong(2, rowCount);
      ps.setTimestamp(3, Timestamp.from(at));
      ps.executeUpdate();
    }
    return hash;
  }

  /** The 0.1.0 canonical form of an arbitrary event, for the residual probe (S-07). */
  static String hashOf(com.housedevinci.einvoice.domain.IssuanceEvent e, String prev) {
    String canonical =
        "ei2h"
            + field(e.keyId())
            + field(e.timestamp().toString())
            + field(e.seriesKey().sellerId())
            + field(e.seriesKey().mode().wire())
            + field(e.stripeAccountId())
            + field(e.seriesKey().series())
            + field(Integer.toString(e.seriesKey().fiscalYear()))
            + field(e.legalNumber().value())
            + field(Long.toString(e.legalNumber().counter()))
            + field(e.stripeInvoiceId())
            + field(e.stripeNumber())
            + field(e.state().name())
            + field(e.issuedAt() == null ? null : e.issuedAt().toString())
            + field(e.documentSha256())
            + field(e.archiveKey())
            + field(e.rulePackVersion())
            + field(e.voidReason())
            + field(e.voidRuleId());
    return hmac(canonical + "|" + prev.length() + ":" + prev);
  }

  private static String field(String value) {
    if (value == null) {
      return "|-";
    }
    return "|" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static String hmac(String material) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
