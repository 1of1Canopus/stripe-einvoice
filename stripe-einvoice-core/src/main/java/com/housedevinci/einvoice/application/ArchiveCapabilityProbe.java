package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves at startup that the archive store really refuses to overwrite (I-06).
 *
 * <p>{@code If-None-Match: *} on PutObject is an AWS S3 capability from late 2024. MinIO,
 * Cloudflare R2, Ceph/RGW and most on-premises gateways support it partially or not at all, and a
 * store that ignores an unknown conditional header <b>overwrites and returns 200</b>. The design's
 * central anti-duplication argument - "one number, two documents is impossible, because the archive
 * is write-once" - would then rest on a header the store discarded, and the failure would be
 * silent: the first writer's bytes gone, every later hash check passing against the new object.
 *
 * <p>So the claim is not taken. A real conditional write runs against a scratch key with two
 * different byte strings, and a store that accepts the second one is refused by default.
 */
public final class ArchiveCapabilityProbe {

  /** Objects the probe leaves behind. Documented, prunable, and outside every document prefix. */
  public static final String SCRATCH_PREFIX = "_probe/";

  private static final Logger log = LoggerFactory.getLogger(ArchiveCapabilityProbe.class);

  private ArchiveCapabilityProbe() {}

  /**
   * @param uniqueSuffix a value that differs per startup: the scratch key must not already exist,
   *     or a probe would refuse a perfectly good store on its second boot
   * @param allowNonAtomic the WARNed weaker mode; it does not restore atomicity and does not
   *     pretend to
   */
  public static void probe(ArchiveStore store, String uniqueSuffix, boolean allowNonAtomic) {
    ArchiveKey key =
        new ArchiveKey(SCRATCH_PREFIX + "atomic-create-" + sanitise(uniqueSuffix) + ".probe");
    byte[] first = "einvoice-probe-a".getBytes(StandardCharsets.UTF_8);
    byte[] second = "einvoice-probe-b".getBytes(StandardCharsets.UTF_8);
    boolean atomic;
    try {
      store.putIfAbsent(key, first);
      store.putIfAbsent(key, second);
      atomic = false; // a second write with different bytes succeeded: the store overwrites
    } catch (EInvoiceException refused) {
      if (!ErrorCodes.ARCHIVE_CONTENT_CONFLICT.equals(refused.code())) {
        throw refused; // an unreachable store is an outage, not a capability answer
      }
      atomic = true;
    }
    if (atomic) {
      if (!store.supportsAtomicCreate()) {
        log.warn(
            "einvoice: {} refuses to overwrite an existing key, but declares"
                + " supportsAtomicCreate()=false. The probe is believed and the declaration is"
                + " not; fix the declaration.",
            store.describe());
      }
      return;
    }
    if (!allowNonAtomic) {
      throw new EInvoiceException(
          ErrorCodes.ARCHIVE_NOT_ATOMIC,
          "this archive store overwrote an existing key with different bytes during the startup"
              + " probe, so it cannot create an object only if it is absent. Write-once is what"
              + " makes one number one document when a retry and a crash overlap, so the store is"
              + " refused. Use a store that supports a conditional create, or set"
              + " einvoice.archive.allow-non-atomic-store=true and accept a read-back check that"
              + " narrows the window without closing it.");
    }
    log.warn(
        "einvoice: einvoice.archive.allow-non-atomic-store=true and {} overwrote a key during the"
            + " startup probe. Every write now does a read-back comparison, which narrows the"
            + " window between two concurrent writers and does not close it: two processes writing"
            + " the same key at the same moment can still leave one document's bytes behind"
            + " another's.",
        store.describe());
  }

  private static String sanitise(String suffix) {
    StringBuilder out = new StringBuilder();
    for (char c : (suffix == null ? "" : suffix).toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') {
        out.append(c);
      }
    }
    if (out.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "the archive probe needs a value that differs per startup");
    }
    return out.toString();
  }
}
