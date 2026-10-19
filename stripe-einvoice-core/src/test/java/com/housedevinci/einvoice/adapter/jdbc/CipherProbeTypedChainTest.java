package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceChainVerifier.Status;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The typed canonical form ({@code ei4h}) on a database of its own: the chain is one trail for the
 * whole database, so every probe here rewrites rows on a database no other test reads.
 */
class CipherProbeTypedChainTest {

  private static final java.util.List<DataSource> OPENED =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  private static <T extends DataSource> T opened(T ds) {
    OPENED.add(ds);
    return ds;
  }

  @AfterEach
  void closePools() {
    synchronized (OPENED) {
      OPENED.forEach(PostgresSupport::close);
      OPENED.clear();
    }
  }

  private static IssuanceChainVerifier verifier(JdbcIssuanceStore store) {
    return new IssuanceChainVerifier(
        store, store, Map.of(Format1.KEY_ID, Format1.SECRET.getBytes(StandardCharsets.UTF_8)));
  }

  /** Two issued invoices, each corrected by one issued credit note. */
  private record Trail(DocumentSeriesFixture f, Issuance a, Issuance b, Issuance ca, Issuance cb) {}

  private static Trail trail(String hint) {
    DataSource ds = opened(PostgresSupport.freshDatabase(hint));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller(hint));
    Issuance a = f.issuedInvoice("in_a", MARCH_10);
    Issuance b = f.issuedInvoice("in_b", MARCH_10);
    Issuance ca = f.issue(f.credit("cn_a", MARCH_10, a.id()), Mode.LIVE);
    Issuance cb = f.issue(f.credit("cn_b", MARCH_10, b.id()), Mode.LIVE);
    IssuanceChainVerifier.Report clean = verifier(f.store).verify();
    assertThat(clean.status()).isEqualTo(Status.INTACT);
    assertThat(clean.forms()).containsExactly(Map.entry(IssuanceChain.TYPED_KEYED_VERSION, 4L));
    return new Trail(f, a, b, ca, cb);
  }

  private static void asOwner(DataSource ds, String table, String trigger, String sql) {
    PostgresSupport.executeOn(ds, "ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
    try {
      PostgresSupport.executeOn(ds, sql);
    } finally {
      PostgresSupport.executeOn(ds, "ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
    }
  }

  @Test
  void probe_swapping_two_target_ids_breaks_the_chain_of_the_381() {
    Trail t = trail("swapids");
    // A role that outranks the triggers points each credit note at the other invoice. Every
    // hash still recomputes; the cross-check through the link is what disagrees (D-PS1-37).
    asOwner(
        t.f().dataSource,
        "einvoice_issuance",
        "einvoice_issuance_guard",
        "UPDATE einvoice_issuance SET corrects_issuance_id = CASE id WHEN "
            + t.ca().id()
            + " THEN "
            + t.b().id()
            + " ELSE "
            + t.a().id()
            + " END WHERE id IN ("
            + t.ca().id()
            + ", "
            + t.cb().id()
            + ")");

    IssuanceChainVerifier.Report report = verifier(t.f().store).verify();
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.unchainedDispositions()).isEqualTo(2);
  }

  @Test
  void probe_rewriting_the_corrected_invoice_on_a_chained_event_is_broken() {
    Trail t = trail("rewritetarget");
    // The event itself is rewritten to name the other invoice's number: the typed form hashes the
    // target's whole identity, so the row no longer recomputes.
    asOwner(
        t.f().dataSource,
        "einvoice_issuance_event",
        "einvoice_issuance_event_append_only",
        "UPDATE einvoice_issuance_event SET corrects_legal_number = '"
            + t.b().legalNumber().value()
            + "' WHERE stripe_invoice_id = 'cn_a'");

    IssuanceChainVerifier.Report report = verifier(t.f().store).verify();
    assertThat(report.status()).isEqualTo(Status.BROKEN);
    assertThat(report.brokenAtSequence()).isPositive();
  }

  @Test
  void probe_flipping_the_corrected_invoice_id_on_a_chained_event_is_broken() {
    Trail t = trail("flipid");
    asOwner(
        t.f().dataSource,
        "einvoice_issuance_event",
        "einvoice_issuance_event_append_only",
        "UPDATE einvoice_issuance_event SET corrects_issuance_id = "
            + t.b().id()
            + " WHERE stripe_invoice_id = 'cn_a'");

    assertThat(verifier(t.f().store).verify().status()).isEqualTo(Status.BROKEN);
  }

  @Test
  void probe_flipping_the_type_of_a_post_seam_series_is_broken() {
    Trail t = trail("flipseries");
    // D-A1-C03 build: the type is bound to the name row by a foreign key, so the owner's flip
    // rewrites the name row and the series rows in one statement, with both guards disabled.
    PostgresSupport.executeOn(
        t.f().dataSource,
        "ALTER TABLE einvoice_series_name DISABLE TRIGGER einvoice_series_name_append_only");
    asOwner(
        t.f().dataSource,
        "einvoice_series",
        "einvoice_series_guard",
        "WITH n AS (UPDATE einvoice_series_name SET document_type = '381' WHERE series = 'INV'"
            + " RETURNING 1) UPDATE einvoice_series SET document_type = '381' WHERE series = 'INV'");
    PostgresSupport.executeOn(
        t.f().dataSource,
        "ALTER TABLE einvoice_series_name ENABLE TRIGGER einvoice_series_name_append_only");

    assertThat(verifier(t.f().store).verify().status()).isEqualTo(Status.BROKEN);
  }

  @Test
  void probe_flipping_the_type_of_a_post_seam_event_is_broken() {
    Trail t = trail("flipevent");
    // document_type and the six corrects_* columns together, so the row's own CHECK holds: the
    // hash is what refuses it.
    asOwner(
        t.f().dataSource,
        "einvoice_issuance_event",
        "einvoice_issuance_event_append_only",
        "UPDATE einvoice_issuance_event e SET document_type = '381', corrects_issuance_id = t.id,"
            + " corrects_seller_id = t.seller_id, corrects_mode = t.mode,"
            + " corrects_series = t.series, corrects_fiscal_year = t.fiscal_year,"
            + " corrects_legal_number = t.legal_number FROM einvoice_issuance t WHERE t.id = "
            + t.a().id()
            + " AND e.stripe_invoice_id = 'in_b'");

    assertThat(verifier(t.f().store).verify().status()).isEqualTo(Status.BROKEN);
  }
}
