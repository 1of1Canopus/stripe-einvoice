package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.domain.EInvoiceException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Where issuance actually runs, with a bound at every edge (I-10).
 *
 * <p>"Hand the id to an async worker" is not a specification, and with virtual threads the natural
 * implementation spawns one per event and has no bound at all - a backlog replay then opens
 * thousands of concurrent connections to Stripe and to the pool. So: a <b>bounded queue</b>, a
 * <b>concurrency limit below the connection pool's size</b> so issuance cannot starve the host
 * application, and a <b>typed rejection past capacity</b>.
 *
 * <p>The part that makes it safe is what happens on rejection: <b>nothing</b>. The event is already
 * durably {@code RECEIVED}, so a rejected submission leaves it exactly where the sweeper will find
 * it. Back-pressure costs latency and never an event.
 *
 * <p>No {@code synchronized} on any path that performs JDBC or HTTP, so nothing here pins a carrier
 * thread if the host runs on virtual threads.
 */
public final class IssuanceWorker implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(IssuanceWorker.class);

  private final IssuanceUnitOfWork unitOfWork;
  private final ThreadPoolExecutor executor;
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong processed = new AtomicLong();

  public IssuanceWorker(IssuanceUnitOfWork unitOfWork, int concurrency, int queueCapacity) {
    this.unitOfWork = unitOfWork;
    this.executor =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            runnable -> {
              Thread thread = new Thread(runnable, "einvoice-issuance");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Queues one recorded event. Past capacity it stays {@code RECEIVED} and the sweeper takes it.
   */
  public boolean submit(String eventId) {
    try {
      executor.execute(() -> run(eventId));
      return true;
    } catch (RejectedExecutionException full) {
      rejected.incrementAndGet();
      log.warn(
          "einvoice: the issuance queue is full, so event {} stays RECEIVED for the sweeper."
              + " Back-pressure costs latency here and never an event.",
          eventId);
      return false;
    }
  }

  private void run(String eventId) {
    try {
      IssuanceUnitOfWork.Outcome outcome = unitOfWork.process(eventId);
      processed.incrementAndGet();
      log.debug("einvoice: event {} reached {} ({})", eventId, outcome.state(), outcome.code());
    } catch (EInvoiceException e) {
      // The state is already recorded on the row by the unit of work; this is the log line, and it
      // carries a code and an id, never a buyer field or a stack trace to a caller.
      log.warn("einvoice: issuance of event {} stopped with {}", eventId, e.code());
    } catch (RuntimeException e) {
      // Anything unmapped: recorded generically here, with the cause server-side only.
      log.error("einvoice: issuance of event {} failed unexpectedly", eventId, e);
    }
  }

  public long rejectedCount() {
    return rejected.get();
  }

  public long processedCount() {
    return processed.get();
  }

  /** What is waiting, for the metrics and for a saturation finding. */
  public int queueDepth() {
    return executor.getQueue().size();
  }

  @Override
  public void destroy() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
