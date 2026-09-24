package com.housedevinci.einvoice.adapter.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Cipher probes for PR 16 (the archive test's object store moved from MinIO to S3Mock).
 *
 * <p>D16-01 asks whether the replacement server still proves what the old one proved: that the 412
 * the adapter depends on is caused by {@code If-None-Match: *} and not by the server refusing every
 * second write. D16-02 asks whether anything in the suite refuses a tag-only image reference, i.e.
 * whether the digest pin is a guard or a convention.
 */
class CipherProbePr16Test {

  private static final String BUCKET = "cipher-probe-pr16";

  private static S3Client client() {
    S3Client c = S3MockSupport.client();
    try {
      c.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    } catch (S3Exception alreadyThere) {
      // second run
    }
    return c;
  }

  /** D16-01: an unconditional PUT must overwrite, or the conditional 412 proves nothing. */
  @Test
  void probe_the_412_comes_from_the_conditional_header_and_not_from_the_server() {
    S3Client c = client();
    String key = "probe/" + UUID.randomUUID();

    c.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromString("a"));
    // No If-None-Match: a real S3 overwrites. A server that refuses here would make the
    // conditional test in S3ArchiveStoreTest pass for the wrong reason.
    c.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromString("b"));
    assertThat(
            c.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .asUtf8String())
        .isEqualTo("b");

    // And with the header the same second write is refused with 412.
    String conditional = "probe/" + UUID.randomUUID();
    c.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(conditional).ifNoneMatch("*").build(),
        RequestBody.fromString("a"));
    int status = 0;
    try {
      c.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key(conditional).ifNoneMatch("*").build(),
          RequestBody.fromString("b"));
    } catch (S3Exception refused) {
      status = refused.statusCode();
    }
    assertThat(status).isEqualTo(412);
  }

  /** D16-02: every Testcontainers image in the tree is referenced by digest, never by tag. */
  @Test
  void probe_every_container_image_reference_is_pinned_by_digest() throws IOException {
    Path root = Path.of("..").toRealPath();
    Pattern parse = Pattern.compile("DockerImageName\\s*\\.\\s*parse\\s*\\(([^;]*?)\\)");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file :
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.toString().contains("/target/"))
              .toList()) {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = parse.matcher(text);
        while (m.find()) {
          String argument =
              m.group(1).replaceAll("\\s+", "").replace("\"+\"", "").replace("\"", "");
          if (!argument.contains("@sha256:")) {
            offenders.add(file + " -> " + argument);
          }
        }
      }
    }
    assertThat(offenders).isEmpty();
  }
}
