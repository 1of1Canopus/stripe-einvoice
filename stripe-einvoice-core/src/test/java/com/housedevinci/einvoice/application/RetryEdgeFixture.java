package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import java.time.Duration;
import java.util.List;

/**
 * Builds the start states and the conclusions of the sweeper's edge table (D-PR45-B1, design
 * section 4). Every row is put in its start state through the store's own declared edges, never by
 * SQL, so a start the state machine cannot reach is not a start the table tests.
 */
final class RetryEdgeFixture {

  /** The revoked-key code, by value, so a test reads the same before and after the constant. */
  static final String ACCESS_REFUSED = "DEI-219";

  private RetryEdgeFixture() {}

  /** The five conclusions a run can reach before {@code FETCHED}, and the revoked key. */
  enum Conclusion {
    DROPPED(InboundState.DROPPED, ErrorCodes.UNSUBSCRIBED_EVENT_TYPE),
    REFUSED_VERSION_SKEW(InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW),
    REFUSED_MODE(InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH),
    REFUSED_ACCOUNT(InboundState.REFUSED_ACCOUNT, ErrorCodes.UNKNOWN_ACCOUNT),
    FAILED_FETCH(InboundState.FAILED_FETCH, ErrorCodes.STRIPE_UNAVAILABLE),
    ACCESS_REFUSED_KEY(InboundState.FAILED_FETCH, ACCESS_REFUSED);

    final InboundState target;
    final String code;

    Conclusion(InboundState target, String code) {
      this.target = target;
      this.code = code;
    }

    /** Whether the conclusion, unmapped, schedules a retry. */
    boolean retriedWhenUnmapped() {
      return this == FAILED_FETCH;
    }

    /** Whether the conclusion, mapped onto a numbered row, schedules a retry (design 4). */
    boolean retriedWhenMapped() {
      return this != DROPPED && this != ACCESS_REFUSED_KEY;
    }
  }

  /**
   * Allocates a number for {@code objectId} and leaves it open: the archive is down, so the seed
   * event ends {@code FAILED_ISSUANCE} with the issuance row behind it. The archive is healed
   * after.
   */
  static String numberWithArchiveDown(IssuanceTestHarness harness, String objectId) {
    harness.source().with(TestInvoices.finalised(objectId));
    String seed = harness.receive("invoice.finalized", objectId);
    harness
        .archive()
        .failWith(new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "archive is down"));
    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(seed);
    harness.archive().heal();
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.issuance(objectId)).as("the seed holds a number").isPresent();
    return seed;
  }

  /** The event a cell runs: its own type, mode and account, the same invoice as the seed. */
  static String receiveFor(IssuanceTestHarness harness, Conclusion conclusion, String objectId) {
    return switch (conclusion) {
      case DROPPED -> harness.receive("invoice.updated", objectId);
      case REFUSED_MODE ->
          harness.receive("invoice.finalized", objectId, FakeStripeSource.PINNED, false, "");
      case REFUSED_ACCOUNT ->
          harness.receive(
              "invoice.finalized", objectId, FakeStripeSource.PINNED, true, "acct_elsewhere");
      default -> harness.receive("invoice.finalized", objectId);
    };
  }

  /** Walks a received row to {@code start} through declared edges only. */
  static void moveTo(IssuanceTestHarness harness, String eventId, InboundState start) {
    JdbcPath path = new JdbcPath(harness, eventId);
    switch (start) {
      case RECEIVED -> {}
      case FETCHED -> path.to(InboundState.FETCHED, "", null);
      case MAPPED -> path.to(InboundState.FETCHED, "", null).to(InboundState.MAPPED, "", null);
      case PARKED ->
          path.to(InboundState.FETCHED, "", null)
              .to(InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, Duration.ofMinutes(1));
      case FAILED_FETCH ->
          path.to(InboundState.FAILED_FETCH, ErrorCodes.STRIPE_UNAVAILABLE, Duration.ofMinutes(1));
      case FAILED_ISSUANCE ->
          path.to(InboundState.FETCHED, "", null)
              .to(
                  InboundState.FAILED_ISSUANCE,
                  ErrorCodes.ARCHIVE_UNAVAILABLE,
                  Duration.ofMinutes(1));
      case REFUSED_VERSION_SKEW ->
          path.to(InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, null);
      case REFUSED_MODE -> path.to(InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH, null);
      case REFUSED_ACCOUNT ->
          path.to(InboundState.REFUSED_ACCOUNT, ErrorCodes.UNKNOWN_ACCOUNT, null);
      case COMPLETED -> path.to(InboundState.COMPLETED, "", null);
      case DROPPED -> path.to(InboundState.DROPPED, ErrorCodes.UNSUBSCRIBED_EVENT_TYPE, null);
      case FAILED_MAPPING ->
          path.to(InboundState.FETCHED, "", null)
              .to(InboundState.FAILED_MAPPING, ErrorCodes.CLOSED_FISCAL_YEAR, null);
      case FAILED_TOTALS ->
          path.to(InboundState.FETCHED, "", null)
              .to(InboundState.FAILED_TOTALS, ErrorCodes.TOTALS_MISMATCH, null);
    }
    assertThat(harness.inbound().find(eventId).orElseThrow().state()).isEqualTo(start);
  }

  /** Sets up the condition a cell concludes on, then runs the event once. */
  static IssuanceUnitOfWork.Outcome run(
      IssuanceTestHarness harness, Conclusion conclusion, String eventId) {
    return switch (conclusion) {
      case REFUSED_VERSION_SKEW -> harness.unitOfWorkPinnedTo("2099-01-01.other").process(eventId);
      case FAILED_FETCH -> {
        harness
            .source()
            .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));
        yield harness.unitOfWork().process(eventId);
      }
      case ACCESS_REFUSED_KEY -> {
        harness
            .source()
            .breakWith(new EInvoiceException(ACCESS_REFUSED, "the key was refused (status 401)"));
        yield harness.unitOfWork().process(eventId);
      }
      default -> harness.unitOfWork().process(eventId);
    };
  }

  static InboundEvent row(IssuanceTestHarness harness, String eventId) {
    return harness.inbound().find(eventId).orElseThrow();
  }

  static String number(IssuanceTestHarness harness, String objectId) {
    return harness.issuance(objectId).map(Issuance::legalNumber).orElseThrow().value();
  }

  /** The states a run concludes in before it has fetched: the table's columns. */
  static final List<InboundState> PRE_FETCHED =
      List.of(
          InboundState.DROPPED,
          InboundState.REFUSED_VERSION_SKEW,
          InboundState.REFUSED_MODE,
          InboundState.REFUSED_ACCOUNT,
          InboundState.FAILED_FETCH,
          InboundState.RECEIVED);

  private record JdbcPath(IssuanceTestHarness harness, String eventId) {
    JdbcPath to(InboundState next, String code, Duration retryIn) {
      harness.inbound().transition(eventId, next, code, harness.now(), retryIn);
      return this;
    }
  }
}
