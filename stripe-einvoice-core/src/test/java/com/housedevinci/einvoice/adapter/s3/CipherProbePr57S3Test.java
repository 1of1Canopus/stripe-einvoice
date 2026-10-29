package com.housedevinci.einvoice.adapter.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.ArchiveListing;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Security review of PR 57, pass 1 on 1ef0995: D-PR57-5. The S3 store lists without {@code
 * encoding-type=url}. AWS states that a {@code ListObjectsV2} "200 OK response can contain valid
 * or invalid XML" and that "the XML 1.0 parser can't parse certain characters, such as characters
 * with an ASCII value from 0 to 10"; {@code encoding-type=url} is the documented way to receive
 * such keys. Without it, a bucket writer's key name either fails the parse on every pass ({@code
 * DEI-241}, the walk stopped for ever) or loses a character (XML end-of-line handling turns CR
 * into LF, so {@code INV-\r} and {@code INV-\n} become one key and the order guard refuses the
 * listing with {@code DEI-284} on every pass). The D1 invariant says no name can stop the walk.
 */
class CipherProbePr57S3Test {

  private static final String HEAD =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult"
          + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>b</Name>"
          + "<Prefix>live/acme/</Prefix><MaxKeys>10</MaxKeys><IsTruncated>false</IsTruncated>";

  /**
   * A bucket that holds {@code INV-\r.xml}, {@code INV-\n.xml} and {@code INV-\x01.xml}, and
   * answers as the AWS pages describe: keys percent-encoded when the request asks for {@code
   * encoding-type=url}, raw characters in the XML otherwise.
   */
  private static S3Client clientFor(HttpServer server) {
    server.createContext(
        "/",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          boolean url = query != null && query.contains("encoding-type=url");
          String body =
              url
                  ? HEAD
                      + "<EncodingType>url</EncodingType>"
                      + "<Contents><Key>live%2Facme%2F2026%2FINV-%01.xml</Key><Size>1</Size></Contents>"
                      + "<Contents><Key>live%2Facme%2F2026%2FINV-%0A.xml</Key><Size>1</Size></Contents>"
                      + "<Contents><Key>live%2Facme%2F2026%2FINV-%0D.xml</Key><Size>1</Size></Contents>"
                      + "</ListBucketResult>"
                  : HEAD
                      + "<Contents><Key>live/acme/2026/INV-\u0001.xml</Key><Size>1</Size></Contents>"
                      + "<Contents><Key>live/acme/2026/INV-\n.xml</Key><Size>1</Size></Contents>"
                      + "<Contents><Key>live/acme/2026/INV-\r.xml</Key><Size>1</Size></Contents>"
                      + "</ListBucketResult>";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
        .region(Region.US_EAST_1)
        .httpClient(UrlConnectionHttpClient.builder().build())
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Test
  void probe_a_control_character_in_an_s3_key_stops_the_walk_for_ever() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      ArchiveListing listing =
          new S3ArchiveStore(clientFor(server), "b", "").listAfter("live/acme/", "", 10);

      assertThat(listing.keys())
          .containsExactly(
              "live/acme/2026/INV-\u0001.xml",
              "live/acme/2026/INV-\n.xml",
              "live/acme/2026/INV-\r.xml");
    } finally {
      server.stop(0);
    }
  }

  /**
   * The same on the S3-compatible server the suite uses: the three objects are accepted, and the
   * listing fails with a 500 on every pass. Whatever the fix, a listing of a bucket holding these
   * names must name them, not fail.
   */
  @Test
  void probe_an_s3_compatible_server_fails_the_listing_on_a_control_character_key() {
    S3Client client = S3MockSupport.client();
    String bucket = "names-" + System.nanoTime();
    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    for (String key :
        new String[] {"live/acme/2026/INV-\u0001.xml", "live/acme/2026/later.xml"}) {
      client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("x"));
    }

    ArchiveListing listing = new S3ArchiveStore(client, bucket, "").listAfter("live/acme/", "", 10);

    assertThat(listing.keys())
        .containsExactly("live/acme/2026/INV-\u0001.xml", "live/acme/2026/later.xml");
  }
}
