package com.housedevinci.einvoice.adapter.s3;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * One S3Mock container for the whole S3 suite. A container per test class is minutes of CI time for
 * a store that holds no state between classes worth isolating: every test uses its own bucket.
 *
 * <p>Was quay.io/minio/minio, pinned by digest. quay.io started requiring authentication for
 * anonymous manifest and tag pulls of that repository on 2026-09-24, which broke the release
 * pipeline's reproducibility build (a clean checkout cannot pull the image without credentials this
 * library must not require of its users). Replaced with Adobe's S3Mock (Apache-2.0), pulled
 * anonymously from Docker Hub and pinned by digest for the same reason MinIO was: the server this
 * module's S3 behaviour is measured against must not change under a moving tag. Verified
 * anonymously pullable with {@code docker pull
 * adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92} (tag 5.2.3)
 * on 2026-09-24. Mirroring MinIO's own image ourselves was rejected: MinIO's AGPL-3.0 licence
 * raises redistribution questions this fix does not have to answer. Skipping the test was rejected:
 * the load-bearing assertion here is that the module's own capability probe agrees with what a real
 * S3-compatible server does, which no other test in the suite covers.
 */
final class S3MockSupport {

  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>(
              DockerImageName.parse(
                  "adobe/s3mock@sha256:"
                      + "ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92"))
          .withExposedPorts(9090)
          .withEnv("initialBuckets", "")
          .waitingFor(Wait.forHttp("/").forStatusCodeMatching(code -> code < 500));

  private S3MockSupport() {}

  static synchronized S3Client client() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    URI endpoint =
        URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9090));
    return S3Client.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("einvoicetest", "einvoicetest")))
        .region(Region.US_EAST_1)
        .httpClient(UrlConnectionHttpClient.builder().build())
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
