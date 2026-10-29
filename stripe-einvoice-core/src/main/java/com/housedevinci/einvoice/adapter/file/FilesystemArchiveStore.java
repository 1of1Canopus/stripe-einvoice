package com.housedevinci.einvoice.adapter.file;

import com.housedevinci.einvoice.application.ArchiveCapabilityProbe;
import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(FilesystemArchiveStore.class);

  /** The name {@code Files.createTempFile(dir, ".einvoice-", ".part")} gives, and only that. */
  private static final Pattern TEMP_NAME = Pattern.compile("^\\.einvoice-[0-9]+\\.part$");

  /** How long this store's own temporary file stays hidden from a listing. */
  private static final Duration TEMP_HIDDEN_FOR = Duration.ofHours(1);

  private final Path root;
  private final Optional<Clock> clock;

  /**
   * The volume's answer to "does it ignore letter case", asked once per store and kept (D-PR57-6).
   * Null until a key or prefix with an upper-case seller element needs it; a failed probe is not
   * kept, so the next call asks again.
   */
  private volatile Boolean ignoresCase;

  /**
   * A store with no clock: it cannot tell a write in flight from a leftover, so it hides no
   * temporary file and lists every one (the safe direction). A pass that runs during a write may
   * then report that write's {@code .einvoice-<digits>.part} file once. The starter uses {@link
   * #FilesystemArchiveStore(Path, Clock)}; a host that builds this store should too.
   */
  public FilesystemArchiveStore(Path root) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.clock = Optional.empty();
  }

  /**
   * @param clock decides how old a temporary file is: this store's own write in flight is hidden
   *     for one hour, and a leftover is listed after that
   */
  public FilesystemArchiveStore(Path root, Clock clock) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.clock = Optional.of(Objects.requireNonNull(clock, "clock"));
  }

  @Override
  public boolean supportsAtomicCreate() {
    return true;
  }

  @Override
  public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
    requireSellerElementFitsVolume(key.value());
    Path target = resolve(key);
    try {
      requireFoldersBelowRoot(target.getParent());
      Files.createDirectories(target.getParent());
      // Checked again: a folder may have been swapped for a link between the two calls (D-RTR-12).
      requireFoldersBelowRoot(target.getParent());
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
    // The name existed a moment ago (the link or move just refused it), so absent now is a fault.
    byte[] stored = readObject(target).orElseThrow(() -> unavailable(new NoSuchFileException("")));
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
      // Only "no such file" is absence, and only while the archive root itself is there: when the
      // root is gone (volume dropped), ENOENT is the same answer for every key and must not read
      // as "no object" (D-RTR-9).
      Optional<byte[]> stored = readObject(target);
      if (stored.isEmpty()) {
        requireRoot();
      }
      return stored;
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  /**
   * Keys after {@code startAfter} under the prefix folder, in UTF-8 byte order (D-PR52-3).
   *
   * <p>One walk of the prefix folder per call; its cost grows with the folder, whatever the page.
   * At most {@code limit + 1} keys are held while it runs, so memory does not.
   *
   * <ul>
   *   <li>Every entry that is not a folder is listed: a file, a link, a FIFO. A folder is never
   *       hidden, whatever its name.
   *   <li>The one entry hidden is this store's own write in flight (C2, D2): the last path element
   *       is {@code .einvoice-<digits>.part}, it is a regular file, and it changed within the last
   *       hour, never in the future. A leftover from a crash is listed after the hour.
   *   <li>A name that does not decode losslessly (D1) is listed as {@code <decoded
   *       path>#inode:<file key>}, unique per file, so two names that decode alike are two keys.
   *       Example: {@code INV-\xff} and {@code INV-\xfe} both decode to {@code INV-\ufffd}. No name
   *       a writer chooses can produce a duplicate key or stop the walk.
   *   <li>An entry below the prefix folder that cannot be read or entered, such as a folder with
   *       mode {@code 000} or a tree deeper than the path limit, is listed as {@code
   *       <path>#unlistable} and the walk carries on (D-PR57-2). Only a failure on the root or on
   *       the prefix folder itself fails the listing.
   *   <li>Every key is at most {@link ArchiveListing#MAX_KEY_BYTES} UTF-8 bytes: a longer one is
   *       cut at a code point boundary and keeps its {@code #} suffix (D-PR57-3).
   *   <li>{@code endOfFolder} is true when the walk found no key past the last one returned.
   * </ul>
   */
  @Override
  public ArchiveListing listAfter(String prefix, String startAfter, int limit) {
    requireSellerElementFitsVolume(prefix == null ? "" : prefix);
    String after = startAfter == null ? "" : startAfter;
    int bound = Math.max(0, limit);
    Path start = root.resolve(prefix == null ? "" : prefix).normalize();
    if (!start.startsWith(root)) {
      return new ArchiveListing(List.of(), true);
    }
    try {
      requireRoot();
    } catch (IOException e) {
      throw unavailable(e);
    }
    try {
      requireFoldersBelowRoot(start);
      // Only a missing path is an empty list; a prefix that is not a folder is a fault (D-RTR-11).
      BasicFileAttributes attributes = Files.readAttributes(start, BasicFileAttributes.class);
      if (!attributes.isDirectory()) {
        throw new IOException("archive prefix is not a folder");
      }
    } catch (NoSuchFileException absent) {
      return new ArchiveListing(List.of(), true);
    } catch (IOException e) {
      throw unavailable(e);
    }
    Optional<Instant> now = clock.map(Clock::instant);
    TreeSet<String> kept = new TreeSet<>(ArchiveListing.UTF8_ORDER);
    int[] unreadable = {0};
    int[] unlistableCount = {0};
    byte[] afterBytes = ArchiveListing.utf8(after);
    try {
      Files.walkFileTree(
          start,
          java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes entry) {
              if (entry.isDirectory() || isOwnWriteInFlight(path, entry, now)) {
                return FileVisitResult.CONTINUE;
              }
              keep(keyOf(path, entry, unreadable));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException failure)
                throws IOException {
              if (failure instanceof NoSuchFileException) {
                // Gone between the folder read and the visit: a write in flight that finished.
                return FileVisitResult.CONTINUE;
              }
              return unlistable(path, failure);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException failure)
                throws IOException {
              if (failure == null || failure instanceof NoSuchFileException) {
                return FileVisitResult.CONTINUE;
              }
              return unlistable(folder, failure);
            }

            /*
             * D-PR57-2: an entry below the start folder that cannot be read or entered (a folder
             * with mode 000, a tree past the path limit) is listed as {@code <path>#unlistable}, an
             * invalid key the walk reports with no lookup, and the walk carries on. A failure on
             * the start folder itself stays fatal.
             */
            private FileVisitResult unlistable(Path entry, IOException failure) throws IOException {
              if (entry.equals(start) || !entry.startsWith(start)) {
                throw failure;
              }
              unlistableCount[0]++;
              keep(bounded(relativeName(entry), "#unlistable"));
              return FileVisitResult.CONTINUE;
            }

            private void keep(String key) {
              if (Arrays.compareUnsigned(ArchiveListing.utf8(key), afterBytes) > 0) {
                kept.add(key);
                if (kept.size() > bound + 1) {
                  kept.pollLast();
                }
              }
            }
          });
      requireRoot();
    } catch (IOException e) {
      throw unavailable(e);
    }
    if (unreadable[0] > 0) {
      log.warn(
          "einvoice: {} archive entries have names that do not decode and no file key; each is"
              + " listed once as <path>#unreadable-name",
          unreadable[0]);
    }
    if (unlistableCount[0] > 0) {
      // The count only: the paths are deployment detail, and the findings name each entry.
      log.warn(
          "einvoice: {} archive entries could not be read or entered; each is listed as"
              + " <path>#unlistable and reported as an orphan",
          unlistableCount[0]);
    }
    boolean end = kept.size() <= bound;
    List<String> keys = new ArrayList<>(bound);
    for (String key : kept) {
      if (keys.size() == bound) {
        break;
      }
      keys.add(key);
    }
    return new ArchiveListing(keys, end);
  }

  /**
   * C2 and D2: this store's own temporary file, and nothing else. The exact name {@code
   * Files.createTempFile(dir, ".einvoice-", ".part")} gives, as the last element, a regular file,
   * changed within the last hour and not in the future. Residual, stated: a writer who touches a
   * file of this shape every hour keeps it hidden; that needs live, repeated access, and the module
   * never reads a {@code .part} file as a document.
   */
  private static boolean isOwnWriteInFlight(
      Path path, BasicFileAttributes entry, Optional<Instant> clockNow) {
    if (clockNow.isEmpty()) {
      return false;
    }
    Instant now = clockNow.get();
    Path name = path.getFileName();
    if (name == null || !entry.isRegularFile() || !TEMP_NAME.matcher(name.toString()).matches()) {
      return false;
    }
    Duration age = Duration.between(entry.lastModifiedTime().toInstant(), now);
    return !age.isNegative() && age.compareTo(TEMP_HIDDEN_FOR) < 0;
  }

  /**
   * D1: the key of one entry. The decoded relative path when it opens the same file again; else the
   * decoded path with the file key, which is unique per file. Example: {@code
   * live/acme/2026/INV-\ufffd#inode:(dev=803,ino=1234)}; the operator finds it with {@code find
   * -inum 1234}. The {@code #} makes it an invalid archive key, so the walk reports it with no
   * database lookup.
   */
  private String keyOf(Path path, BasicFileAttributes entry, int[] unreadable) {
    String decoded = relativeName(path);
    boolean fits = ArchiveListing.utf8(decoded).length <= ArchiveListing.MAX_KEY_BYTES;
    if (fits && opensTheSameFile(decoded, path, entry)) {
      return decoded;
    }
    Object fileKey = entry.fileKey();
    if (fileKey != null) {
      return bounded(decoded, "#inode:" + fileKey);
    }
    unreadable[0]++;
    return bounded(decoded, "#unreadable-name");
  }

  private String relativeName(Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  /**
   * D-PR57-3: {@code name + suffix}, with {@code name} cut at a code point boundary so that the
   * whole is at most {@link ArchiveListing#MAX_KEY_BYTES} UTF-8 bytes. A name that does not decode
   * grows three bytes (U+FFFD) per raw byte, so a path well under the system limit can decode past
   * the bound; cut, it still ends in its suffix, which keeps it unique (the file key) and invalid
   * as an archive key (the {@code #}). Example: six 255-byte names of {@code \xff} decode to about
   * 4.6 KB and are listed as the first 4 KB or so, then {@code #inode:(dev=..,ino=..)}.
   */
  static String bounded(String name, String suffix) {
    int budget = ArchiveListing.MAX_KEY_BYTES - ArchiveListing.utf8(suffix).length;
    if (budget < 0) {
      return cut(name + suffix, ArchiveListing.MAX_KEY_BYTES);
    }
    return cut(name, budget) + suffix;
  }

  /** The longest start of {@code text}, whole code points only, that fits {@code bytes}. */
  private static String cut(String text, int bytes) {
    int used = 0;
    int end = 0;
    while (end < text.length()) {
      int codePoint = text.codePointAt(end);
      int width = ArchiveListing.utf8(new String(Character.toChars(codePoint))).length;
      if (used + width > bytes) {
        break;
      }
      used += width;
      end += Character.charCount(codePoint);
    }
    return text.substring(0, end);
  }

  private boolean opensTheSameFile(String decoded, Path path, BasicFileAttributes entry) {
    try {
      Path again = root.resolve(decoded);
      BasicFileAttributes reread =
          Files.readAttributes(again, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      Object fileKey = entry.fileKey();
      return fileKey != null ? fileKey.equals(reread.fileKey()) : Files.isSameFile(again, path);
    } catch (IOException | java.nio.file.InvalidPathException notTheSame) {
      return false;
    }
  }

  /**
   * True when this archive's volume ignores letter case at its root (D-PR52-3, C3). It writes a
   * scratch file {@code _probe/<uuid>-a} and asks whether {@code _probe/<uuid>-A} exists. A failed
   * delete of the scratch file never fails the probe (D7): the file stays, like the capability
   * probe's scratch keys, and a WARN names the code. Out of scope, stated: case folding set by hand
   * on a folder below the root (ext4 {@code casefold}, the Windows per-folder flag).
   */
  public boolean ignoresLetterCase() {
    String id = UUID.randomUUID().toString();
    Path folder = root.resolve(ArchiveCapabilityProbe.SCRATCH_PREFIX);
    Path lower = folder.resolve(id + "-a");
    try {
      requireRoot();
      requireFoldersBelowRoot(folder);
      Files.createDirectories(folder);
      requireFoldersBelowRoot(folder);
      Files.write(lower, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      boolean ignores = Files.exists(folder.resolve(id + "-A"), LinkOption.NOFOLLOW_LINKS);
      try {
        Files.delete(lower);
      } catch (IOException e) {
        log.warn(
            "einvoice: the archive case probe could not remove its scratch file ({}); it stays"
                + " under {}",
            ErrorCodes.ARCHIVE_UNAVAILABLE,
            ArchiveCapabilityProbe.SCRATCH_PREFIX);
      }
      return ignores;
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  /**
   * Refuses a seller id with an upper-case letter on a volume that ignores case: {@code DEI-285},
   * no opt-out (D-PR52-3, C3). Two distinct lower-case ids never share a folder on any volume, so
   * no seller is ever shown a key another seller wrote. Uses the answer this store keeps.
   */
  public void requireSellerIdFitsVolume(String sellerId) {
    if (!sellerId.equals(sellerId.toLowerCase(Locale.ROOT))) {
      requireSellerIdFitsVolume(sellerId, volumeIgnoresCase());
    }
  }

  /**
   * D-PR57-6: the case rule lives in the store, the one place every path goes through: a host that
   * decorates this store, or builds the sweep without the starter, is refused too. Applied to the
   * seller element of a key or prefix ({@code live/Acme/...}): {@code putIfAbsent} and {@code
   * listAfter} refuse it with {@code DEI-285} on a volume that ignores case. A key or prefix of
   * another shape ({@code _probe/...}, {@code live/}) has no seller element and is not checked.
   */
  private void requireSellerElementFitsVolume(String keyOrPrefix) {
    String[] elements = keyOrPrefix.split("/", 3);
    if (elements.length < 2 || elements[1].isEmpty() || !isModeElement(elements[0])) {
      return;
    }
    requireSellerIdFitsVolume(elements[1]);
  }

  private static boolean isModeElement(String element) {
    for (Mode mode : Mode.values()) {
      if (mode.wire().equals(element)) {
        return true;
      }
    }
    return false;
  }

  private boolean volumeIgnoresCase() {
    Boolean known = ignoresCase;
    if (known == null) {
      known = ignoresLetterCase();
      ignoresCase = known;
    }
    return known;
  }

  /** The rule itself, for a known answer about the volume. */
  public static void requireSellerIdFitsVolume(String sellerId, boolean volumeIgnoresCase) {
    if (volumeIgnoresCase && !sellerId.equals(sellerId.toLowerCase(Locale.ROOT))) {
      throw new EInvoiceException(ErrorCodes.ARCHIVE_SELLER_CASE, sellerCaseMessage(sellerId));
    }
  }

  /**
   * D3: never "rename" for a seller with documents. Numbering is per seller id, so {@code Acme}
   * restarting as {@code acme} would issue {@code INV-2026-000001} a second time.
   */
  static String sellerCaseMessage(String sellerId) {
    return "Seller id "
        + sellerId
        + " has upper-case letters and the archive volume ignores letter case. If this seller has"
        + " issued documents, move the archive to a case-sensitive volume and keep the seller id."
        + " Only a seller with no issued documents may change its id to lower case.";
  }

  @Override
  public String describe() {
    return "filesystem archive at " + root;
  }

  /**
   * Reads the object at the path, or empty when there is none. Attributes first, without following
   * links: a link, a FIFO, a device or a folder is not an archived document and is a fault, not
   * bytes from outside the root and not a read that never returns (D-RTR-10). The open then refuses
   * links too. A regular file swapped for a FIFO between the two calls needs write access to the
   * volume; that residual window is stated, not closed.
   */
  private Optional<byte[]> readObject(Path target) throws IOException {
    requireFoldersBelowRoot(target.getParent());
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw new IOException("archive object is not a regular file");
      }
      try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.of(in.readAllBytes());
      }
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    }
  }

  /**
   * The root must be a folder that can be read; otherwise nothing under it can be called absent.
   */
  private void requireRoot() throws IOException {
    if (!Files.readAttributes(root, BasicFileAttributes.class).isDirectory()) {
      throw new IOException("archive root is not a folder");
    }
  }

  /**
   * Every folder name between the root and the path must be a plain folder, not a link: the
   * no-follow flag on the last name of a path says nothing about the names above it (D-RTR-12). A
   * name that does not exist ends the check, since nothing below it can exist either. The root
   * itself may be a link, by design; the operator chose it.
   */
  private void requireFoldersBelowRoot(Path folder) throws IOException {
    if (!folder.startsWith(root)) {
      throw new IOException("archive path is outside the root");
    }
    Path current = root;
    for (Path name : root.relativize(folder)) {
      if (name.toString().isEmpty()) {
        continue;
      }
      current = current.resolve(name);
      try {
        BasicFileAttributes attributes =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
          throw new IOException("archive folder is a link or not a folder");
        }
      } catch (NoSuchFileException absent) {
        return;
      }
    }
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
