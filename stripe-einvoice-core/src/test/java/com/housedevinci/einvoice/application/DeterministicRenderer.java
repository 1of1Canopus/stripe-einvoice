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

  private RuntimeException failure;

  public void breakWith(RuntimeException failure) {
    this.failure = failure;
  }

  public void heal() {
    this.failure = null;
  }

  @Override
  public RenderedDocument render(DocumentInput input) {
    if (failure != null) {
      throw failure;
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
