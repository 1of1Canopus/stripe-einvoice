package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * D3-03: every vendored artefact this module ships is named, with its licence, in {@code NOTICE} -
 * the file a redistributor's attribution obligation actually attaches to - not only in {@code
 * PROVENANCE.md}, which is good practice but not the same thing as attribution.
 *
 * <p>This is the check that closes the blind spot the licence gate cannot see: the gate reads
 * resolved Maven dependencies, and a vendored file under {@code src/main/resources} is not one. A
 * new vendored artefact under an attribution group this test does not already recognise fails here,
 * by name, before it ships silently in a jar next to material it is not related to.
 *
 * <p>Shown red once, deliberately, before this test existed: adding an entry to {@code
 * CHECKSUMS.txt} for a path under a directory prefix absent from both {@code PROVENANCE.md} and
 * {@code NOTICE} failed {@link #every_vendored_artefact_group_is_named_in_provenance_and_notice()}
 * with a message naming the exact path. The fake entry and the fake file were both removed once
 * that was confirmed; nothing in the committed suite exercises the fake path.
 */
class VendoredArtefactAttributionTest {

  @Test
  void every_vendored_artefact_group_is_named_in_provenance_and_notice() {
    String provenance = read("/com/housedevinci/einvoice/reference/PROVENANCE.md");
    // Not "/META-INF/NOTICE" on the classpath: several dependencies on the test classpath ship a
    // META-INF/NOTICE of their own, and getResourceAsStream returns whichever one the classpath
    // happens to order first - reliably not this module's. This module's own NOTICE, the one
    // license-maven-plugin copies into the published jar, lives at the multi-module root.
    String notice = readNoticeFile();

    for (String path : VendoredArtefacts.expectedChecksums().keySet()) {
      if (path.equals("PROVENANCE.md")) {
        // The manifest's own evidence file, not itself a third-party artefact.
        continue;
      }
      String group = attributionGroup(path);
      assertThat(provenance)
          .as(
              "PROVENANCE.md records the source, release and licence for every vendored artefact"
                  + " group; %s (path %s) is not mentioned",
              group, path)
          .contains(group);
      assertThat(notice)
          .as(
              "NOTICE names every vendored artefact group and its licence, so a redistributor's"
                  + " attribution obligation has one place to read it; %s (path %s) is not"
                  + " mentioned",
              group, path)
          .contains(group);
    }
  }

  /**
   * The unit PROVENANCE.md and NOTICE actually attribute at: the bare file name for a schematron
   * stylesheet (each one is named individually in both documents, sometimes without its directory
   * prefix - "Files: CEN-EN16931-UBL.xslt ..." - so the file name is the strongest substring
   * common to every mention), or the shared {@code ubl/2.1/} prefix for the OASIS schema set,
   * fifteen files both documents attribute as one group under one licence rather than by name.
   */
  private static String attributionGroup(String path) {
    if (path.startsWith("ubl/2.1/")) {
      return "ubl/2.1/";
    }
    int lastSlash = path.lastIndexOf('/');
    return lastSlash < 0 ? path : path.substring(lastSlash + 1);
  }

  private static String read(String classpathResource) {
    try (InputStream in = VendoredArtefactAttributionTest.class.getResourceAsStream(
        classpathResource)) {
      assertThat(in).as("%s must be on the test classpath", classpathResource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIoTestException(classpathResource, e);
    }
  }

  private static String readNoticeFile() {
    // Surefire's working directory is this module's own root (stripe-einvoice-core); the reactor
    // root, and this module's own NOTICE, is one level up.
    Path notice = Path.of("..", "NOTICE");
    assertThat(Files.isRegularFile(notice))
        .as("%s must exist (this module's own NOTICE, not a dependency's)", notice.toAbsolutePath())
        .isTrue();
    try {
      return Files.readString(notice, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIoTestException(notice.toString(), e);
    }
  }

  private static final class UncheckedIoTestException extends RuntimeException {
    UncheckedIoTestException(String resource, IOException cause) {
      super("could not read " + resource, cause);
    }
  }
}
