package com.housedevinci.einvoice.adapter.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.ArchiveCapabilityProbe;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3 store against a real S3-compatible server (MinIO in Testcontainers).
 *
 * <p>The load-bearing test is {@link #the_startup_probe_does_not_lie_about_this_server}: whether a
 * given gateway honours {@code If-None-Match: *} is a property of the deployment, not something a
 * library can assert once. What this module must guarantee is that its <em>probe</em> agrees with
 * what the server actually does - so the test establishes the truth with a raw SDK call and then
 * asserts the probe reached the same conclusion.
 */
@Testcontainers
@Timeout(180)
class S3ArchiveStoreTest {

  private static S3Client client;
  private static final String BUCKET = "einvoice-archive";
  private static final byte[] BYTES = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  private static ArchiveKey key() {
    return new ArchiveKey(
        "live/acme/2026/INV-000001-"
            + UUID.randomUUID().toString().replace("-", "")
            + "".repeat(0)
            + "a".repeat(32)
            + ".xml");
  }

  @BeforeAll
  static void bucket() {
    client = MinioSupport.client();
    try {
      client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    } catch (S3Exception alreadyThere) {
      // A bucket that exists is what a second run looks like.
    }
  }

  @Test
  void an_existing_archive_key_with_identical_bytes_succeeds() {
    S3ArchiveStore store = new S3ArchiveStore(client, BUCKET, "");
    ArchiveKey key = key();
    assertThat(store.putIfAbsent(key, BYTES)).isEqualTo(ArchiveStore.WriteResult.CREATED);
    assertThat(store.putIfAbsent(key, BYTES)).isEqualTo(ArchiveStore.WriteResult.ALREADY_IDENTICAL);
    assertThat(store.get(key)).contains(BYTES);
  }

  @Test
  void an_existing_archive_key_with_different_bytes_is_refused() {
    S3ArchiveStore store = new S3ArchiveStore(client, BUCKET, "");
    ArchiveKey key = key();
    store.putIfAbsent(key, BYTES);
    assertThatThrownBy(() -> store.putIfAbsent(key, "<Other/>".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(store.get(key)).contains(BYTES);
  }

  @Test
  void the_startup_probe_does_not_lie_about_this_server() {
    boolean serverRefusesOverwrite = serverHonoursIfNoneMatch();
    S3ArchiveStore store = new S3ArchiveStore(client, BUCKET, "");
    if (serverRefusesOverwrite) {
      ArchiveCapabilityProbe.probe(store, UUID.randomUUID().toString(), false);
    } else {
      assertThatThrownBy(
              () -> ArchiveCapabilityProbe.probe(store, UUID.randomUUID().toString(), false))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.ARCHIVE_NOT_ATOMIC);
    }
  }

  /** The truth about this server, established with the SDK and not with our own store. */
  private boolean serverHonoursIfNoneMatch() {
    String raw = "raw-probe/" + UUID.randomUUID();
    client.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(raw).ifNoneMatch("*").build(),
        RequestBody.fromString("a"));
    try {
      client.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key(raw).ifNoneMatch("*").build(),
          RequestBody.fromString("b"));
      return false;
    } catch (S3Exception refused) {
      return refused.statusCode() == 412;
    }
  }

  @Test
  void a_prefix_keeps_two_archives_out_of_each_other() {
    S3ArchiveStore one = new S3ArchiveStore(client, BUCKET, "tenant-a");
    S3ArchiveStore two = new S3ArchiveStore(client, BUCKET, "tenant-b/");
    ArchiveKey key = key();
    one.putIfAbsent(key, BYTES);
    assertThat(two.get(key)).isEmpty();
    assertThat(one.get(key)).contains(BYTES);
    assertThat(one.list("live", 100)).contains(key.value());
    assertThat(two.list("live", 100)).doesNotContain(key.value());
  }

  @Test
  void a_missing_object_reads_as_absent() {
    assertThat(new S3ArchiveStore(client, BUCKET, "").get(key())).isEmpty();
  }

  @Test
  void a_prefix_that_traverses_upwards_is_refused_at_construction() {
    assertThatThrownBy(() -> new S3ArchiveStore(client, BUCKET, "../elsewhere"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  @Test
  void the_description_names_the_bucket_and_no_credential() {
    assertThat(new S3ArchiveStore(client, BUCKET, "p").describe())
        .isEqualTo("s3 archive in bucket einvoice-archive under p/");
  }
}
