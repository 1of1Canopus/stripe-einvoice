package com.housedevinci.einvoice.adapter.file;

import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The archive on a local filesystem or a mounted volume.
 *
 * <p>Write-once is {@code CREATE_NEW}, which is {@code O_EXCL}: the create and the exclusivity
 * check are one operation in the kernel, so two processes writing the same key cannot both believe
 * they created it. A key that already exists is read back and compared byte for byte - identical is
 * success, different is {@code DEI-240} and never an overwrite.
 *
 * <p>The bytes are written to a temporary file in the same directory and then linked into place
 * atomically, so a crash mid-write cannot leave a short file at a key that the next run will read
 * as "already there with different bytes".
 */
public final class FilesystemArchiveStore implements ArchiveStore {

  private final Path root;

  public FilesystemArchiveStore(Path root) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
  }

  @Override
  public boolean supportsAtomicCreate() {
    return true;
  }

  @Override
  public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".einvoice-", ".part");
      try {
        Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        try {
          // A hard link, not a rename. POSIX rename(2) replaces its target silently, and
          // ATOMIC_MOVE is exactly that call - so the one option that looks safest is the one that
          // would overwrite an archived legal document. link(2) is atomic and fails when the name
          // exists, which is the semantics this store owes its caller.
          Files.createLink(target, temporary);
          return WriteResult.CREATED;
        } catch (FileAlreadyExistsException exists) {
          return identicalOrConflict(key, target, bytes);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException noLinks) {
          // A filesystem without hard links (some network and container mounts). Files.move
          // without REPLACE_EXISTING still refuses an existing target; it is a check-then-rename
          // rather than one operation, and that is stated rather than hidden.
          try {
            Files.move(temporary, target);
            return WriteResult.CREATED;
          } catch (FileAlreadyExistsException exists) {
            return identicalOrConflict(key, target, bytes);
          }
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  private WriteResult identicalOrConflict(ArchiveKey key, Path target, byte[] bytes)
      throws IOException {
    byte[] stored = Files.readAllBytes(target);
    if (MessageDigest.isEqual(stored, bytes)) {
      // What a retry of the same input looks like. The key carries the content hash, so identical
      // bytes at the same key is the expected outcome of a resumed issuance, not a coincidence.
      return WriteResult.ALREADY_IDENTICAL;
    }
    throw new EInvoiceException(
        ErrorCodes.ARCHIVE_CONTENT_CONFLICT,
        "the archive key already holds different bytes. Nothing is overwritten: an archived legal"
            + " document is evidence, and this is either a hash collision, a key reused by hand, or"
            + " a store that is not the one we wrote to (key: "
            + key.value()
            + ")");
  }

  @Override
  public Optional<byte[]> get(ArchiveKey key) {
    Path target = resolve(key);
    try {
      // Read the bytes and let the read decide. Files.exists answers false when it cannot tell
      // (no permission, a link loop), and "cannot tell" must never look like "no object": a
      // resumed issuance would then re-render and burn a number with the document on disk
      // (D-RTR-8). Only NoSuchFileException is absence.
      return Optional.of(Files.readAllBytes(target));
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  @Override
  public List<String> list(String prefix, int limit) {
    Path start = root.resolve(prefix == null ? "" : prefix).normalize();
    if (!start.startsWith(root)) {
      return List.of();
    }
    try {
      // Files.isDirectory is false for "cannot tell" as well; read the attributes instead so that
      // only a missing path is an empty list and an unreadable one is a failure (D-RTR-8).
      if (!Files.readAttributes(start, BasicFileAttributes.class).isDirectory()) {
        return List.of();
      }
    } catch (NoSuchFileException absent) {
      return List.of();
    } catch (IOException e) {
      throw unavailable(e);
    }
    List<String> keys = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(start)) {
      walk.filter(Files::isRegularFile)
          .map(path -> root.relativize(path).toString().replace('\\', '/'))
          .filter(name -> !name.contains("/.einvoice-"))
          .sorted(Comparator.naturalOrder())
          .limit(Math.max(0, limit))
          .forEach(keys::add);
    } catch (NoSuchFileException vanished) {
      return List.of();
    } catch (IOException | UncheckedIOException e) {
      // Files.walk reports a directory it cannot open while the stream runs, as an unchecked
      // exception. It is a read failure like any other.
      throw unavailable(e instanceof UncheckedIOException u ? u.getCause() : (IOException) e);
    }
    return List.copyOf(keys);
  }

  @Override
  public String describe() {
    return "filesystem archive at " + root;
  }

  private Path resolve(ArchiveKey key) {
    Path target = root.resolve(key.value()).normalize();
    if (!target.startsWith(root)) {
      // ArchiveKey already refuses traversal; this is the second place, because a key resolves
      // against a real directory and one check in one class is one deletion away from none.
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an archive key must resolve inside the archive root");
    }
    return target;
  }

  private static EInvoiceException unavailable(IOException cause) {
    // The path is not in the message: it is deployment detail on an error that can reach a caller.
    return new EInvoiceException(
        ErrorCodes.ARCHIVE_UNAVAILABLE, "the document archive is unavailable", cause);
  }
}
