package com.housedevinci.einvoice.adapter.s3;

import java.net.URI;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * One MinIO container for the whole S3 suite. A container per test class is minutes of CI time for
 * a store that holds no state between classes worth isolating: every test uses its own bucket.
 */
final class MinioSupport {

  private static final MinIOContainer CONTAINER =
      new MinIOContainer(
              // Pinned by digest, and from quay.io rather than Docker Hub: the image the module's
              // S3 behaviour is measured against must not be able to change under a moving tag.
              DockerImageName.parse(
                      "quay.io/minio/minio@sha256:"
                          + "a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
                  .asCompatibleSubstituteFor("minio/minio"))
          .withUserName("einvoicetest")
          .withPassword("einvoicetest");

  private MinioSupport() {}

  static synchronized S3Client client() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    return S3Client.builder()
        .endpointOverride(URI.create(CONTAINER.getS3URL()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(CONTAINER.getUserName(), CONTAINER.getPassword())))
        .region(Region.US_EAST_1)
        .httpClient(UrlConnectionHttpClient.builder().build())
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
