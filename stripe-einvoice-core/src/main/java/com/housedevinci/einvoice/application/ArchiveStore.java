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

  /**
   * The stored bytes, for the integrity check, for an auditor read, and for a resumed run that must
   * learn whether its own key already holds its document before it renders again.
   *
   * <p>Empty means the key holds no object. A failure to read throws, and the module treats a throw
   * as "not known yet" and retries; it never treats it as "absent", because a burn on an unread key
   * could record a number as unused while its document sits in the archive.
   *
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-241} when the store is
   *     unreachable
   */
  Optional<byte[]> get(ArchiveKey key);

  /**
   * True when an object exists at the key.
   *
   * <p>The default reads the bytes, which is correct everywhere and wasteful on a remote store;
   * implementations that can ask the question directly (a HEAD, a stat) override it.
   */
  default boolean exists(ArchiveKey key) {
    return get(key).isPresent();
  }

  /**
   * The first keys under a prefix, in UTF-8 byte order. Bounded by the caller. The default reads
   * one {@link #listAfter} page from the start of the prefix.
   */
  default List<String> list(String prefix, int limit) {
    return listAfter(prefix, "", limit).keys();
  }

  /**
   * Keys under {@code prefix} that sort strictly after {@code startAfter}, in UTF-8 byte order, at
   * most {@code limit} of them: the orphan walk's one listing per pass (D-PR52-3).
   *
   * <p><b>Contract.</b> Every key starts with {@code prefix}. The keys are strictly ascending in
   * UTF-8 byte order ({@link ArchiveListing#UTF8_ORDER}) and each is strictly after {@code
   * startAfter}; an empty {@code startAfter} means the start of the prefix. {@link
   * ArchiveListing#endOfFolder()} is true only when the store has said there is no further key,
   * never because the page came back short. No key appears twice. No key is longer than {@link
   * ArchiveListing#MAX_KEY_BYTES} UTF-8 bytes: a store that can hold a longer name lists it cut and
   * marked, never whole (D-PR57-3). A listing that breaks this is refused by the walk with {@code
   * DEI-284}, and nothing moves.
   *
   * <p>No default: a store that can only list from the start would make every pass read the whole
   * folder into memory, which is the costly design the walk exists to avoid (D-PR52-3, C12).
   *
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-241} when the store is
   *     unreachable
   */
  ArchiveListing listAfter(String prefix, String startAfter, int limit);

  /** One line for the startup log: the kind of store and its root. Never a credential. */
  String describe();
}
