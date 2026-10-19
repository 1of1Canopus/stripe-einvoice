package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.adapter.validation.UblDocumentDates;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Rows a resumed run finds, built the way the unit of work leaves them (archive retry refusal
 * design, revision 2, section 2), and the ports a retry is run with.
 */
final class ArchiveRetryFixture {

  static final UblDocumentDates READER = new UblDocumentDates();

  private ArchiveRetryFixture() {}

  /** The shape of the row a resumed run finds. */
  enum Shape {
    /** P1: the marker itself failed; no hash, no key, no object. */
    FAILED_ARCHIVE_NO_HASH(IssuanceState.FAILED_ARCHIVE, false, false),
    /** P2: the PUT timed out and nothing landed. */
    FAILED_ARCHIVE_NO_OBJECT(IssuanceState.FAILED_ARCHIVE, true, false),
    /** P2: the PUT answered with a timeout, but the object landed. */
    FAILED_ARCHIVE_WITH_OBJECT(IssuanceState.FAILED_ARCHIVE, true, true),
    /** The process died after the marker and before the PUT. */
    ARCHIVING_NO_OBJECT(IssuanceState.ARCHIVING, true, false),
    /** The process died after the PUT and before {@code markIssued}. */
    ARCHIVING_WITH_OBJECT(IssuanceState.ARCHIVING, true, true);

    final IssuanceState state;
    final boolean hash;
    final boolean object;

    Shape(IssuanceState state, boolean hash, boolean object) {
      this.state = state;
      this.hash = hash;
      this.object = object;
    }
  }

  /** One prepared row: the harness, its invoice, the event that resumes it, the row as found. */
  record Row(IssuanceTestHarness harness, String invoiceId, String eventId, Issuance before) {

    Issuance now() {
      return harness.issuance(invoiceId).orElseThrow();
    }

    InboundEvent event() {
      return harness.inbound().find(eventId).orElseThrow();
    }

    IssuanceUnitOfWork.Outcome retry(DocumentRenderer r, DocumentValidator v, DocumentDates d) {
      return retry(r, v, d, harness.archive());
    }

    IssuanceUnitOfWork.Outcome retry(
        DocumentRenderer r, DocumentValidator v, DocumentDates d, ArchiveStore a) {
      return harness.unitOfWorkWith(r, v, d, a).process(eventId);
    }

    /** The resumed run with the item 38 renderer: BT-2 seven days before the numbering day. */
    IssuanceUnitOfWork.Outcome retryBackdated() {
      return retry(backdating(harness, 7), TestValidators.passing(), READER);
    }

    List<String> chained() {
      return ArchiveRetryFixture.chained(harness, invoiceId);
    }
  }

