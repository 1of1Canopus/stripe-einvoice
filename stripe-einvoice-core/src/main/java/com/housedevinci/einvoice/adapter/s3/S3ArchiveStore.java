package com.housedevinci.einvoice.adapter.s3;

import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The archive in an S3-compatible bucket.
 *
 * <p>Write-once is {@code If-None-Match: *} on PutObject: the store creates the object only if the
 * key is absent, and answers {@code 412 PreconditionFailed} when it is not. A 412 is <b>not</b> a
 * failure here - it is the point - so the existing object is read back and compared byte for byte:
 * identical is success, which is what a retry of the same input looks like, and different is {@code
 * DEI-240}, an alert, never an overwrite.
 *
 * <p><b>This capability is not assumed.</b> Several widely deployed S3-compatible stores ignore an
 * unknown conditional header and overwrite with a 200, so the module probes the behaviour at
 * startup with a real conditional write (I-06) and refuses a store that fails, unless the operator
 * sets the WARNed weaker mode. In that mode this class still sends the header - a store that
 * honours it later costs nothing - and the caller adds the read-back comparison.
 *
 * <p>Nothing here logs a key, a bucket policy, a credential or a response body.
 */
public final class S3ArchiveStore implements ArchiveStore {

  private static final String PRECONDITION_FAILED = "PreconditionFailed";

  private final S3Client client;
  private final String bucket;
  private final String prefix;

  /**
   * @param prefix an optional key prefix inside the bucket, so an archive can share a bucket with
   *     something else without either being able to address the other's keys
   */
  public S3ArchiveStore(S3Client client, String bucket, String prefix) {
    this.client = Objects.requireNonNull(client, "client");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    String normalised = prefix == null || prefix.isBlank() ? "" : prefix.strip();
    if (!normalised.isEmpty() && !normalised.endsWith("/")) {
      normalised = normalised + "/";
    }
    if (normalised.startsWith("/") || normalised.contains("..")) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG, "einvoice.archive.s3.prefix is relative and never traverses upwards");
    }
    this.prefix = normalised;
    // D-PR52-3, C5 and D5: a directory bucket lists in no order and has no StartAfter, so the
    // orphan walk could end a "cycle" with keys never examined. Its name says so: "--x-s3" for the
    // bucket, "--xa--s3" for an access point to one. A convenience, not the control: an alias or a
    // proxy hides the name, and the walk's per-pass order guard catches what this cannot.
    if (bucket.endsWith("--x-s3") || bucket.endsWith("--xa--s3")) {
      throw new EInvoiceException(
          ErrorCodes.ARCHIVE_LISTING_UNORDERED,
          "the archive bucket is an S3 directory bucket or an access point to one. Its listing has"
              + " no key order and no start-after, so the orphan check could not examine every key."
              + " Use a general purpose bucket.");
    }
  }

  @Override
  public boolean supportsAtomicCreate() {
    // A claim about the protocol. The startup probe is what decides whether this store keeps it.
    return true;
  }

  @Override
  public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
    String objectKey = prefix + key.value();
    try {
      client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(objectKey).ifNoneMatch("*").build(),
          RequestBody.fromBytes(bytes));
      return WriteResult.CREATED;
    } catch (S3Exception e) {
      if (isPreconditionFailed(e)) {
        return identicalOrConflict(key, objectKey, bytes);
      }
      throw unavailable(e);
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  private static boolean isPreconditionFailed(S3Exception e) {
    return e.statusCode() == 412
        || (e.awsErrorDetails() != null
            && PRECONDITION_FAILED.equals(e.awsErrorDetails().errorCode()));
  }

  private WriteResult identicalOrConflict(ArchiveKey key, String objectKey, byte[] bytes) {
    byte[] stored =
        read(objectKey)
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.ARCHIVE_UNAVAILABLE,
                        "the archive refused the write as already present and then could not"
                            + " produce the object"));
    if (MessageDigest.isEqual(stored, bytes)) {
      return WriteResult.ALREADY_IDENTICAL;
    }
    throw new EInvoiceException(
        ErrorCodes.ARCHIVE_CONTENT_CONFLICT,
        "the archive key already holds different bytes. Nothing is overwritten: an archived legal"
            + " document is evidence (key: "
            + key.value()
            + ")");
  }

  @Override
  public Optional<byte[]> get(ArchiveKey key) {
    return read(prefix + key.value());
  }

  private Optional<byte[]> read(String objectKey) {
    try {
      return Optional.of(
          client
              .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
              .asByteArray());
    } catch (NoSuchKeyException absent) {
      return Optional.empty();
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  /**
   * Keys after {@code startAfter} under the prefix, in the bucket's UTF-8 byte order (D-PR52-3).
   *
   * <p>{@code StartAfter} is the bucket prefix plus the saved position on the first request;
   * continuation tokens carry the call after that, and never leave it (a token is never saved). The
   * call loops until it has {@code limit} keys or the bucket says it is done: a response with fewer
   * keys than asked is not the end (C5). {@code endOfFolder} is {@code IsTruncated=false} on the
   * last response read.
   *
   * <p>Every request asks for {@code encoding-type=url} (D-PR57-5). XML 1.0 cannot carry every
   * code point an S3 key may hold: a key with {@code \x01} fails the parse on every pass, and XML
   * end-of-line handling turns {@code \r} into {@code \n}, so two keys arrive as one. Encoded, the
   * response carries any key, and the SDK decodes {@code Key}, {@code Prefix} and {@code
   * StartAfter} before this method sees them.
   */
  @Override
  public ArchiveListing listAfter(String keyPrefix, String startAfter, int limit) {
    int bound = Math.max(0, limit);
    String requestPrefix = prefix + (keyPrefix == null ? "" : keyPrefix);
    String after = startAfter == null || startAfter.isEmpty() ? null : prefix + startAfter;
    List<String> keys = new ArrayList<>();
    String token = null;
    boolean truncated = true;
    try {
      while (keys.size() < bound && truncated) {
        ListObjectsV2Response response =
            client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(bucket)
                    // D-PR57-5: keys come back percent-encoded and the SDK decodes them. Raw, a
                    // key with a control character fails the XML parse, and CR becomes LF.
                    .encodingType(EncodingType.URL)
                    .prefix(requestPrefix)
                    .startAfter(token == null ? after : null)
                    .continuationToken(token)
                    .maxKeys(Math.min(1000, bound - keys.size()))
                    .build());
        response.contents().stream()
            .map(object -> object.key().substring(prefix.length()))
            .forEach(keys::add);
        truncated = Boolean.TRUE.equals(response.isTruncated());
        token = truncated ? response.nextContinuationToken() : null;
        if (truncated && token == null) {
          throw new EInvoiceException(
              ErrorCodes.ARCHIVE_UNAVAILABLE,
              "the archive listing said there is more and gave no continuation token");
        }
      }
    } catch (SdkException e) {
      throw unavailable(e);
    }
    if (keys.size() > bound) {
      return new ArchiveListing(keys.subList(0, bound), false);
    }
    // The loop ended with a full page while the bucket still said "more": not the end.
    return new ArchiveListing(keys, !truncated);
  }

  @Override
  public String describe() {
    return "s3 archive in bucket " + bucket + (prefix.isEmpty() ? "" : " under " + prefix);
  }

  /** The cause is kept for the server-side log; the message carries no endpoint and no key. */
  private static EInvoiceException unavailable(SdkException cause) {
    return new EInvoiceException(
        ErrorCodes.ARCHIVE_UNAVAILABLE, "the document archive is unavailable", cause);
  }
}
