package com.housedevinci.einvoice.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The filesystem store's {@code listAfter} (D-PR52-3, paths L1, L2, L5, L6, L17, L18, L22, L27 to
 * L29 of the orphan check design).
 */
class FilesystemArchiveListingTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
  private static final String FOLDER = "live/acme/";

  @TempDir Path root;

  private FilesystemArchiveStore store() {
    return new FilesystemArchiveStore(root, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Path file(String relative) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "<Invoice/>");
    return path;
  }

  private List<String> all() {
    return store().listAfter(FOLDER, "", 1000).keys();
  }

  /** L1. Keys after the position, in UTF-8 byte order, with the end of the folder as a value. */
  @Test
  void the_walk_lists_after_the_position_and_says_when_the_folder_ends() throws IOException {
    file("live/acme/2026/a.xml");
    file("live/acme/2026/b.xml");
    file("live/acme/2026/c.xml");

    ArchiveListing first = store().listAfter(FOLDER, "", 2);
    ArchiveListing rest = store().listAfter(FOLDER, first.keys().get(1), 2);

    assertThat(first.keys()).containsExactly("live/acme/2026/a.xml", "live/acme/2026/b.xml");
    assertThat(first.endOfFolder()).isFalse();
    assertThat(rest.keys()).containsExactly("live/acme/2026/c.xml");
    assertThat(rest.endOfFolder()).isTrue();
  }

  /** A full page that happens to be the last keys: the walk found none after, so it is the end. */
  @Test
  void a_full_last_page_is_the_end_of_the_folder() throws IOException {
    file("live/acme/2026/a.xml");
    file("live/acme/2026/b.xml");

    assertThat(store().listAfter(FOLDER, "", 2).endOfFolder()).isTrue();
  }

  /** L22. A missing folder lists nothing; the sweep, not the store, decides what that means. */
  @Test
  void a_missing_folder_is_an_empty_listing() {
    ArchiveListing listing = store().listAfter(FOLDER, "", 10);

    assertThat(listing.keys()).isEmpty();
    assertThat(listing.endOfFolder()).isTrue();
  }

  /** T5, L6 on the filesystem: the folder is the boundary, a neighbour's folder is never read. */
  @Test
  void a_neighbour_seller_whose_id_starts_with_ours_is_not_listed() throws IOException {
    file("live/acme2/2026/INV-1.xml");
    file("live/acme/2026/INV-1.xml");

    assertThat(all()).containsExactly("live/acme/2026/INV-1.xml");
  }

  /** T11, L5. UTF-8 byte order, not Java's: a key outside the basic plane sorts after U+FF61. */
  @Test
  void utf8_byte_order_is_used_for_keys_outside_the_basic_plane() throws IOException {
    String high = "live/acme/2026/😀.xml";
    String low = "live/acme/2026/｡.xml";
    try {
      file(high);
      file(low);
    } catch (IOException | java.nio.file.InvalidPathException unsupported) {
      org.junit.jupiter.api.Assumptions.abort("this file system refuses the names: " + unsupported);
    }
    assertThat(high.compareTo(low)).as("Java order puts the emoji first").isNegative();

    assertThat(all()).containsExactly(low, high);
    assertThat(store().listAfter(FOLDER, low, 10).keys()).containsExactly(high);
  }

  /** T10a, C2. This store's own write in flight is hidden. */
  @Test
  void a_fresh_temporary_file_is_hidden() throws IOException {
    Path temp = file("live/acme/2026/.einvoice-123456789.part");
    Files.setLastModifiedTime(temp, FileTime.from(NOW.minus(Duration.ofMinutes(5))));

    assertThat(all()).isEmpty();
  }

  /** T10b, C2. A leftover from a crash is listed after one hour. */
  @Test
  void a_temporary_file_older_than_an_hour_is_an_orphan() throws IOException {
    Path temp = file("live/acme/2026/.einvoice-123456789.part");
    Files.setLastModifiedTime(temp, FileTime.from(NOW.minus(Duration.ofMinutes(61))));

    assertThat(all()).containsExactly("live/acme/2026/.einvoice-123456789.part");
  }

  /** D2. A future time never hides a file: {@code touch -t 2099...} is listed. */
  @Test
  void a_temporary_file_dated_in_the_future_is_an_orphan() throws IOException {
    Path temp = file("live/acme/2026/.einvoice-42.part");
    Files.setLastModifiedTime(temp, FileTime.from(Instant.parse("2099-01-01T00:00:00Z")));

    assertThat(all()).containsExactly("live/acme/2026/.einvoice-42.part");
  }

  /** T10c, C2. Only the exact name shape as the last element; a folder is never hidden. */
  @Test
  void a_file_named_like_a_temporary_file_inside_a_folder_is_listed() throws IOException {
    file("live/acme/2026/.einvoice-1.part/hidden.xml");
    file("live/acme/2026/.einvoice-evil.xml");
    file("live/acme/2026/.einvoice-12.part.xml");

    assertThat(all())
        .containsExactly(
            "live/acme/2026/.einvoice-1.part/hidden.xml",
            "live/acme/2026/.einvoice-12.part.xml",
            "live/acme/2026/.einvoice-evil.xml");
  }

  /** Review probe, C2: the old filter dropped any path with a {@code /.einvoice-} element. */
  @Test
  void probe_a_file_under_an_einvoice_dot_folder_is_never_examined() throws IOException {
    file("live/acme/2026/.einvoice-dir/hidden.xml");

    assertThat(all()).containsExactly("live/acme/2026/.einvoice-dir/hidden.xml");
  }

  /** Without a clock the store cannot tell a write in flight from a leftover, so it hides none. */
  @Test
  void a_store_without_a_clock_hides_no_temporary_file() throws IOException {
    file("live/acme/2026/.einvoice-7.part");

    assertThat(new FilesystemArchiveStore(root).listAfter(FOLDER, "", 10).keys())
        .containsExactly("live/acme/2026/.einvoice-7.part");
  }

  /**
   * Review probe, C4, L18. Seller {@code .} would walk {@code live/./}, which a file system reads
   * as {@code live/}: every seller of the mode. The key and the folder refuse a {@code .} element.
   */
  @Test
  void probe_seller_dot_lists_every_seller_of_the_mode() {
    assertThatThrownBy(() -> ArchiveKey.sellerFolder(Mode.LIVE, "."))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
    assertThat(ArchiveKey.isValid("live/./2026/INV-1.xml")).isFalse();
    assertThat(ArchiveKey.isValid("live/acme/./INV-1.xml")).isFalse();
    assertThat(ArchiveKey.sellerFolder(Mode.LIVE, "acme.eu")).isEqualTo("live/acme.eu/");
  }

  /** T20, C3. A mixed-case seller is refused on a volume that ignores case, with the D3 text. */
  @Test
  void a_mixed_case_seller_is_refused_on_a_case_insensitive_volume() {
    assertThatThrownBy(() -> FilesystemArchiveStore.requireSellerIdFitsVolume("Acme", true))
        .isInstanceOf(EInvoiceException.class)
        .hasMessage(
            "Seller id Acme has upper-case letters and the archive volume ignores letter case. If"
                + " this seller has issued documents, move the archive to a case-sensitive volume"
                + " and keep the seller id. Only a seller with no issued documents may change its"
                + " id to lower case.")
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_SELLER_CASE);
    FilesystemArchiveStore.requireSellerIdFitsVolume("acme", true);
  }

  /** T21, C3. The same id starts on a case-sensitive volume. */
  @Test
  void a_mixed_case_seller_starts_on_a_case_sensitive_volume() {
    FilesystemArchiveStore.requireSellerIdFitsVolume("Acme", false);
  }

  /** C3, D7. The probe answers what the volume does, and leaves no scratch file behind. */
  @Test
  void the_case_probe_tells_the_volume_and_cleans_up() throws IOException {
    Path lower = root.resolve("case-check-a");
    Files.writeString(lower, "x");
    boolean expected = Files.exists(root.resolve("case-check-A"));
    Files.delete(lower);

    assertThat(store().ignoresLetterCase()).isEqualTo(expected);
    try (var left = Files.list(root.resolve("_probe"))) {
      assertThat(left).isEmpty();
    }
    if (expected) {
      assertThatThrownBy(() -> store().requireSellerIdFitsVolume("Acme"))
          .extracting("code")
          .isEqualTo(ErrorCodes.ARCHIVE_SELLER_CASE);
    } else {
      store().requireSellerIdFitsVolume("Acme");
    }
  }

  /**
   * Review probe, D1, L27. Linux takes any bytes in a name. {@code INV-\xff} and {@code INV-\xfe}
   * decode to one string; listed as that string twice, the order guard would stop the walk on every
   * pass. Each is listed once, as a distinct key with its file key, and the walk moves on. Skipped
   * on macOS and Windows, whose file systems refuse such names.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void probe_two_undecodable_names_stop_the_walk_for_ever() throws Exception {
    Files.createDirectories(root.resolve("live/acme/2026"));
    file("live/acme/2026/later.xml");
    Process touch =
        new ProcessBuilder(
                "/bin/sh", "-c", "touch \"$(printf 'INV-\\377')\" \"$(printf 'INV-\\376')\"")
            .directory(root.resolve("live/acme/2026").toFile())
            .start();
    assertThat(touch.waitFor()).isZero();

    List<String> keys = all();

    assertThat(keys).hasSize(3).doesNotHaveDuplicates();
    assertThat(keys.stream().filter(k -> k.contains("#inode:"))).hasSize(2);
    assertThat(keys).contains("live/acme/2026/later.xml");
    List<String> sorted = new ArrayList<>(keys);
    sorted.sort(ArchiveListing.UTF8_ORDER);
    assertThat(keys).isEqualTo(sorted);
  }

  /** T27, D1. The same two names through the walk's pages: two orphans, and the walk moves on. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void two_names_that_decode_alike_are_two_orphans_and_the_walk_moves_on() throws Exception {
    Files.createDirectories(root.resolve("live/acme/2026"));
    Process touch =
        new ProcessBuilder(
                "/bin/sh",
                "-c",
                "touch \"$(printf 'INV-\\377')\" \"$(printf 'INV-\\376')\" zzz.xml")
            .directory(root.resolve("live/acme/2026").toFile())
            .start();
    assertThat(touch.waitFor()).isZero();

    ArchiveListing first = store().listAfter(FOLDER, "", 1);
    ArchiveListing second = store().listAfter(FOLDER, first.keys().get(0), 1);
    ArchiveListing third = store().listAfter(FOLDER, second.keys().get(0), 1);

    assertThat(List.of(first.keys().get(0), second.keys().get(0)))
        .allSatisfy(k -> assertThat(ArchiveKey.isValid(k)).isFalse())
        .doesNotHaveDuplicates();
    assertThat(third.keys()).containsExactly("live/acme/2026/zzz.xml");
    assertThat(third.endOfFolder()).isTrue();
  }

  /** The default {@code list} is one page of {@code listAfter} from the start. */
  @Test
  void list_reads_the_first_page() throws IOException {
    file("live/acme/2026/b.xml");
    file("live/acme/2026/a.xml");

    assertThat(store().list(FOLDER, 1)).containsExactly("live/acme/2026/a.xml");
  }
}