  static Row prepare(Shape shape, String invoiceId) {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);
    switch (shape) {
      case FAILED_ARCHIVE_NO_HASH -> {
        harness.renderer().breakWith(new Error("crash after NUMBERED"));
        crash(harness, eventId);
        harness.renderer().heal();
        PostgresSupport.execute(
            "UPDATE einvoice_issuance SET state = 'FAILED_ARCHIVE' WHERE seller_id = '"
                + harness.sellerId()
                + "' AND stripe_invoice_id = '"
                + invoiceId
                + "'");
      }
      case FAILED_ARCHIVE_NO_OBJECT -> {
        harness
            .archive()
            .failWith(new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "timeout"));
        harness.unitOfWork().process(eventId);
      }
      case FAILED_ARCHIVE_WITH_OBJECT -> {
        harness
            .archive()
            .failAfterWriteWith(
                new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "timeout after the write"));
        harness.unitOfWork().process(eventId);
      }
      case ARCHIVING_NO_OBJECT -> {
        harness.archive().failWith(new Error("killed before the PUT"));
        crash(harness, eventId);
      }
      case ARCHIVING_WITH_OBJECT -> {
        harness.archive().failAfterWriteWith(new Error("killed after the PUT"));
        crash(harness, eventId);
      }
    }
    harness.archive().heal();
    Issuance before = harness.issuance(invoiceId).orElseThrow();
    assertThat(before.state()).as("fixture state").isEqualTo(shape.state);
    assertThat(before.documentHash().isPresent()).as("fixture hash").isEqualTo(shape.hash);
    assertThat(harness.archive().size()).as("fixture object").isEqualTo(shape.object ? 1 : 0);
    return new Row(harness, invoiceId, eventId, before);
  }

  private static void crash(IssuanceTestHarness harness, String eventId) {
    try {
      harness.unitOfWork().process(eventId);
    } catch (Error expected) {
      // The crash the recovery column of the unit of work describes.
    }
  }

  /** A renderer that edits the harness renderer's bytes, the way a host renderer would. */
  static DocumentRenderer editing(
      IssuanceTestHarness harness, BiFunction<String, DocumentInput, String> edit) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public RenderedDocument render(DocumentInput input) {
        RenderedDocument d = real.render(input);
        String xml = new String(d.bytes(), StandardCharsets.UTF_8);
        return new RenderedDocument(
            edit.apply(xml, input).getBytes(StandardCharsets.UTF_8), d.extension(), d.profile());
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return real.preflight(input);
      }

      @Override
      public boolean carriesSupplyDate() {
        return true;
      }
    };
  }

  static DocumentRenderer backdating(IssuanceTestHarness harness, int days) {
    return editing(
        harness,
        (xml, input) ->
            xml.replace(
                "<IssueDate>" + input.issueDate() + "</IssueDate>",
                "<IssueDate>" + input.issueDate().minusDays(days) + "</IssueDate>"));
  }

  /** A renderer that refuses with a code, or throws a host bug, on every render. */
  static DocumentRenderer throwing(IssuanceTestHarness harness, RuntimeException failure) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public RenderedDocument render(DocumentInput input) {
        throw failure;
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return real.preflight(input);
      }

      @Override
      public boolean carriesSupplyDate() {
        return true;
      }
    };
  }

  /** A validator that counts its calls and answers what {@code delegate} answers. */
  static final class Counting implements DocumentValidator {
    final AtomicInteger calls = new AtomicInteger();
    private final DocumentValidator delegate;

    Counting(DocumentValidator delegate) {
      this.delegate = delegate;
    }

    @Override
    public Report validate(byte[] bytes, DocumentInput input) {
      calls.incrementAndGet();
      return delegate.validate(bytes, input);
    }
  }

  /** A date reader that counts its calls. */
  static final class CountingDates implements DocumentDates {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public FoundDates read(byte[] bytes) {
      calls.incrementAndGet();
      return READER.read(bytes);
    }
  }

  /** The harness archive, except that every read throws {@code failure}. */
  static ArchiveStore unreadable(InMemoryArchiveStore inner, RuntimeException failure) {
    return new ArchiveStore() {
      @Override
      public boolean supportsAtomicCreate() {
        return true;
      }

      @Override
      public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
        return inner.putIfAbsent(key, bytes);
      }

      @Override
      public Optional<byte[]> get(ArchiveKey key) {
        throw failure;
      }

      @Override
      public java.util.List<String> list(String prefix, int limit) {
        return inner.list(prefix, limit);
      }

      @Override
      public String describe() {
        return "unreadable archive (test)";
      }
    };
  }

  /** The chained events of one invoice, oldest first: state, hash, key and rule id. */
  static List<String> chained(IssuanceTestHarness harness, String invoiceId) {
    List<String> out = new ArrayList<>();
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT state, document_sha256, archive_key, void_rule_id"
                    + " FROM einvoice_issuance_event WHERE seller_id = ? AND stripe_invoice_id = ?"
                    + " ORDER BY seq")) {
      ps.setString(1, harness.sellerId());
      ps.setString(2, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              rs.getString(1)
                  + "|"
                  + rs.getString(2)
                  + "|"
                  + rs.getString(3)
                  + "|"
                  + rs.getString(4));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  /** The line a burned row's chained event must carry: the row's hash, key and rule id. */
  static String burnLine(Issuance row, String ruleId) {
    return "FAILED_VALIDATION|" + row.documentSha256() + "|" + row.archiveKey() + "|" + ruleId;
  }

  /**
   * The WARN lines written while {@code action} runs that name a resumed burn. The core's test
   * logger is slf4j-simple, which resolves {@code System.err} at each write.
   */
  static List<String> resumedBurnWarnings(Runnable action) {
    java.io.PrintStream original = System.err;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured
        .toString(StandardCharsets.UTF_8)
        .lines()
        .filter(l -> l.contains(" WARN ") && l.contains("refused on a resumed run"))
        .toList();
  }
}
