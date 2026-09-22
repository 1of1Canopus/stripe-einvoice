package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.IssuanceAnchor;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.IssuanceEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks the issuance log in sequence order, recomputes every hash, compares the head with the
 * anchor, and then asks the one question a chain over an append-only log cannot answer on its own:
 * does every disposed issuance row agree with its latest chained event?
 *
 * <p>That cross-check is what catches the path no scan and no grant can close - the host's own raw
 * JDBC against our tables. A row inserted or a state rewritten out of band has no matching chained
 * event, and this reports {@link Status#BROKEN} rather than a clean trail.
 *
 * <p>Holds a keyring ({@code keyId -> secret}), because the key id is inside the hashed material
 * from row 1, so a rotation window has rows signed by different ids in one still-keyed log. An id
 * the keyring does not hold is {@code BROKEN}, never skipped.
 */
public final class IssuanceChainVerifier {

  private static final int PAGE = 500;
  private static final Logger log = LoggerFactory.getLogger(IssuanceChainVerifier.class);

  private final IssuanceEventReader reader;
  private final IssuanceAnchor anchor;
  private final Map<String, IssuanceChain> keyring;

  public IssuanceChainVerifier(
      IssuanceEventReader reader, IssuanceAnchor anchor, Map<String, byte[]> keyring) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(keyring, "keyring");
    Map<String, IssuanceChain> chains = new LinkedHashMap<>();
    keyring.forEach((id, secret) -> chains.put(id, IssuanceChain.keyed(secret, id)));
    this.keyring = Map.copyOf(chains);
  }

  public enum Status {
    /** No rows and no anchor. */
    EMPTY,
    /** Every hash recomputes, the head matches the anchor, and the log is keyed. */
    INTACT,
    /**
     * Every hash recomputes and the head matches, but the log is unkeyed: its integrity rests only
     * on database privilege separation, not on a secret. Deliberately a different word from {@link
     * #INTACT}, so a clean unkeyed result never reads like a clean keyed one.
     */
    INTACT_UNKEYED,
    /**
     * A row does not recompute, claims the wrong chain version, claims an unknown key id, or a
     * disposed issuance row has no chained event that agrees with it.
     */
    BROKEN,
    /** The rows recompute but the head hash or the row count differs from the anchor. */
    ANCHOR_MISMATCH,
    /**
     * There is no anchor row and the log is not empty. Reported unconditionally, keyed or not:
     * without the anchor there is no attacker-unwritable record of what the rows ought to claim,
     * and the verifier refuses to guess.
     */
    NO_ANCHOR
  }

  /**
   * @param status outcome
   * @param verified rows that recomputed
   * @param brokenAtSequence sequence of the first failing row, or -1
   * @param headHash hash of the newest verified row, or GENESIS
   * @param anchored whether an anchor row was available
   * @param keyed the log's recorded mode, or false when not anchored
   * @param keyIds every distinct key id seen on a row that recomputed
   * @param unchainedDispositions disposed issuance rows with no agreeing chained event
   */
  public record Report(
      Status status,
      long verified,
      long brokenAtSequence,
      String headHash,
      boolean anchored,
      boolean keyed,
      Set<String> keyIds,
      long unchainedDispositions) {

    public boolean intact() {
      return status == Status.INTACT || status == Status.INTACT_UNKEYED || status == Status.EMPTY;
    }
  }

  public Report verify() {
    Optional<IssuanceAnchor.Anchor> anchored = anchor.anchor();
    if (anchored.isEmpty()) {
      List<IssuanceEvent> probe = reader.readAfter(0, 1);
      if (probe.isEmpty()) {
        long unchained = countUnchained(Set.of());
        if (unchained > 0) {
          log.warn(
              "einvoice: {} disposed issuance row(s) exist with an empty issuance log."
                  + " Reporting BROKEN.",
              unchained);
          return new Report(
              Status.BROKEN, 0, -1, IssuanceChain.GENESIS, false, false, Set.of(), unchained);
        }
        return new Report(Status.EMPTY, 0, -1, IssuanceChain.GENESIS, false, false, Set.of(), 0);
      }
      log.warn(
          "einvoice: verifying a non-empty issuance log with no anchor row; refusing to report"
              + " INTACT. Reporting NO_ANCHOR.");
      return new Report(Status.NO_ANCHOR, 0, -1, IssuanceChain.GENESIS, false, false, Set.of(), 0);
    }
    boolean expectKeyed = anchored.get().keyed();
    String expectedVersion =
        expectKeyed ? IssuanceChain.KEYED_VERSION : IssuanceChain.CANONICAL_VERSION;

    String prev = IssuanceChain.GENESIS;
    long after = 0;
    long count = 0;
    Set<String> keyIdsSeen = new LinkedHashSet<>();
    Set<String> chained = new LinkedHashSet<>();
    while (true) {
      List<IssuanceEvent> page;
      try {
        page = reader.readAfter(after, PAGE);
      } catch (EInvoiceException e) {
        // A row that does not even decode was written by exactly the actor this log exists to
        // detect. The verifier reports BROKEN rather than letting a typed decode error escape,
        // which would make the verifier fail instead of reporting the tamper it found.
        log.warn(
            "einvoice: the issuance log did not decode while verifying after seq={}: {}",
            after,
            e.code());
        return new Report(
            Status.BROKEN,
            count,
            after,
            prev,
            true,
            expectKeyed,
            keyIdsSeen,
            countUnchained(chained));
      }
      if (page.isEmpty()) {
        break;
      }
      for (IssuanceEvent e : page) {
        IssuanceChain rowChain = chainForRow(expectKeyed, e.keyId());
        if (!expectedVersion.equals(e.chainVersion())
            || rowChain == null
            || !rowChain.verify(e, prev)) {
          return new Report(
              Status.BROKEN,
              count,
              e.sequence(),
              prev,
              true,
              expectKeyed,
              keyIdsSeen,
              countUnchained(chained));
        }
        keyIdsSeen.add(e.keyId());
        chained.add(dispositionKey(e));
        prev = e.hash();
        after = e.sequence();
        count++;
      }
    }
    long unchained = countUnchained(chained);
    if (anchored.get().rowCount() != count || !anchored.get().headHash().equals(prev)) {
      return new Report(
          Status.ANCHOR_MISMATCH, count, -1, prev, true, expectKeyed, keyIdsSeen, unchained);
    }
    if (unchained > 0) {
      log.warn(
          "einvoice: {} disposed issuance row(s) have no chained event that agrees with them."
              + " Reporting BROKEN.",
          unchained);
      return new Report(Status.BROKEN, count, -1, prev, true, expectKeyed, keyIdsSeen, unchained);
    }
    if (count == 0) {
      return new Report(Status.EMPTY, 0, -1, prev, true, expectKeyed, keyIdsSeen, 0);
    }
    return new Report(
        expectKeyed ? Status.INTACT : Status.INTACT_UNKEYED,
        count,
        -1,
        prev,
        true,
        expectKeyed,
        keyIdsSeen,
        0);
  }

  // D1-02: keyed on the row's identity - seller, mode, series, fiscal year, source object id,
  // legal number and state - not merely on seller|mode|number|state. RC-02 adds the two void
  // columns: the justification for a burned number is chained, is what an auditor is shown, and
  // lives on a row the runtime role may UPDATE, so a rewrite of it must read as BROKEN exactly as
  // a rewritten identity does. That narrower key let one
  // legitimate chained event vouch for every row that happened to share those four values,
  // including a forged, already-disposed row inserted out of band in a different fiscal year.
  private long countUnchained(Set<String> chained) {
    return reader.disposedIssuances().stream()
        .map(
            d ->
                d.sellerId()
                    + "|"
                    + d.mode()
                    + "|"
                    + d.series()
                    + "|"
                    + d.fiscalYear()
                    + "|"
                    + d.stripeInvoiceId()
                    + "|"
                    + d.legalNumber()
                    + "|"
                    + d.state()
                    + "|"
                    + d.voidReason()
                    + "|"
                    + d.voidRuleId())
        .filter(key -> !chained.contains(key))
        .count();
  }

  private static String dispositionKey(IssuanceEvent e) {
    return e.seriesKey().sellerId()
        + "|"
        + e.seriesKey().mode().wire()
        + "|"
        + e.seriesKey().series()
        + "|"
        + e.seriesKey().fiscalYear()
        + "|"
        + e.stripeInvoiceId()
        + "|"
        + e.legalNumber().value()
        + "|"
        + e.state().name()
        + "|"
        + e.voidReason()
        + "|"
        + e.voidRuleId();
  }

  private IssuanceChain chainForRow(boolean expectKeyed, String rowKeyId) {
    if (!expectKeyed) {
      return IssuanceChain.UNKEYED_KEY_ID.equals(rowKeyId) ? IssuanceChain.unkeyed() : null;
    }
    if (IssuanceChain.UNKEYED_KEY_ID.equals(rowKeyId)) {
      return null;
    }
    return keyring.get(rowKeyId);
  }
}
