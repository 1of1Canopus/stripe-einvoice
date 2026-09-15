package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.WebhookSignature;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one place the internet reaches this module.
 *
 * <p>The order is the whole design (I-01), and the response contract splits on a single question:
 * <b>is this provably from Stripe?</b>
 *
 * <ul>
 *   <li><b>400</b> - not provably from Stripe, or not readable at all: a wrong content type, a body
 *       past the cap, a missing or bad signature, identity fields that will not parse. Nothing
 *       durable is written, which is correct: we cannot attribute it.
 *   <li><b>200</b> - provably from Stripe and durably recorded. That includes a duplicate, an event
 *       type we do not act on, an issuance that later fails, and every routing refusal - a version
 *       skew, a mode mismatch, an unknown account - each as a terminal state on the row. Stripe
 *       treats a 400 as a failed delivery and disables endpoints that keep failing, and an API
 *       version skew applies to every event on the account at once: a control designed to refuse
 *       one bad event must not be able to stop the whole intake.
 *   <li><b>503</b> - the database is unreachable, so we could not make the record durable. The only
 *       5xx, and the only case where a redelivery is what we want.
 * </ul>
 *
 * <p>The body is read as bytes through a limiting stream that counts as it reads, never as a
 * container-decoded {@code String} and never on the strength of {@code Content-Length}: a chunked
 * request carries no length and a lying one carries a wrong one (I-09, D-19).
 *
 * <p>Nothing here logs the body, the signature header, a secret, or a buyer field, at any level.
 */
@RestController
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final InboundEventStore inbound;
  private final IssuanceWorker worker;
  private final Map<String, String> webhookSecrets;
  private final Duration tolerance;
  private final int maxBodyBytes;
  private final Mode mode;
  private final Clock clock;

  public StripeWebhookController(
      InboundEventStore inbound,
      IssuanceWorker worker,
      Map<String, String> webhookSecrets,
      Duration tolerance,
      int maxBodyBytes,
      Mode mode,
      Clock clock) {
    this.inbound = inbound;
    this.worker = worker;
    this.webhookSecrets = Map.copyOf(webhookSecrets);
    this.tolerance = tolerance;
    this.maxBodyBytes = maxBodyBytes;
    this.mode = mode;
    this.clock = clock;
  }

  @PostMapping(path = "${einvoice.webhook.path:/webhooks/stripe}")
  public ResponseEntity<Void> receive(HttpServletRequest request) {
    if (!isJson(request.getContentType())) {
      return ResponseEntity.badRequest().build();
    }
    if (request.getContentLengthLong() > maxBodyBytes) {
      // An early reject only. The real check is the counting read below, because this header is
      // absent on a chunked request and wrong on a lying one.
      return ResponseEntity.badRequest().build();
    }
    byte[] body;
    try {
      body = readCapped(request);
    } catch (IOException tooLargeOrBroken) {
      return ResponseEntity.badRequest().build();
    }

    String signatureKeyId;
    EventIdentity identity;
    try {
      signatureKeyId =
          WebhookSignature.verify(
              body,
              request.getHeader(WebhookSignature.HEADER),
              webhookSecrets,
              tolerance,
              clock.instant());
      identity = EventIdentity.from(body);
    } catch (EInvoiceException refused) {
      // No echo of the body, the header or which check failed: an endpoint that tells the internet
      // which half of the check it failed is a free oracle.
      log.debug("einvoice: a webhook request was refused ({})", refused.code());
      return ResponseEntity.badRequest().build();
    }

    try {
      boolean first =
          inbound.record(
              InboundEvent.received(identity, mode, signatureKeyId, body, clock.instant()), body);
      if (first) {
        // Handed over after the commit, and a rejection past capacity simply leaves the row
        // RECEIVED for the sweeper: back-pressure costs latency and never an event.
        worker.submit(identity.eventId());
      }
      return ResponseEntity.ok().build();
    } catch (EInvoiceException e) {
      if (ErrorCodes.STORE_UNAVAILABLE.equals(e.code())) {
        log.warn("einvoice: an event could not be recorded durably ({})", e.code());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
      }
      log.debug("einvoice: an event was refused after verification ({})", e.code());
      return ResponseEntity.badRequest().build();
    }
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) {
      return false;
    }
    String normalised = contentType.toLowerCase(Locale.ROOT);
    return normalised.startsWith("application/json") || normalised.startsWith("text/json");
  }

  /** Counts bytes as it reads and aborts past the cap, before anything is buffered whole. */
  private byte[] readCapped(HttpServletRequest request) throws IOException {
    try (InputStream in = request.getInputStream()) {
      byte[] buffer = new byte[Math.min(8192, maxBodyBytes)];
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      int read;
      int total = 0;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > maxBodyBytes) {
          throw new BodyTooLarge();
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  /** Not an EInvoiceException: this never reaches a caller as anything but a 400. */
  static final class BodyTooLarge extends IOException {
    private static final long serialVersionUID = 1L;
  }
}
