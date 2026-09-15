package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import java.util.List;
import java.util.Optional;

/**
 * Where the legal originals live: a filesystem directory, an S3-compatible bucket, or a store the
 * host application supplies.
 *
 * <p><b>The contract is write-once, and it is not advisory.</b> The database and the object store
 * cannot commit together, so this module makes the write idempotent by content instead: the key
 * carries the SHA-256 of the exact bytes, and a retry writes the same key with the same bytes.
 * {@link #putIfAbsent} therefore has exactly three outcomes and no fourth:
 *
 * <ul>
 *   <li>the key did not exist and now holds these bytes - {@link WriteResult#CREATED};
 *   <li>the key already holds <em>these exact bytes</em> - {@link WriteResult#ALREADY_IDENTICAL},
 *       which is success, because that is what a retry of the same input looks like;
 *   <li>the key already holds <em>different</em> bytes - a refusal with {@code DEI-240}, an alert,
 *       and never an overwrite.
 * </ul>
 *
 * <p><b>{@link #supportsAtomicCreate()} is a claim, and the module does not take claims</b> (I-06).
 * At startup the capability is probed with a real conditional write against a scratch key, because
 * a store that ignores an unknown conditional header overwrites and answers 200 - which would leave
 * the whole anti-duplication argument resting on a header the store discarded, silently. A store
 * that fails the probe is refused unless the operator sets the WARNed weaker mode.
 *
 * <p><b>There is no delete.</b> Retention expiry is an operator action outside this module (spec
 * path 28), and an archive that this module can empty is not evidence.
 */
public interface ArchiveStore {

  /** What a write did. Both values are success; a conflict is an exception, not a value. */
  enum WriteResult {
    /** The object did not exist and now holds these bytes. */
    CREATED,
    /** The object already held these exact bytes: a retry of the same input. */
    ALREADY_IDENTICAL
  }

  /**
   * True when this store can create an object <em>only if it is absent</em>, atomically - {@code
   * If-None-Match: *} on S3, {@code O_EXCL} on a filesystem.
   *
   * <p>A store that answers {@code true} is probed at startup before the answer is believed.
   */
  boolean supportsAtomicCreate();

  /**
   * Writes the bytes at the key unless the key exists with different content.
   *
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-240} when the key exists
   *     with different bytes, {@code DEI-241} when the store is unreachable
   */
  WriteResult putIfAbsent(ArchiveKey key, byte[] bytes);

  /** The stored bytes, for the integrity check and for an auditor read. */
  Optional<byte[]> get(ArchiveKey key);

  /** Keys under a prefix, for reconciliation's orphan-object direction. Bounded by the caller. */
  List<String> list(String prefix, int limit);

  /** One line for the startup log: the kind of store and its root. Never a credential. */
  String describe();
}
