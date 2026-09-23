package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A renderer for the tests, with the one property the unit of work depends on: the same input
 * renders the same bytes, under any time zone and any locale. The EN 16931 writers arrive with
 * their own design and their own validator suites; what is exercised here is the unit of work.
 */
public final class DeterministicRenderer implements DocumentRenderer {

  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

  private Throwable failure;
  private final java.util.concurrent.atomic.AtomicInteger renders =
      new java.util.concurrent.atomic.AtomicInteger();

  /** How many documents this renderer has produced - what a re-entered pipeline pays for. */
  public int renders() {
    return renders.get();
  }

  /**
   * @param failure a {@code RuntimeException} for a host-port-bug probe (D2-04), or a test's own
   *     {@code Error} to simulate a crash that no ordinary exception handling reaches
   */
  public void breakWith(Throwable failure) {
    this.failure = failure;
  }

  public void heal() {
    this.failure = null;
  }

  /**
   * This renderer maps nothing, so there is nothing it can refuse before a number exists: it
   * answers PASSED and leaves the failure injection to the render, which is where the probes that
   * use {@link #breakWith} expect it. The renderer with a real preflight is the module's own; a
   * renderer with none at all is {@code RendererWithoutPreflight}.
   */
  @Override
  public PreflightReport preflight(MappingInput input) {
    return PreflightReport.passed();
  }

  @Override
  public RenderedDocument render(DocumentInput input) {
    renders.incrementAndGet();
    if (failure instanceof RuntimeException re) {
      throw re;
    }
    if (failure instanceof Error err) {
      throw err;
    }
    StringBuilder out = new StringBuilder();
    out.append("<Invoice>");
    out.append("<ID>").append(input.legalNumber().value()).append("</ID>");
    out.append("<IssueDate>").append(ISO_DATE.format(input.issueDate())).append("</IssueDate>");
    out.append("<SourceId>").append(input.invoice().id()).append("</SourceId>");
    out.append("<Currency>").append(input.invoice().currency()).append("</Currency>");
    out.append("<Total>").append(input.invoice().totalMinor()).append("</Total>");
    for (SourceInvoice.SourceLine line : input.invoice().lines()) {
      out.append("<Line id=\"")
          .append(line.id())
          .append("\">")
          .append(line.netMinor())
          .append("</Line>");
    }
    out.append("</Invoice>");
    if (out.length() > 1_000_000) {
      throw new EInvoiceException(ErrorCodes.RENDER_FAILED, "rendered document is implausible");
    }
    return new RenderedDocument(out.toString().getBytes(StandardCharsets.UTF_8), "xml", "test");
  }
}
