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
  @Valid @NotNull private final Stripe stripe = new Stripe();
  @Valid @NotNull private final Webhook webhook = new Webhook();
  @Valid @NotNull private final Issuance issuance = new Issuance();
  @Valid @NotNull private final Archive archive = new Archive();
  @Valid @NotNull private final Inbound inbound = new Inbound();
  @Valid @NotNull private final Reconcile reconcile = new Reconcile();

  /**
   * The id and version of the rule pack an issued document was checked against, recorded on the row
   * and inside the hashed material so a re-validation years later uses the pack that was in force
   * rather than today's.
   */
  @NotBlank private String rulePackVersion = "unversioned";

  public String getRulePackVersion() {
    return rulePackVersion;
  }

  public void setRulePackVersion(String rulePackVersion) {
    this.rulePackVersion = rulePackVersion;
  }

  public Stripe getStripe() {
    return stripe;
  }

  public Webhook getWebhook() {
    return webhook;
  }

  public Issuance getIssuance() {
    return issuance;
  }

  public Archive getArchive() {
    return archive;
  }

  public Inbound getInbound() {
    return inbound;
  }

  public Reconcile getReconcile() {
    return reconcile;
  }

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

  /**
   * The Stripe account this module reads from, and the secrets it reads with.
   *
   * <p>Both secrets are required, environment-only, format-checked at startup and excluded from
   * actuator by name rather than by the framework's name sanitisation (checklist line 44). They are
   * different secrets with different jobs: one proves a request came from Stripe, the other reads
   * every invoice on the account.
   */
  public static class Stripe {

    /** Restricted, read-scoped, env-only, no default. */
    private String apiKey = "";

    /**
     * The webhook secret keyring: {@code einvoice.stripe.webhook-secrets.<id>=whsec_...}. Every
     * active id is tried and the one that verified is recorded on the inbound row, because Stripe's
     * own rotation leaves two secrets live and a single-secret verifier means downtime or a
     * rotation nobody performs.
     */
    private final Map<String, String> webhookSecrets = new LinkedHashMap<>();

    /**
     * The pinned API version. Empty means the SDK's own, which is the only version its generated
     * models can speak; naming a different one is refused at startup rather than sent as a header
     * the code cannot honour.
     */
    private String apiVersion = "";

    /**
     * Refuse an API key that is not a restricted key. The weaker mode WARNs at every startup: this
     * module needs read scopes on invoices, customers, credit notes and tax rates and never writes
     * to Stripe, so a secret key here is authority nobody asked for.
     */
    private boolean requireRestrictedKey = true;

    /** The SDK's usage telemetry. Off by default; the weaker mode WARNs at every startup. */
    private boolean telemetry;

    /**
     * How far before the reconciliation window to start listing invoices. Stripe filters a list by
     * {@code created}, not by finalisation, and a subscription invoice can sit as a draft for weeks
     * - so this is what decides whether such an invoice is visible to the sweep at all.
     */
    @NotNull private Duration listLookback = Duration.ofDays(30);

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public Map<String, String> getWebhookSecrets() {
      return webhookSecrets;
    }

    public String getApiVersion() {
      return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
      this.apiVersion = apiVersion;
    }

    public boolean isRequireRestrictedKey() {
      return requireRestrictedKey;
    }

    public void setRequireRestrictedKey(boolean requireRestrictedKey) {
      this.requireRestrictedKey = requireRestrictedKey;
    }

    public boolean isTelemetry() {
      return telemetry;
    }

    public void setTelemetry(boolean telemetry) {
      this.telemetry = telemetry;
    }

    public Duration getListLookback() {
      return listLookback;
    }

    public void setListLookback(Duration listLookback) {
      this.listLookback = listLookback;
    }
  }

  /** The unauthenticated edge: the one place the internet reaches this module. */
  public static class Webhook {

    /** Where the endpoint is mapped. The host application permits exactly this path. */
    @NotBlank private String path = "/webhooks/stripe";

    /** Auto-configure the endpoint at all. A host with its own controller sets this false. */
    private boolean enabled = true;

    /**
     * The body cap, counted as bytes are read rather than believed from {@code Content-Length}: a
     * chunked request carries no length and a lying one carries a wrong one. With the authoritative
     * re-fetch, a large body is never needed.
     */
    @Min(1024)
    @Max(8 * 1024 * 1024)
    private int maxBodyBytes = 1024 * 1024;

    /**
     * The signature tolerance window. Bounded above: a large window plus a ledger gap is a replay.
     */
    @NotNull private Duration tolerance = Duration.ofSeconds(300);

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxBodyBytes() {
      return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
      this.maxBodyBytes = maxBodyBytes;
    }

    public Duration getTolerance() {
      return tolerance;
    }

    public void setTolerance(Duration tolerance) {
      this.tolerance = tolerance;
    }
  }

  /** The worker, the retries and the watcher. */
  public static class Issuance {

    /** Run issuance at all. A host that only wants the numbering API sets this false. */
    private boolean enabled = true;

    /**
     * How many events may be issued at once. Deliberately small and deliberately below a normal
     * connection pool: issuance must not be able to starve the host application of connections.
     */
    @Min(1)
    @Max(64)
    private int concurrency = 2;

    /** The bounded queue. Past it, an event simply stays RECEIVED and the sweeper takes it. */
    @Min(1)
    @Max(100_000)
    private int queueCapacity = 1_000;

    /** The first retry delay; it doubles per attempt up to {@code max-retry-backoff}. */
    @NotNull private Duration retryBackoff = Duration.ofMinutes(1);

    @NotNull private Duration maxRetryBackoff = Duration.ofHours(1);

    /**
     * How long a retryable terminal keeps being re-picked. Provider incidents run hours and a
     * regional problem at the customer runs longer, so this is measured in days (I-08).
     */
    @NotNull private Duration retryCeiling = Duration.ofHours(72);

    /** Past this, an allocated number with no document is a compliance finding. */
    @NotNull private Duration alertAfter = Duration.ofHours(6);

    /** How often the sweeper re-picks what is due. */
    @NotNull private Duration sweepInterval = Duration.ofMinutes(1);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getConcurrency() {
      return concurrency;
    }

    public void setConcurrency(int concurrency) {
      this.concurrency = concurrency;
    }

    public int getQueueCapacity() {
      return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
      this.queueCapacity = queueCapacity;
    }

    public Duration getRetryBackoff() {
      return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
      this.retryBackoff = retryBackoff;
    }

    public Duration getMaxRetryBackoff() {
      return maxRetryBackoff;
    }

    public void setMaxRetryBackoff(Duration maxRetryBackoff) {
      this.maxRetryBackoff = maxRetryBackoff;
    }

    public Duration getRetryCeiling() {
      return retryCeiling;
    }

    public void setRetryCeiling(Duration retryCeiling) {
      this.retryCeiling = retryCeiling;
    }

    public Duration getAlertAfter() {
      return alertAfter;
    }

    public void setAlertAfter(Duration alertAfter) {
      this.alertAfter = alertAfter;
    }

    public Duration getSweepInterval() {
      return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
      this.sweepInterval = sweepInterval;
    }
  }

  /** Where the legal originals live. Write-once has no opt-out; the store is probed at startup. */
  public static class Archive {

    /** {@code filesystem} or {@code s3}; a host that supplies its own ArchiveStore bean wins. */
    @NotBlank private String type = "filesystem";

    /** The archive root for {@code type=filesystem}. */
    private String root = "";

    /**
     * The explicit weaker mode for a store that cannot create an object only if it is absent
     * (I-06). It WARNs at every startup, and the mandatory read-back it turns on narrows the window
     * between two writers without closing it - which the docs say plainly.
     */
    private boolean allowNonAtomicStore;

    @Valid @NotNull private final S3 s3 = new S3();

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getRoot() {
      return root;
    }

    public void setRoot(String root) {
      this.root = root;
    }

    public boolean isAllowNonAtomicStore() {
      return allowNonAtomicStore;
    }

    public void setAllowNonAtomicStore(boolean allowNonAtomicStore) {
      this.allowNonAtomicStore = allowNonAtomicStore;
    }

    public S3 getS3() {
      return s3;
    }

    /** An S3-compatible bucket. Credentials come from the AWS SDK's own chain, never from here. */
    public static class S3 {

      private String bucket = "";

      private String prefix = "";

      private String region = "";

      /** For an S3-compatible store that is not AWS. Static configuration only, never a payload. */
      private String endpoint = "";

      private boolean pathStyleAccess;

      public String getBucket() {
        return bucket;
      }

      public void setBucket(String bucket) {
        this.bucket = bucket;
      }

      public String getPrefix() {
        return prefix;
      }

      public void setPrefix(String prefix) {
        this.prefix = prefix;
      }

      public String getRegion() {
        return region;
      }

      public void setRegion(String region) {
        this.region = region;
      }

      public String getEndpoint() {
        return endpoint;
      }

      public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
      }

      public boolean isPathStyleAccess() {
        return pathStyleAccess;
      }

      public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
      }
    }
  }

  /**
   * The durable inbound record and its retention.
   *
   * <p>The raw bodies are the largest store of buyer data in this module, so they are nulled the
   * moment an event can no longer run, and anything that never got there is purged at this ceiling
   * (I-07). A longer value is allowed and WARNs: it is a PII retention decision, not a tuning knob.
   */
  public static class Inbound {

    @NotNull private Duration retention = Duration.ofDays(30);

    @Min(1)
    @Max(100_000)
    private int purgeBatch = 500;

    public Duration getRetention() {
      return retention;
    }

    public void setRetention(Duration retention) {
      this.retention = retention;
    }

    public int getPurgeBatch() {
      return purgeBatch;
    }

    public void setPurgeBatch(int purgeBatch) {
      this.purgeBatch = purgeBatch;
    }
  }

  /**
   * The sweep that finds a sale with no document. Free core, and the only mitigation this module
   * has for the failure that is silent by construction (I-03).
   */
  public static class Reconcile {

    private boolean enabled = true;

    @NotNull private Duration interval = Duration.ofMinutes(15);

    @NotNull private Duration window = Duration.ofDays(2);

    /** Long enough that an invoice still in flight is not reported as missing. */
    @NotNull private Duration grace = Duration.ofMinutes(15);

    /**
     * How many of the newest documents to re-hash each sweep. This is what makes a store that
     * silently overwrote detectable in the free core; the exhaustive rescan is Pro.
     */
    @Min(0)
    @Max(10_000)
    private int driftSample = 10;

    @Min(1)
    @Max(10_000)
    private int pageSize = 500;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getInterval() {
      return interval;
    }

    public void setInterval(Duration interval) {
      this.interval = interval;
    }

    public Duration getWindow() {
      return window;
    }

    public void setWindow(Duration window) {
      this.window = window;
    }

    public Duration getGrace() {
      return grace;
    }

    public void setGrace(Duration grace) {
      this.grace = grace;
    }

    public int getDriftSample() {
      return driftSample;
    }

    public void setDriftSample(int driftSample) {
      this.driftSample = driftSample;
    }

    public int getPageSize() {
      return pageSize;
    }

    public void setPageSize(int pageSize) {
      this.pageSize = pageSize;
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

    /**
     * How long after a fiscal year ends a late invoice from that year may still be numbered.
     *
     * <p>Unset by default, and that is a deliberate accounting decision rather than a weak one: a
     * cut-off refuses a document the seller may be legally required to issue, so it is the seller's
     * accountant who sets it. When it is set, a late invoice past it is refused with {@code
     * DEI-207} and reported, rather than being numbered into a period already declared.
     */
    private Duration closedYearCutoff;

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

    public Duration getClosedYearCutoff() {
      return closedYearCutoff;
    }

    public void setClosedYearCutoff(Duration closedYearCutoff) {
      this.closedYearCutoff = closedYearCutoff;
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
