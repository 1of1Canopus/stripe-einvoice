package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sweep's fifth check (D-RTR-4): an archive port that counts lookups per key and can fail on
 * one key, and a capture of the WARN lines the check writes.
 */
final class DisposedArchiveFixture {

  private DisposedArchiveFixture() {}

  /** Counts {@code exists} and {@code get} per key; throws on {@code failing} when it is set. */
  static final class Counting implements ArchiveStore {

    private final ArchiveStore delegate;
    private final Map<String, Integer> exists = new ConcurrentHashMap<>();
    private final Map<String, Integer> gets = new ConcurrentHashMap<>();
    private volatile String failingKey = "";
    private volatile RuntimeException failure;
    private final List<String> calls;

    Counting(ArchiveStore delegate) {
      this(delegate, new CopyOnWriteArrayList<>());
    }

    /** Every call is also appended to {@code calls}, as {@code exists:<key>}, {@code get:<key>}. */
    Counting(ArchiveStore delegate, List<String> calls) {
      this.delegate = delegate;
      this.calls = calls;
    }

    /** Every lookup of {@code key} throws {@code thrown}. */
    Counting failingOn(String key, RuntimeException thrown) {
      this.failingKey = key;
      this.failure = thrown;
      return this;
    }

    int existsCalls(String key) {
      return exists.getOrDefault(key, 0);
    }

    int getCalls(String key) {
      return gets.getOrDefault(key, 0);
    }

    int lookups(String key) {
      return existsCalls(key) + getCalls(key);
    }

    int allLookups() {
      return exists.values().stream().mapToInt(Integer::intValue).sum()
          + gets.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public boolean supportsAtomicCreate() {
      return delegate.supportsAtomicCreate();
    }

    @Override
    public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public boolean exists(ArchiveKey key) {
      exists.merge(key.value(), 1, Integer::sum);
      calls.add("exists:" + key.value());
      if (key.value().equals(failingKey)) {
        throw failure;
      }
      return delegate.exists(key);
    }

    @Override
    public Optional<byte[]> get(ArchiveKey key) {
      gets.merge(key.value(), 1, Integer::sum);
      calls.add("get:" + key.value());
      if (key.value().equals(failingKey)) {
        throw failure;
      }
      return delegate.get(key);
    }

    @Override
    public List<String> list(String prefix, int limit) {
      calls.add("list:" + prefix);
      return delegate.list(prefix, limit);
    }

    @Override
    public String describe() {
      return "counting archive (test)";
    }
  }

  /**
   * A reader that appends each method name to {@code calls} and throws {@code DEI-241} from {@code
   * failing}, so a test can see what the sweep read, in which order, and what a failed read skips.
   */
  static IssuanceReader recordingReader(IssuanceReader target, List<String> calls, String failing) {
    return (IssuanceReader)
        Proxy.newProxyInstance(
            IssuanceReader.class.getClassLoader(),
            new Class<?>[] {IssuanceReader.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              if (method.getName().equals(failing)) {
                throw new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "test: read failed");
              }
              try {
                return method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** Everything written to the test logger while {@code action} runs. */
  static String log(Runnable action) {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  /**
   * The WARN lines written while {@code action} runs that name an object at a disposed key. The
   * core's test logger is slf4j-simple, which resolves {@code System.err} at each write.
   */
  static List<String> disposedWarnings(Runnable action) {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    List<String> out = new ArrayList<>();
    captured
        .toString(StandardCharsets.UTF_8)
        .lines()
        .filter(l -> l.contains(" WARN ") && l.contains("burned or voided number"))
        .forEach(out::add);
    return out;
  }
}
