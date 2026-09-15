package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The durable inbound record against a real PostgreSQL: idempotency, retries, and the body. */
class InboundEventStoreTest {

  private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
  private static final byte[] BODY =
      "{\"id\":\"evt\",\"type\":\"invoice.finalized\"}".getBytes(StandardCharsets.UTF_8);

  private static JdbcInboundEventStore store() {
    return new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
  }

  private static InboundEvent received(String eventId) {
    EventIdentity identity =
        new EventIdentity(eventId, "invoice.finalized", "2026-03-31.clover", true, "", "in_1");
    return InboundEvent.received(identity, Mode.LIVE, "primary", BODY, NOW);
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  void the_first_delivery_is_recorded_with_its_body_and_a_duplicate_is_a_no_op() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    assertThat(store.record(received(id), BODY)).isTrue();
    // At-least-once delivery is Stripe's documented behaviour, so a redelivery is normal traffic:
    // it must be a no-op that the endpoint still answers 200 to, never a second row or an error.
    assertThat(store.record(received(id), BODY)).isFalse();

    InboundEvent stored = store.find(id).orElseThrow();
    assertThat(stored.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(stored.signatureKeyId()).isEqualTo("primary");
    assertThat(stored.bodyPresent()).isTrue();
    assertThat(stored.bodySha256()).isEqualTo(Hashes.sha256Hex(BODY));
    assertThat(store.body(id)).contains(BODY);
  }

  @Test
  void a_raw_body_is_nulled_once_the_issuance_is_terminal() {
    // I-07. The body is the largest store of buyer data in the module; it lives exactly as long as
    // the event can still run, and the SHA-256 outlives it so what arrived stays provable.
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    store.transition(id, InboundState.FETCHED, "", NOW, null);
    assertThat(store.body(id)).contains(BODY);

    InboundEvent done = store.transition(id, InboundState.COMPLETED, "", NOW, null);
    assertThat(done.bodyPresent()).isFalse();
    assertThat(store.body(id)).isEmpty();
    assertThat(done.bodySha256()).isEqualTo(Hashes.sha256Hex(BODY));
  }

  @Test
  void a_refused_event_keeps_its_body_so_it_can_be_replayed_after_the_pin_is_updated() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    InboundEvent refused =
        store.transition(
            id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, NOW, null);

    assertThat(refused.state()).isEqualTo(InboundState.REFUSED_VERSION_SKEW);
    assertThat(refused.lastCode()).isEqualTo(ErrorCodes.API_VERSION_SKEW);
    assertThat(refused.bodyPresent()).isTrue();

    // The replay: same row, same path, once the operator re-pinned the API version.
    InboundEvent replayed = store.transition(id, InboundState.FETCHED, "", NOW, null);
    assertThat(replayed.state()).isEqualTo(InboundState.FETCHED);
  }

  @Test
  void a_backwards_transition_is_refused_by_the_state_machine_not_by_the_column() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    store.transition(id, InboundState.COMPLETED, "", NOW, null);
    assertThatThrownBy(() -> store.transition(id, InboundState.FETCHED, "", NOW, null))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_ILLEGAL_TRANSITION);
  }

  @Test
  void the_sweeper_sees_what_is_running_and_what_is_retryable_and_nothing_final() {
    JdbcInboundEventStore store = store();
    String running = freshId();
    String retryable = freshId();
    String finalFailure = freshId();
    String completed = freshId();
    store.record(received(running), BODY);
    store.record(received(retryable), BODY);
    store.record(received(finalFailure), BODY);
    store.record(received(completed), BODY);
    store.transition(
        retryable,
        InboundState.FAILED_FETCH,
        ErrorCodes.STRIPE_UNAVAILABLE,
        NOW,
        Duration.ofMinutes(5));
    store.transition(finalFailure, InboundState.FETCHED, "", NOW, null);
    store.transition(
        finalFailure, InboundState.FAILED_TOTALS, ErrorCodes.TOTALS_MISMATCH, NOW, null);
    store.transition(completed, InboundState.COMPLETED, "", NOW, null);

    List<String> due =
        store.due(NOW.plus(Duration.ofMinutes(10)), Duration.ofHours(72), 100).stream()
            .map(InboundEvent::eventId)
            .toList();
    assertThat(due).contains(running, retryable).doesNotContain(finalFailure, completed);
  }

  @Test
  void a_retryable_terminal_is_not_re_picked_before_its_time_or_after_the_ceiling() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    store.transition(
        id, InboundState.FAILED_FETCH, ErrorCodes.STRIPE_UNAVAILABLE, NOW, Duration.ofHours(1));

    assertThat(ids(store.due(NOW.plusSeconds(60), Duration.ofHours(72), 100))).doesNotContain(id);
    assertThat(ids(store.due(NOW.plus(Duration.ofHours(2)), Duration.ofHours(72), 100)))
        .contains(id);
    // Past the ceiling the event is final and becomes a compliance finding instead (I-08).
    assertThat(ids(store.due(NOW.plus(Duration.ofDays(9)), Duration.ofHours(72), 100)))
        .doesNotContain(id);
  }

  private static List<String> ids(List<InboundEvent> events) {
    return events.stream().map(InboundEvent::eventId).toList();
  }

  @Test
  void the_resolved_seller_is_bound_from_the_account_and_never_from_the_payload() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    assertThat(store.find(id).orElseThrow().sellerId()).isEmpty();
    assertThat(store.bindSeller(id, "acme", NOW).sellerId()).isEqualTo("acme");
  }

  @Test
  void the_failing_validation_rule_is_recorded_so_the_operator_void_can_name_it() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    store.record(received(id), BODY);
    store.transition(id, InboundState.FETCHED, "", NOW, null);
    store.transition(
        id, InboundState.FAILED_ISSUANCE, ErrorCodes.VALIDATION_REFUSED, "BR-CO-10", NOW, null);
    assertThat(store.lastRuleId(id)).contains("BR-CO-10");
  }

  @Test
  void the_purge_removes_only_what_is_older_than_the_retention_ceiling() {
    JdbcInboundEventStore store = store();
    String old = freshId();
    String recent = freshId();
    store.record(received(old), BODY);
    PostgresSupport.execute(
        "UPDATE einvoice_inbound_event SET received_at = now() - interval '90 days'"
            + " WHERE event_id = '"
            + old
            + "'");
    store.record(received(recent), BODY);

    int purged = store.purgeOlderThan(Instant.now().minus(Duration.ofDays(30)), 100);
    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(store.find(old)).isEmpty();
    assertThat(store.find(recent)).isPresent();
  }

  @Test
  void the_counts_by_state_carry_no_buyer_field() {
    JdbcInboundEventStore store = store();
    store.record(received(freshId()), BODY);
    assertThat(store.countsByState())
        .isNotEmpty()
        .allSatisfy(count -> assertThat(count.count()).isPositive());
  }

  @Test
  void a_transition_on_an_event_nobody_recorded_is_refused() {
    assertThatThrownBy(
            () -> store().transition("evt_does_not_exist", InboundState.FETCHED, "", NOW, null))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }
}
