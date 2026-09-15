package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A store for the tests, in two behaviours: the write-once one every conforming store must have,
 * and the one that silently overwrites - which is what a store that ignores an unknown conditional
 * header actually does, and the reason the startup probe exists (I-06).
 */
public final class InMemoryArchiveStore implements ArchiveStore {

  private final Map<String, byte[]> objects = new LinkedHashMap<>();
  private final boolean overwrites;
  private final boolean declaresAtomic;
  private RuntimeException failure;

  public InMemoryArchiveStore() {
    this(false, true);
  }

  public static InMemoryArchiveStore silentlyOverwriting() {
    return new InMemoryArchiveStore(true, true);
  }

  public InMemoryArchiveStore(boolean overwrites, boolean declaresAtomic) {
    this.overwrites = overwrites;
    this.declaresAtomic = declaresAtomic;
  }

  /** Makes every later write fail, for the crash and outage tests. */
  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  public void heal() {
    this.failure = null;
  }

  /** Writes an object behind the module's back, for the orphan-detection tests. */
  public void putDirectly(String key, byte[] bytes) {
    objects.put(key, bytes.clone());
  }

  public int size() {
    return objects.size();
  }

  @Override
  public boolean supportsAtomicCreate() {
    return declaresAtomic;
  }

  @Override
  public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
    if (failure != null) {
      throw failure;
    }
    byte[] existing = objects.get(key.value());
    if (existing != null && !overwrites) {
      if (MessageDigest.isEqual(existing, bytes)) {
        return WriteResult.ALREADY_IDENTICAL;
      }
      throw new EInvoiceException(
          ErrorCodes.ARCHIVE_CONTENT_CONFLICT, "the archive key already holds different bytes");
    }
    objects.put(key.value(), bytes.clone());
    return WriteResult.CREATED;
  }

  @Override
  public Optional<byte[]> get(ArchiveKey key) {
    byte[] bytes = objects.get(key.value());
    return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
  }

  @Override
  public List<String> list(String prefix, int limit) {
    List<String> keys = new ArrayList<>();
    for (String key : objects.keySet()) {
      if (key.startsWith(prefix == null ? "" : prefix) && keys.size() < limit) {
        keys.add(key);
      }
    }
    return keys;
  }

  @Override
  public String describe() {
    return "in-memory archive (test)";
  }
}
