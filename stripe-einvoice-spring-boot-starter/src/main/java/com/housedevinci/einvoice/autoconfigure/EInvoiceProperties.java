package com.housedevinci.einvoice.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything this module reads from configuration, validated at startup rather than at the first
 * invoice.
 *
 * <p>Two rules run through all of it. Every control is <b>on by default</b>, and every weaker mode
 * is an explicit property that logs a WARN at <em>every</em> startup, not once - an operator who
 * reads one boot log a month must see it in that log. And nothing here has a fail-open setting:
 * there is no property that disables the uniqueness constraints, the triggers, the resume-by-source
 * behaviour or the chain.
 */
@ConfigurationProperties("einvoice")
@Validated
public class EInvoiceProperties {

  /**
   * Live or test. {@code test} is the weaker mode: it WARNs at every startup and routes to a
   * separate series row and archive root, so a click in the Stripe test dashboard can never consume
   * a number from the production series (D-01).
   */
  @NotBlank private String mode = "live";

  /**
   * Run the bundled PostgreSQL schema at startup. Idempotent, and safe beside the host's Flyway.
   */
  private boolean initializeSchema = true;

  @Valid @NotNull private final Seller seller = new Seller();
  @Valid @NotNull private final Numbering numbering = new Numbering();
  @Valid @NotNull private final Chain chain = new Chain();

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public boolean isInitializeSchema() {
    return initializeSchema;
  }

  public void setInitializeSchema(boolean initializeSchema) {
    this.initializeSchema = initializeSchema;
  }

  public Seller getSeller() {
    return seller;
  }

  public Numbering getNumbering() {
    return numbering;
  }

  public Chain getChain() {
    return chain;
  }

  /** The one seller 0.1.0 issues for (Decision 1). Connect and many sellers are a later version. */
  public static class Seller {

    /** The seller's id in this module's tables. Present in every predicate and every key. */
    @NotBlank private String id = "";

    /**
     * The tax jurisdiction's zone, required and with no default (D-15). BT-2 is derived from
     * Stripe's {@code finalized_at} converted in <em>this</em> zone, so an invoice finalised at
     * 23:30 UTC lands in the day, month and quarter the seller's tax authority would put it in,
     * rather than in whichever one the container's clock suggests.
     */
    @NotBlank private String taxZone = "";

    /**
     * The Stripe account id this seller is reached by, or blank for the platform account. Events
     * are routed by Stripe's own {@code account} field, never by payload metadata (D-01, D-10).
     */
    private String stripeAccountId = "";

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getTaxZone() {
      return taxZone;
    }

    public void setTaxZone(String taxZone) {
      this.taxZone = taxZone;
    }

    public String getStripeAccountId() {
      return stripeAccountId;
    }

    public void setStripeAccountId(String stripeAccountId) {
      this.stripeAccountId = stripeAccountId;
    }
  }

  /** The numbering series this seller issues from. */
  public static class Numbering {

    /** The series name. One series in 0.1.0; the column exists so many is configuration. */
    @NotBlank private String series = "DEFAULT";

    /** The rendered prefix, required and with no default: {@code [A-Z0-9-]}, at most 32. */
    @NotBlank private String prefix = "";

    /** Zero-padding width. Outside 4..12 the application refuses to start. */
    @Min(4)
    @Max(12)
    private int width = 6;

    /**
     * Restart the counter at 1 each fiscal year, with the year in the series key. {@code false} is
     * the weaker mode and WARNs at every startup: a continuous multi-year series is legal on some
     * readings, so it exists, loudly.
     */
    private boolean fiscalYearReset = true;

    /** How long an allocation may wait on the series row before giving up. */
    @NotNull private Duration allocationTimeout = Duration.ofSeconds(5);

    public String getSeries() {
      return series;
    }

    public void setSeries(String series) {
      this.series = series;
    }

    public String getPrefix() {
      return prefix;
    }

    public void setPrefix(String prefix) {
      this.prefix = prefix;
    }

    public int getWidth() {
      return width;
    }

    public void setWidth(int width) {
      this.width = width;
    }

    public boolean isFiscalYearReset() {
      return fiscalYearReset;
    }

    public void setFiscalYearReset(boolean fiscalYearReset) {
      this.fiscalYearReset = fiscalYearReset;
    }

    public Duration getAllocationTimeout() {
      return allocationTimeout;
    }

    public void setAllocationTimeout(Duration allocationTimeout) {
      this.allocationTimeout = allocationTimeout;
    }
  }

  /**
   * The issuance log's keyed hash chain (Decision 6: in the free core, not a paid feature).
   *
   * <p>The secret is environment-only and has no default. It is <b>not</b> the Stripe webhook
   * secret: one is what proves a request came from Stripe, the other is what proves our own record
   * was not rewritten afterwards, and a single value for both means one leak costs both properties.
   */
  public static class Chain {

    /**
     * Base64 of at least 32 bytes, from the environment. Required unless {@code unkeyed} is set.
     *
     * <p>Base64 only, never a raw passphrase: a 32-character passphrase is also valid Base64, so a
     * decoder that guesses would silently use 24 different bytes as the key.
     */
    private String hmacSecret = "";

    /** The id written into every row's hashed material, so rotation is data, not a format break. */
    private String hmacKeyId = "";

    /** Retired key ids the verifier still needs during a rotation window. */
    private final Map<String, String> hmacKeys = new LinkedHashMap<>();

    /**
     * The WARNed opt-out. An unkeyed log's integrity rests only on database privilege separation:
     * anyone who can write a row can recompute every hash after it. The verifier then reports
     * {@code INTACT_UNKEYED}, never {@code INTACT}.
     */
    private boolean unkeyed;

    public String getHmacSecret() {
      return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
      this.hmacSecret = hmacSecret;
    }

    public String getHmacKeyId() {
      return hmacKeyId;
    }

    public void setHmacKeyId(String hmacKeyId) {
      this.hmacKeyId = hmacKeyId;
    }

    public Map<String, String> getHmacKeys() {
      return hmacKeys;
    }

    public boolean isUnkeyed() {
      return unkeyed;
    }

    public void setUnkeyed(boolean unkeyed) {
      this.unkeyed = unkeyed;
    }
  }
}
