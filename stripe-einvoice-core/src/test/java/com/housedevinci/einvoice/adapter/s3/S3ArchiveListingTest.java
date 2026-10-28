package com.housedevinci.einvoice.adapter.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The S3 store's {@code listAfter} (D-PR52-3, paths L3, L4, L19, L20, L30): against a real
 * S3-compatible server for the walk, and against a scripted client for the answers a server may
 * give that this one does not.
 */
@Timeout(300)
class S3ArchiveListingTest {

  private static final String FOLDER = "live/acme/";

  /** The keys one cycle of pages examines, from the empty position to the end of the folder. */
  private static List<String> walk(S3ArchiveStore store, int page) {
    List<String> seen = new ArrayList<>();
    String after = "";
    for (int calls = 0; calls < 100; calls++) {
      ArchiveListing listing = store.listAfter(FOLDER, after, page);
      seen.addAll(listing.keys());
      if (listing.endOfFolder()) {
        return seen;
      }
      after = listing.keys().get(listing.keys().size() - 1);
    }
    throw new AssertionError("the walk never reached the end of the folder");
  }

  private static void seed(S3Client client, String bucket, String bucketPrefix, int count) {
    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    IntStream.range(0, count)
        .parallel()
        .forEach(
            i ->
                client.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(
                            bucketPrefix
                                + FOLDER
                                + String.format(java.util.Locale.ROOT, "2026/INV-%06d.xml", i))
                        .build(),
                    RequestBody.fromString("x")));
  }

  /** T4, no bucket prefix. 1,200 keys, page 500: each key once per cycle, in byte order. */
  @Test
  void s3_lists_after_the_cursor_across_pages() {
    S3Client client = S3MockSupport.client();
    String bucket = "walk-" + System.nanoTime();
    seed(client, bucket, "", 1200);
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key("live/acme2/2026/INV-000001.xml").build(),
        RequestBody.fromString("x"));

    List<String> seen = walk(new S3ArchiveStore(client, bucket, ""), 500);

    assertThat(seen).hasSize(1200).doesNotHaveDuplicates().allMatch(k -> k.startsWith(FOLDER));
    List<String> sorted = new ArrayList<>(seen);
    sorted.sort(ArchiveListing.UTF8_ORDER);
    assertThat(seen).isEqualTo(sorted);
  }

  /** T4 with {@code einvoice.archive.s3.prefix} (C13): StartAfter is the prefix plus the cursor. */
  @Test
  void s3_lists_after_the_cursor_under_a_bucket_prefix() {
    S3Client client = S3MockSupport.client();
    String bucket = "walk-prefix-" + System.nanoTime();
    seed(client, bucket, "tenant-a/", 1200);
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key("tenant-b/" + FOLDER + "2026/x.xml").build(),
        RequestBody.fromString("x"));

    List<String> seen = walk(new S3ArchiveStore(client, bucket, "tenant-a/"), 500);

    assertThat(seen).hasSize(1200).doesNotHaveDuplicates();
    assertThat(seen).allMatch(k -> k.startsWith(FOLDER), "stripped of the bucket prefix");
  }

  /** The request the store sends: the bucket prefix on both the prefix and StartAfter. */
  @Test
  void start_after_carries_the_bucket_prefix_and_only_on_the_first_request() {
    List<ListObjectsV2Request> sent = new CopyOnWriteArrayList<>();
    S3Client client =
        scripted(
            sent,
            List.of(
                page(List.of("tenant-a/live/acme/2026/b.xml"), true, "t1"),
                page(List.of("tenant-a/live/acme/2026/c.xml"), false, null)));

    ArchiveListing listing =
        new S3ArchiveStore(client, "bucket", "tenant-a")
            .listAfter(FOLDER, "live/acme/2026/a.xml", 5);

    assertThat(listing.keys()).containsExactly("live/acme/2026/b.xml", "live/acme/2026/c.xml");
    assertThat(listing.endOfFolder()).isTrue();
    assertThat(sent.get(0).prefix()).isEqualTo("tenant-a/live/acme/");
    assertThat(sent.get(0).startAfter()).isEqualTo("tenant-a/live/acme/2026/a.xml");
    assertThat(sent.get(1).startAfter()).isNull();
    assertThat(sent.get(1).continuationToken()).isEqualTo("t1");
  }

  /**
   * T23 at the store, L20. A server may answer with fewer keys than asked and more to come: the
   * store asks again, and the end is the server's {@code IsTruncated=false}, never a count.
   */
  @Test
  void a_short_response_with_more_to_come_is_not_the_end() {
    List<ListObjectsV2Request> sent = new CopyOnWriteArrayList<>();
    S3Client shortThenMore =
        scripted(
            sent,
            List.of(
                page(List.of("live/acme/a.xml"), true, "t1"),
                page(List.of("live/acme/b.xml"), true, "t2")));

    ArchiveListing listing =
        new S3ArchiveStore(shortThenMore, "bucket", "").listAfter(FOLDER, "", 2);

    assertThat(listing.keys()).containsExactly("live/acme/a.xml", "live/acme/b.xml");
    assertThat(listing.endOfFolder()).isFalse();
    assertThat(sent).hasSize(2);
  }

  /** A truncated answer with no token cannot be followed: an outage, never the end. */
  @Test
  void a_truncated_answer_without_a_token_is_refused() {
    S3Client broken =
        scripted(new ArrayList<>(), List.of(page(List.of("live/acme/a.xml"), true, null)));

    assertThatThrownBy(() -> new S3ArchiveStore(broken, "bucket", "").listAfter(FOLDER, "", 5))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
  }

  /** T24, L19, L30. Both directory bucket suffixes are refused when the store is built. */
  @Test
  void a_directory_bucket_is_refused_at_startup() {
    S3Client client = scripted(new ArrayList<>(), List.of());
    for (String bucket : List.of("archive--euw1-az1--x-s3", "archive--euw1-az1--xa--s3")) {
      assertThatThrownBy(() -> new S3ArchiveStore(client, bucket, ""))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.ARCHIVE_LISTING_UNORDERED);
    }
    new S3ArchiveStore(client, "archive-x-s3", "");
  }

  private static ListObjectsV2Response page(List<String> keys, boolean truncated, String token) {
    return ListObjectsV2Response.builder()
        .contents(keys.stream().map(k -> S3Object.builder().key(k).build()).toList())
        .isTruncated(truncated)
        .nextContinuationToken(token)
        .build();
  }

  /** A client that answers {@code listObjectsV2} from a script and refuses everything else. */
  private static S3Client scripted(
      List<ListObjectsV2Request> sent, List<ListObjectsV2Response> answers) {
    Set<Integer> served = new HashSet<>();
    return (S3Client)
        Proxy.newProxyInstance(
            S3Client.class.getClassLoader(),
            new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
              if (method.getName().equals("listObjectsV2")
                  && args != null
                  && args[0] instanceof ListObjectsV2Request request) {
                sent.add(request);
                int next = served.size();
                served.add(next);
                return answers.get(next);
              }
              if (method.getName().equals("close")) {
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
