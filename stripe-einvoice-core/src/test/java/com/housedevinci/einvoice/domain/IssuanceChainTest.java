package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The chain's canonical form and its key handling, with no database in the way. */
class IssuanceChainTest {

  private static final byte[] SECRET =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Instant WHEN = Instant.parse("2026-01-15T10:00:00Z");

  private static IssuanceEvent event(String reason, String ruleId) {
    Issuance issuance =
        new Issuance(
            1L,
            new SeriesKey("acme", "DEFAULT", 2026, Mode.LIVE),
            "in_1",
            "",
            "STRIPE-1",
            new LegalNumber("INV-2026-000001", 1),
            WHEN,
            WHEN,
            "",
            "",
            "fr-2026.1",
            IssuanceState.VOID_UNUSED,
            reason,
            ruleId);
    return IssuanceEvent.voided(issuance, WHEN);
  }

  @Test
  void the_canonical_form_is_length_prefixed_so_a_boundary_cannot_be_moved() {
    // "ab" + "c" and "a" + "bc" join to the same string; they must not hash to the same value, or a
    // rewrite that moves one character between two fields would go undetected.
    IssuanceChain chain = IssuanceChain.keyed(SECRET, "k1");
    String left = chain.hashOf(event("ab", "c"), IssuanceChain.GENESIS);
    String right = chain.hashOf(event("a", "bc"), IssuanceChain.GENESIS);
    assertThat(left).isNotEqualTo(right);
  }

  @Test
  void the_key_id_is_inside_the_hashed_material_from_row_one() {
    // So a rotation is data rather than a format break, and a row signed by a retired id still
    // verifies against the keyring rather than being skipped.
    String underK1 =
        IssuanceChain.keyed(SECRET, "k1").hashOf(event("r", ""), IssuanceChain.GENESIS);
    String underK2 =
        IssuanceChain.keyed(SECRET, "k2").hashOf(event("r", ""), IssuanceChain.GENESIS);
    assertThat(underK1).isNotEqualTo(underK2);
  }

  @Test
  void a_keyed_row_is_not_recomputable_by_an_unkeyed_verifier() {
    IssuanceEvent keyed =
        IssuanceChain.keyed(SECRET, "k1").link(event("r", ""), IssuanceChain.GENESIS);
    assertThat(keyed.chainVersion()).isEqualTo(IssuanceChain.KEYED_VERSION);
    assertThat(IssuanceChain.unkeyed().verify(keyed, IssuanceChain.GENESIS)).isFalse();
  }

  @Test
  void a_short_secret_or_a_reserved_key_id_is_refused_at_configuration_time() {
    assertThatThrownBy(
            () -> IssuanceChain.keyed("too short".getBytes(StandardCharsets.UTF_8), "k1"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
    assertThatThrownBy(() -> IssuanceChain.keyed(SECRET, "none"))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_linked_row_verifies_against_its_predecessor_and_against_nothing_else() {
    IssuanceChain chain = IssuanceChain.keyed(SECRET, "k1");
    IssuanceEvent first = chain.link(event("first", ""), IssuanceChain.GENESIS);
    IssuanceEvent second = chain.link(event("second", ""), first.hash());

    assertThat(chain.verify(first, IssuanceChain.GENESIS)).isTrue();
    assertThat(chain.verify(second, first.hash())).isTrue();
    assertThat(chain.verify(second, IssuanceChain.GENESIS)).isFalse();
  }

  @Test
  void an_unkeyed_chain_reports_its_own_version_and_key_id() {
    IssuanceChain unkeyed = IssuanceChain.unkeyed();
    assertThat(unkeyed.isKeyed()).isFalse();
    assertThat(unkeyed.version()).isEqualTo(IssuanceChain.CANONICAL_VERSION);
    assertThat(unkeyed.keyId()).isEqualTo(IssuanceChain.UNKEYED_KEY_ID);
  }
}
