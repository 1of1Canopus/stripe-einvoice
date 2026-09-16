package com.housedevinci.einvoice.adapter.stripe;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A local HTTP server that answers like Stripe.
 *
 * <p>The adapter's properties - the version header on every request, pagination to exhaustion, a
 * refusal on a residual page, an outage mapped to a retryable code, and the absence of any mutating
 * call - are properties of the requests it makes and the answers it accepts. A mock of the SDK
 * would assert that the adapter agrees with itself; this asserts what goes on the wire.
 */
final class StripeStub implements AutoCloseable {

  private final HttpServer server;
  private final Map<String, List<String>> responses = new LinkedHashMap<>();
  private final List<String> requests = new ArrayList<>();
  private final List<String> versionHeaders = new ArrayList<>();
  private int status = 200;

  StripeStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String query = exchange.getRequestURI().getQuery();
          requests.add(
              exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
          String version = exchange.getRequestHeaders().getFirst("Stripe-Version");
          versionHeaders.add(version == null ? "" : version);
          String body = next(path);
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  /** Queues one response per call for a path; the last one repeats. */
  StripeStub answer(String path, String json) {
    responses.computeIfAbsent(path, key -> new ArrayList<>()).add(json);
    return this;
  }

  void failWith(int httpStatus) {
    this.status = httpStatus;
  }

  private String next(String path) {
    List<String> queued = responses.get(path);
    if (queued == null || queued.isEmpty()) {
      return "{\"error\":{\"message\":\"no stub\",\"type\":\"invalid_request_error\"}}";
    }
    return queued.size() == 1 ? queued.get(0) : queued.remove(0);
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<String> requests() {
    return List.copyOf(requests);
  }

  List<String> versionHeaders() {
    return List.copyOf(versionHeaders);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
