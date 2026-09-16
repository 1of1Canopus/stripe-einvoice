package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * Runs one vendored compiled schematron over the exact bytes and reads the SVRL it produces.
 *
 * <p>Schematron is executed as <b>XSLT</b>, which means this class runs a third-party stylesheet as
 * code over untrusted input. D-07's controls all apply here and each has its own test:
 *
 * <ul>
 *   <li>the factory comes from {@link SecureXml#xslt2Factory(String)}: secure processing on,
 *       external DTD, schema and stylesheet access blanked, extension functions off both portably
 *       and by the processor's own switch, a URI resolver that <b>throws</b>;
 *   <li>the document is parsed by the hardened SAX parser, so a DOCTYPE in it is refused before the
 *       stylesheet sees it;
 *   <li>the SVRL output is bounded: a stylesheet that tries to produce an unbounded report is cut
 *       off by the writer itself, inside the transform;
 *   <li>the transform runs on a bounded pool with a wall-clock timeout, and a timeout or a
 *       saturated pool is {@code NOT_EVALUATED}, never a pass;
 *   <li>the stylesheet's checksum is recomputed before it is compiled, not only in a test.
 * </ul>
 *
 * <p>{@link Templates} is thread-safe and compiling one of these takes about a second, so they are
 * cached per artefact path in an instance field - never a static, so a test can build a validator
 * with a different processor and not inherit another test's compiled state.
 */
public final class SchematronValidator implements AutoCloseable {

  /** The bound on an SVRL report. A real one is tens of kilobytes. */
  static final int MAX_SVRL_CHARS = 4 * 1024 * 1024;

  private final String processorClassName;
  private final Duration timeout;
  private final Map<String, Templates> compiled = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor pool;

  /**
   * @param processorClassName the XSLT 2.0 {@code TransformerFactory} implementation to use
   * @param timeout the wall-clock bound on one transform
   * @param concurrency how many transforms may run at once
   */
  public SchematronValidator(String processorClassName, Duration timeout, int concurrency) {
    this.processorClassName = java.util.Objects.requireNonNull(processorClassName, "processor");
    this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero() || timeout.toMinutes() > 10) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.documents.validation-timeout lies between one second and ten minutes");
    }
    int threads = Math.max(1, Math.min(concurrency, 32));
    this.pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            // A bounded queue, and a rejection past it (checklist line 55): an unbounded queue
            // behind a slow dependency turns a burst into a permanent backlog.
            new java.util.concurrent.ArrayBlockingQueue<>(threads * 4),
            runnable -> {
              Thread thread = new Thread(runnable, "einvoice-schematron");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    this.pool.allowCoreThreadTimeOut(true);
  }

  /** One SVRL assertion that fired. */
  public record Finding(String ruleId, String flag, String text) {

    /** The flags that make a document invalid. {@code information} is recorded and does not. */
    public boolean fatal() {
      return !"information".equalsIgnoreCase(flag);
    }
  }

  /** True when an XSLT 2.0 processor is available at all. */
  public boolean processorAvailable() {
    try {
      SecureXml.xslt2Factory(processorClassName);
      return true;
    } catch (EInvoiceException absent) {
      return false;
    }
  }

  /**
   * @param bytes the exact bytes that will be archived
   * @param artefactPath the vendored stylesheet, from {@link VendoredArtefacts}
   * @return the assertions that fired, in the stylesheet's own order
   * @throws EInvoiceException {@link ErrorCodes#XSLT_PROCESSOR_MISSING}, {@link
   *     ErrorCodes#VALIDATION_TIMEOUT} or {@link ErrorCodes#VALIDATION_OUTPUT_TOO_LARGE}; each is a
   *     NOT_EVALUATED at the caller and never a pass
   */
  public List<Finding> validate(byte[] bytes, String artefactPath) {
    Templates templates = compiled.computeIfAbsent(artefactPath, this::compile);
    String svrl = transform(templates, bytes);
    return SvrlReader.read(svrl);
  }

  private Templates compile(String artefactPath) {
    VendoredArtefacts.requireIntact(artefactPath);
    TransformerFactory factory = SecureXml.xslt2Factory(processorClassName);
    try (InputStream in = VendoredArtefacts.open(artefactPath)) {
      // No system id: the stylesheet is self-contained and must not be able to resolve a sibling
      // by relative path. The refusing URI resolver would throw anyway; this removes the base.
      return factory.newTemplates(new StreamSource(in));
    } catch (TransformerException | IOException e) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "a vendored schematron stylesheet could not be compiled, so the rules it carries cannot"
              + " run and no document is judged by them",
          e);
    }
  }

  private String transform(Templates templates, byte[] bytes) {
    Future<String> future;
    try {
      future = pool.submit(() -> runTransform(templates, bytes));
    } catch (RejectedExecutionException saturated) {
      throw new EInvoiceException(
          ErrorCodes.VALIDATION_TIMEOUT,
          "the document validator is at capacity, so this document was not checked. It is not"
              + " archived: an unevaluated rule is not a passed rule");
    }
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException slow) {
      future.cancel(true);
      throw new EInvoiceException(
          ErrorCodes.VALIDATION_TIMEOUT,
          "a validation run passed its wall-clock bound and was abandoned. The document is not"
              + " archived: a rule that did not finish is not a rule that passed");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new EInvoiceException(
          ErrorCodes.VALIDATION_TIMEOUT, "a validation run was interrupted before it finished");
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof EInvoiceException typed) {
        throw typed;
      }
      // Anything a third-party stylesheet or the processor threw. The cause carries class names
      // and frames and stays server-side; the caller sees a code (checklist lines 47 and 58).
      throw new EInvoiceException(
          ErrorCodes.VALIDATION_NOT_EVALUATED,
          "the document validator could not complete a run, so nothing about this document was"
              + " established",
          cause);
    }
  }

  private String runTransform(Templates templates, byte[] bytes) {
    BoundedWriter out = new BoundedWriter(MAX_SVRL_CHARS);
    try {
      Transformer transformer = templates.newTransformer();
      transformer.setURIResolver(SecureXml.refusingUriResolver());
      transformer.setErrorListener(new SecureXml.RefusingErrorListener());
      javax.xml.parsers.SAXParser parser = SecureXml.saxParserFactory().newSAXParser();
      parser.getXMLReader().setEntityResolver(SecureXml.refusingEntityResolver());
      transformer.transform(
          new javax.xml.transform.sax.SAXSource(
              parser.getXMLReader(), new org.xml.sax.InputSource(new ByteArrayInputStream(bytes))),
          new StreamResult(out));
      return out.toString();
    } catch (BoundedWriter.TooMuchOutput tooMuch) {
      throw new EInvoiceException(
          ErrorCodes.VALIDATION_OUTPUT_TOO_LARGE,
          "a validation run produced more output than this module will read, and was stopped. The"
              + " document is not archived");
    } catch (TransformerException
        | org.xml.sax.SAXException
        | javax.xml.parsers.ParserConfigurationException e) {
      if (e.getCause() instanceof BoundedWriter.TooMuchOutput) {
        throw new EInvoiceException(
            ErrorCodes.VALIDATION_OUTPUT_TOO_LARGE,
            "a validation run produced more output than this module will read, and was stopped");
      }
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED,
          "the document could not be read by the validator: it is not well-formed XML this module"
              + " will parse, or it carries a document type declaration",
          e);
    }
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }

  /** A writer that refuses past a bound, from inside the transform (checklist line 12). */
  static final class BoundedWriter extends Writer {

    /** Thrown inside the transform, so the stylesheet is stopped rather than merely truncated. */
    static final class TooMuchOutput extends RuntimeException {
      private static final long serialVersionUID = 1L;

      TooMuchOutput() {
        super("validation output bound reached");
      }
    }

    private final StringBuilder out = new StringBuilder(64 * 1024);
    private final int max;

    BoundedWriter(int max) {
      this.max = max;
    }

    @Override
    public void write(char[] buffer, int offset, int length) {
      if (out.length() + length > max) {
        throw new TooMuchOutput();
      }
      out.append(buffer, offset, length);
    }

    @Override
    public void flush() {
      // Nothing buffered beyond the builder.
    }

    @Override
    public void close() {
      // Nothing to release.
    }

    @Override
    public String toString() {
      return out.toString();
    }
  }

  /** Reads the assertions out of an SVRL report with the hardened parser. */
  static final class SvrlReader {

    private static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";

    private SvrlReader() {}

    static List<Finding> read(String svrl) {
      List<Finding> findings = new ArrayList<>();
      try {
        javax.xml.parsers.SAXParser parser = SecureXml.saxParserFactory().newSAXParser();
        org.xml.sax.XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(SecureXml.refusingEntityResolver());
        reader.setContentHandler(new Handler(findings));
        reader.parse(
            new org.xml.sax.InputSource(
                new ByteArrayInputStream(svrl.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      } catch (org.xml.sax.SAXException
          | IOException
          | javax.xml.parsers.ParserConfigurationException e) {
        throw new EInvoiceException(
            ErrorCodes.VALIDATION_NOT_EVALUATED,
            "the validator produced a report this module could not read, so nothing about this"
                + " document was established",
            e);
      }
      return findings;
    }

    private static final class Handler extends org.xml.sax.helpers.DefaultHandler {

      private final List<Finding> findings;
      private final StringBuilder text = new StringBuilder();
      private String pendingRule;
      private String pendingFlag;
      private boolean inText;

      Handler(List<Finding> findings) {
        this.findings = findings;
      }

      @Override
      public void startElement(
          String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
        if (!SVRL_NS.equals(uri)) {
          return;
        }
        if ("failed-assert".equals(localName) || "successful-report".equals(localName)) {
          pendingRule = attributes.getValue("id");
          pendingFlag = attributes.getValue("flag");
          if (pendingRule == null || pendingRule.isBlank()) {
            pendingRule = "unnamed-rule";
          }
          if (pendingFlag == null || pendingFlag.isBlank()) {
            pendingFlag = "fatal";
          }
          text.setLength(0);
        } else if ("text".equals(localName) && pendingRule != null) {
          inText = true;
        }
      }

      @Override
      public void characters(char[] buffer, int start, int length) {
        if (inText) {
          text.append(buffer, start, length);
        }
      }

      @Override
      public void endElement(String uri, String localName, String qName) {
        if (!SVRL_NS.equals(uri)) {
          return;
        }
        if ("text".equals(localName)) {
          inText = false;
        } else if ("failed-assert".equals(localName) || "successful-report".equals(localName)) {
          findings.add(new Finding(pendingRule, pendingFlag, text.toString().strip()));
          pendingRule = null;
          pendingFlag = null;
          text.setLength(0);
        }
      }
    }
  }
}
