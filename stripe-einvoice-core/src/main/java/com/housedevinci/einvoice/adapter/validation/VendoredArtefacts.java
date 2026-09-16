package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The vendored schema and stylesheet set, and its checksum manifest (D-24).
 *
 * <p>Nothing is downloaded at build time or at run time. Everything is read from the classpath and
 * from nowhere else: {@link #open(String)} refuses a path that is not in the manifest, so a caller
 * cannot reach a classpath resource this module never vendored. The SHA-256 of every file is in
 * {@code reference/CHECKSUMS.txt}, which is itself on the classpath, and a test recomputes every
 * one on every build.
 *
 * <p>A stylesheet is <b>executable code</b> and these are run over documents that will be filed
 * with a tax authority. Downloading one during a build, into a module that then runs it, is the
 * supply-chain shape the release checklist exists to refuse; a checksum that nothing recomputes is
 * a comment. {@code PROVENANCE.md}, beside the manifest, records per file where it came from, which
 * release, when it was fetched and under what licence.
 */
public final class VendoredArtefacts {

  /** Classpath prefix every vendored file sits under. */
  public static final String ROOT = "/reference/";

  /** The manifest, relative to {@link #ROOT}. */
  public static final String MANIFEST = "CHECKSUMS.txt";

  /** UBL 2.1 Invoice schema. */
  public static final String UBL_INVOICE_XSD = "ubl/2.1/maindoc/UBL-Invoice-2.1.xsd";

  /** UBL 2.1 CreditNote schema. */
  public static final String UBL_CREDIT_NOTE_XSD = "ubl/2.1/maindoc/UBL-CreditNote-2.1.xsd";

  /** The CEN EN 16931 rules, UBL syntax, as the KoSIT validator configuration compiles them. */
  public static final String EN16931_UBL_XSLT = "schematron/en16931/EN16931-UBL-validation.xsl";

  /** The CIUS XRechnung 3.0.2 rules, UBL syntax. */
  public static final String XRECHNUNG_UBL_XSLT =
      "schematron/xrechnung/XRechnung-UBL-validation.xsl";

  /** The CEN EN 16931 rules as OpenPeppol compiles them for the Peppol release. */
  public static final String PEPPOL_CEN_UBL_XSLT = "schematron/peppol/CEN-EN16931-UBL.xslt";

  /** The Peppol-specific rules, including the code-list rules. */
  public static final String PEPPOL_BIS_UBL_XSLT = "schematron/peppol/PEPPOL-EN16931-UBL.xslt";

  private static final Map<String, String> EXPECTED = readManifest();

  private VendoredArtefacts() {}

  /**
   * @return the manifest: relative path to lower-case hex SHA-256, in the manifest's order
   */
  public static Map<String, String> expectedChecksums() {
    return Collections.unmodifiableMap(EXPECTED);
  }

  /**
   * Opens a vendored file.
   *
   * @param relativePath path under {@link #ROOT}
   * @return the stream, which the caller closes
   * @throws EInvoiceException {@link ErrorCodes#ARTEFACT_TAMPERED} when the path is not in the
   *     manifest or the file is not on the classpath
   */
  public static InputStream open(String relativePath) {
    if (!EXPECTED.containsKey(relativePath)) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "a validation artefact was requested that this module does not vendor. Only the files in"
              + " reference/CHECKSUMS.txt are reachable, so a classpath entry cannot stand in for"
              + " one of them");
    }
    InputStream in = VendoredArtefacts.class.getResourceAsStream(ROOT + relativePath);
    if (in == null) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "a vendored validation artefact is missing from the classpath, so the rules it carries"
              + " cannot run and no document is judged by them");
    }
    return in;
  }

  /**
   * @param relativePath path under {@link #ROOT}
   * @return the file's actual SHA-256, lower-case hex
   */
  public static String actualChecksum(String relativePath) {
    try (InputStream in = open(relativePath)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED, "a vendored validation artefact could not be read", e);
    } catch (NoSuchAlgorithmException e) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED, "SHA-256 is required by every JDK", e);
    }
  }

  /**
   * Recomputes one artefact's checksum and refuses it if it moved.
   *
   * <p>Called before a stylesheet is compiled, not only in a test: the test proves the repository
   * is intact, and this proves the <em>jar that is running</em> is. They are different claims and
   * only one of them is about production.
   */
  public static void requireIntact(String relativePath) {
    String expected = EXPECTED.get(relativePath);
    String actual = actualChecksum(relativePath);
    if (!java.security.MessageDigest.isEqual(
        expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8))) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "a vendored validation artefact no longer hashes to what this module recorded for it."
              + " A stylesheet is executable code and this one is not the one that was reviewed");
    }
  }

  private static Map<String, String> readManifest() {
    Map<String, String> map = new LinkedHashMap<>();
    try (InputStream in = VendoredArtefacts.class.getResourceAsStream(ROOT + MANIFEST)) {
      if (in == null) {
        throw new EInvoiceException(
            ErrorCodes.ARTEFACT_TAMPERED, "the validation artefact checksum manifest is missing");
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : text.split("\n")) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int gap = trimmed.indexOf("  ");
        if (gap < 0) {
          throw new EInvoiceException(
              ErrorCodes.ARTEFACT_TAMPERED,
              "the validation artefact checksum manifest has a line this module cannot parse, and"
                  + " an unparseable manifest is not a clean one");
        }
        map.put(trimmed.substring(gap + 2).strip(), trimmed.substring(0, gap).strip());
      }
    } catch (IOException e) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "the validation artefact checksum manifest is unreadable",
          e);
    }
    if (map.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED, "the validation artefact checksum manifest is empty");
    }
    return map;
  }
}
