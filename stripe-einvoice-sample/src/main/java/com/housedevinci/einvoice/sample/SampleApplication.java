package com.housedevinci.einvoice.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A Spring Boot application that allocates legal invoice numbers through the starter and serves the
 * series report.
 *
 * <p>It is deliberately small. What it demonstrates is the shape of the thing: the host application
 * owns the endpoints and their authorization, this module owns the number, and the void operation
 * has no endpoint at all unless the host writes one.
 */
@SpringBootApplication
public class SampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }
}
