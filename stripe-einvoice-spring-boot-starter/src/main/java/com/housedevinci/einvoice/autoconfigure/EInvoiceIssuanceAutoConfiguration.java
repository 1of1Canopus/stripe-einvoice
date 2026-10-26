package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.file.FilesystemArchiveStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcFindingStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundEventStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundRunLock;
import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcReprocessLedger;
import com.housedevinci.einvoice.adapter.jdbc.JdbcUnitOfWork;
import com.housedevinci.einvoice.adapter.validation.UblDocumentDates;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.InboundRunLock;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceReprocess;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.PreflightSupport;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.application.ReprocessLedger;
import com.housedevinci.einvoice.application.RunPermits;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * Wires the issuance unit of work: the intake, the worker, the sweeper, the archive and the signal.
 *
 * <p><b>Nothing here starts unless it can do the whole job.</b> The pipeline is conditional on a
 * {@link DocumentRenderer} and a {@link DocumentValidator} bean, because an application that can
 * allocate a number and cannot produce a validated document would consume a legal series and
 * archive nothing. The EN 16931 writers arrive with their own design; until then a host supplies
 * its own, or this half of the module simply does not start and says so once, clearly.
 */
@AutoConfiguration(after = EInvoiceAutoConfiguration.class)
@ConditionalOnBean(JdbcIssuanceStore.class)
public class EInvoiceIssuanceAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(EInvoiceIssuanceAutoConfiguration.class);

  /**
   * The durable record of every accepted Stripe event - and part of the intake path, so it is gated
   * by the same property (D14-02).
   *
   * <p>{@code einvoice.issuance.enabled=false} is documented, in the README and in this module's
   * own refusal message, as "run the numbering API only". Until D14-02 it turned off the sweeper
   * alone: a host that had the three intake beans and a webhook signing secret still mapped the
   * unauthenticated endpoint, still built the worker, and still wrote a row per event it would
   * never issue - answering Stripe 200, so nothing retried and nothing alerted. The property now
   * gates the thing it names: this store, and {@link IssuanceUnitOfWork} below, on which the
   * worker, the reconciliation sweep, the reprocess and the webhook controller are all already
   * conditional. Nothing else in this module reads an inbound event.
   */
  /**
   * D-PR42-1: the issuance row lock's session check, once, at startup, outside any transaction, so
   * a host transaction never waits for the two connections the check needs. {@code DEI-104} (a
   * pooler in transaction or statement mode) fails the startup. A database not reachable yet only
   * WARNs, and the check then runs before the first lock instead.
   */
  /** Kept apart so the starter loads without HikariCP on the classpath. */
  static final class HikariPoolSize {
    private HikariPoolSize() {}

    /** Connections one live run may hold at once: run lock, row lock, one short-lived. */
    static final int PER_RUN = 3;

    /** The one sweeper thread every module job runs on (T23 pins it). */
    static final int JOBS = 1;

    /** The host application's own share. */
    static final int HOST = 1;

    static void cannotCheck() {
      log.warn(
          "einvoice: the connection pool is not HikariCP, so the rule 3 x"
              + " einvoice.issuance.concurrency + 2 <= maximum pool size could not be checked at"
              + " startup. Check it by hand: a smaller pool stalls issuance with DEI-102.");
    }

    static void refuseBelowRunNeed(javax.sql.DataSource dataSource, int concurrency) {
      int max;
      try {
        if (!dataSource.isWrapperFor(com.zaxxer.hikari.HikariConfigMXBean.class)) {
          cannotCheck();
          return;
        }
        max = dataSource.unwrap(com.zaxxer.hikari.HikariConfigMXBean.class).getMaximumPoolSize();
      } catch (java.sql.SQLException e) {
        cannotCheck();
        return;
      }
      int need = PER_RUN * concurrency + JOBS + HOST;
      if (need > max) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "einvoice.issuance.concurrency is "
                + concurrency
                + " and spring.datasource.hikari.maximum-pool-size is "
                + max
                + ": issuance needs 3 x "
                + concurrency
                + " + 1 (module jobs) + 1 (host) = "
                + need
                + " connections. Lower einvoice.issuance.concurrency or raise the pool size.");
      }
    }

    static void refuseBelowTwo(javax.sql.DataSource dataSource) {
      int max;
      try {
        if (!dataSource.isWrapperFor(com.zaxxer.hikari.HikariConfigMXBean.class)) {
          return;
        }
        max = dataSource.unwrap(com.zaxxer.hikari.HikariConfigMXBean.class).getMaximumPoolSize();
      } catch (java.sql.SQLException e) {
        return;
      }
      if (max < 2) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "the connection pool has a maximum size of "
                + max
                + "; the issuance row lock needs at least 2 (its session check opens two"
                + " connections at once). Raise spring.datasource.hikari.maximum-pool-size to 2"
                + " or more.");
      }
    }
  }

  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton einvoiceSessionPinningCheck(
      ObjectProvider<JdbcIssuanceStore> store) {
    return () ->
        store.ifAvailable(
            s -> {
              // D-PR43-2: the check needs two free connections. A pool that can never give them
              // would hold its only connection for the pool's timeout on every issuance.
              if (org.springframework.util.ClassUtils.isPresent(
                  "com.zaxxer.hikari.HikariConfigMXBean", null)) {
                HikariPoolSize.refuseBelowTwo(s.dataSource());
              }
              try {
                s.verifySessionPinning();
              } catch (EInvoiceException e) {
                if (ErrorCodes.SESSION_NOT_PINNED.equals(e.code())) {
                  throw e;
                }
                log.warn(
                    "einvoice: the database session check could not run at startup ({}); it runs"
                        + " before the first issuance row lock instead",
                    e.code());
              }
            });
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "einvoice.issuance", name = "enabled", matchIfMissing = true)
  public InboundEventStore einvoiceInboundEventStore(JdbcUnitOfWork unitOfWork) {
    return new JdbcInboundEventStore(unitOfWork);
  }

  /**
   * At most one run per Stripe invoice (D-PR45-1). Not {@code @ConditionalOnMissingBean}: the lock
   * is a control, and a host that replaced it would run two renders of one number at once. Wired
   * even with the intake off: it reads and writes nothing on its own, and a host that builds its
   * own unit of work must still be given it.
   */
  @Bean
  public InboundRunLock einvoiceInboundRunLock(
      JdbcUnitOfWork unitOfWork, javax.sql.DataSource dataSource) {
    return new JdbcInboundRunLock(unitOfWork, dataSource);
  }

  /**
   * The one bound on live runs, shared by the worker and the reprocess (D-ORPE-4). Not replaceable,
   * for the same reason as the lock: the pool rule below counts on it.
   */
  @Bean
  public RunPermits einvoiceRunPermits(EInvoiceProperties properties) {
    return new RunPermits(properties.getIssuance().getConcurrency());
  }

  /**
   * The pool rule (D-ORPE-4, D-ORPE-7d), checked at startup: {@code 3 x concurrency + 1 + 1 <=
   * maximum pool size}. A live run counts three connections: its run lock for the whole run, the
   * issuance row lock during the archive write, and one short-lived connection for the allocator
   * and the reads. Every module job (sweep, reconciliation, purge, chain check) runs on the one
   * sweeper thread, so the jobs count one. The host counts one. Refused with {@code DEI-100}; a
   * host lowers {@code einvoice.issuance.concurrency} or raises the pool. A pool that does not
   * unwrap to HikariCP cannot be checked: a WARN at every startup says so.
   *
   * <p>No {@code @ConditionalOnBean} (D-PR49-2): that condition is evaluated when this definition
   * is registered, before the unit of work's own definition below, so the rule never existed in a
   * started context. The unit of work is looked up after every singleton exists instead, which also
   * covers one the host builds.
   */
  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton einvoiceRunPoolRule(
      javax.sql.DataSource dataSource,
      RunPermits permits,
      ObjectProvider<IssuanceUnitOfWork> unitOfWork) {
    return () -> {
      if (unitOfWork.stream().findAny().isPresent()) {
        refusePoolBelowRunNeed(dataSource, permits);
      }
    };
  }

  /** The rule's arithmetic, for {@code RunPoolRuleTest}. */
  static void refusePoolBelowRunNeed(javax.sql.DataSource dataSource, RunPermits permits) {
    if (!org.springframework.util.ClassUtils.isPresent(
        "com.zaxxer.hikari.HikariConfigMXBean", null)) {
      HikariPoolSize.cannotCheck();
      return;
    }
    HikariPoolSize.refuseBelowRunNeed(dataSource, permits.permits());
  }

  @Bean
  @ConditionalOnMissingBean
  public FindingStore einvoiceFindingStore(JdbcUnitOfWork unitOfWork) {
    return new JdbcFindingStore(unitOfWork);
  }

  /**
   * The archive, and the probe that decides whether this application may use it (I-06).
   *
   * <p>The probe runs here, at bean creation, with a real conditional write against a scratch key
   * that differs per startup - so a store which quietly overwrites is refused before it can be
   * handed a legal document, rather than discovered when two documents share a number.
   */
  @Bean
  @ConditionalOnMissingBean
  public ArchiveStore einvoiceArchiveStore(EInvoiceProperties properties) {
    EInvoiceProperties.Archive archive = properties.getArchive();
    ArchiveStore store;
    if ("filesystem".equalsIgnoreCase(archive.getType())) {
      if (archive.getRoot().isBlank()) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "einvoice.archive.root is required for einvoice.archive.type=filesystem: the legal"
                + " originals have to live somewhere the operator chose deliberately.");
      }
      store = new FilesystemArchiveStore(Path.of(archive.getRoot()));
    } else {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.archive.type must be 'filesystem', or the host application supplies its own"
              + " ArchiveStore bean. An S3-compatible store is available in the core artifact"
              + " (S3ArchiveStore) and needs the AWS SDK and a configured client, which this"
              + " module deliberately does not build for you.");
    }
    return store;
  }

  /**
   * Probes the store this application actually has, ours or the host's (I-06). Separate from the
   * bean that builds ours on purpose: a host-supplied store makes that factory back off, and a
   * probe that lived inside it would skip exactly the store nobody reviewed.
   */
  @Bean
  @ConditionalOnMissingBean
  public ArchiveStoreStartupProbe einvoiceArchiveStoreProbe(
      ArchiveStore archive, EInvoiceProperties properties) {
    return new ArchiveStoreStartupProbe(archive, properties.getArchive().isAllowNonAtomicStore());
  }

  /**
   * Unconditional on the renderer and the validator, deliberately: this is the check that tells the
   * difference between "not wired" and "wired wrong" (D2-02). It must run whether or not those
   * beans exist, so it cannot itself be conditional on them.
   */
  @Bean
  @ConditionalOnMissingBean
  public IssuanceIntakeWiringCheck einvoiceIssuanceIntakeWiringCheck(
      ConfigurableListableBeanFactory beanFactory,
      EInvoiceProperties properties,
      Environment environment) {
    return new IssuanceIntakeWiringCheck(beanFactory, properties, environment);
  }

  @Bean
  @ConditionalOnMissingBean
  public PreflightSupport einvoicePreflightSupport(FindingStore findings, Clock clock) {
    return new PreflightSupport(findings, clock);
  }

  /**
   * P-01 and checklist line 65. A renderer that does not override {@code preflight} is a standing
   * fact about the application, so it is found at startup rather than on the first invoice, and it
   * is recorded where an operator looks rather than only in a boot log.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DocumentRenderer.class)
  public PreflightSupportCheck einvoicePreflightSupportCheck(
      DocumentRenderer renderer, PreflightSupport support, EInvoiceProperties properties) {
    return new PreflightSupportCheck(renderer, support, properties);
  }

  /**
   * The intake pipeline itself, and with D14-02 the single place {@code
   * einvoice.issuance.enabled=false} takes effect: the worker, the reconciliation sweep, the
   * sweeper, the privileged reprocess and the webhook controller are each already conditional on
   * this bean, so turning it off turns off every path by which a Stripe event can reach this
   * application. What deliberately survives, and is asserted bean by bean in {@code
   * IssuanceWiringTest}: the numbering service, the archive and its probe, the findings store,
   * service and endpoint, the preflight support and its check, the reprocess ledger, the health
   * indicator (which reports "not configured" with no sweeper) and the Stripe reader, which reads
   * and writes nothing on its own.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({DocumentRenderer.class, DocumentValidator.class, StripeInvoiceSource.class})
  @ConditionalOnProperty(prefix = "einvoice.issuance", name = "enabled", matchIfMissing = true)
  public IssuanceUnitOfWork einvoiceIssuanceUnitOfWork(
      InboundEventStore inbound,
      InboundRunLock runLock,
      RunPermits permits,
      StripeInvoiceSource source,
      JdbcIssuanceStore store,
      DocumentRenderer renderer,
      DocumentValidator validator,
      ArchiveStore archive,
      PreflightSupport preflightSupport,
      Clock clock,
      EInvoiceProperties properties) {
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        renderer,
        validator,
        // D-PR36-6: built here, not a bean, and not @ConditionalOnMissingBean. A host that
        // replaces the renderer or the validator cannot replace the date check through Spring.
        new UblDocumentDates(),
        archive,
        preflightSupport,
        clock,
        configuration(properties, source));
  }

  static IssuanceUnitOfWork.Configuration configuration(
      EInvoiceProperties properties, StripeInvoiceSource source) {
    return new IssuanceUnitOfWork.Configuration(
        properties.getSeller().getId(),
        properties.getSeller().getStripeAccountId(),
        Mode.of(properties.getMode()),
        properties.getNumbering().getSeries(),
        properties.getNumbering().isFiscalYearReset(),
        ZoneId.of(properties.getSeller().getTaxZone()),
        source.pinnedApiVersion(),
        properties.getRulePackVersion(),
        properties.getIssuance().getRetryBackoff(),
        properties.getIssuance().getMaxRetryBackoff(),
        Optional.ofNullable(properties.getNumbering().getClosedYearCutoff()),
        properties.getArchive().isAllowNonAtomicStore());
  }

  /**
   * The privileged reprocess of one refused event (QUESTIONS 26). A bean the host calls from its
   * own admin action, behind its own authorization - <b>no HTTP endpoint and no actuator
   * operation</b>, for the same reason the void has none (N-04): a library cannot authenticate
   * anyone, and an auto-configured reprocess endpoint would hand an unauthenticated caller the
   * issuance pipeline. Nothing in this module calls it, and no schedule reaches it.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(IssuanceUnitOfWork.class)
  public IssuanceReprocess einvoiceIssuanceReprocess(
      IssuanceUnitOfWork unitOfWork,
      InboundEventStore inbound,
      JdbcIssuanceStore store,
      ReprocessLedger ledger,
      Clock clock) {
    return new IssuanceReprocess(unitOfWork, inbound, store, ledger, clock);
  }

  /**
   * The durable record of every privileged re-open (D9-03). Always wired, even where the intake is
   * not: the table is written only by the reprocess, and a host that has one without the other
   * would have the action with no record of it.
   */
  @Bean
  @ConditionalOnMissingBean
  public ReprocessLedger einvoiceReprocessLedger(JdbcUnitOfWork unitOfWork) {
    return new JdbcReprocessLedger(unitOfWork);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(IssuanceUnitOfWork.class)
  public IssuanceWorker einvoiceIssuanceWorker(
      IssuanceUnitOfWork unitOfWork, EInvoiceProperties properties) {
    return new IssuanceWorker(
        unitOfWork,
        properties.getIssuance().getConcurrency(),
        properties.getIssuance().getQueueCapacity());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(IssuanceUnitOfWork.class)
  public ReconciliationSweep einvoiceReconciliationSweep(
      IssuanceUnitOfWork unitOfWork,
      JdbcIssuanceStore store,
      InboundEventStore inbound,
      ArchiveStore archive,
      FindingStore findings,
      ReprocessLedger reprocessLedger,
      Clock clock,
      EInvoiceProperties properties) {
    // The unit of work's own source: a successful list call counts as an accepted key and re-arms
    // the events a refused key stopped (D-SRE-3).
    return new ReconciliationSweep(
        unitOfWork.stripeSource(),
        store,
        inbound,
        archive,
        findings,
        reprocessLedger,
        clock,
        new ReconciliationSweep.Settings(
            properties.getReconcile().getWindow(),
            properties.getReconcile().getGrace(),
            properties.getIssuance().getAlertAfter(),
            properties.getReconcile().getDriftSample(),
            properties.getReconcile().getPageSize()),
        unitOfWork.configuration());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(IssuanceUnitOfWork.class)
  @ConditionalOnProperty(prefix = "einvoice.issuance", name = "enabled", matchIfMissing = true)
  public IssuanceSweeper einvoiceIssuanceSweeper(
      IssuanceUnitOfWork unitOfWork,
      InboundEventStore inbound,
      IssuanceWorker worker,
      ReconciliationSweep reconciliation,
      IssuanceChainVerifier verifier,
      Clock clock,
      EInvoiceProperties properties) {
    return new IssuanceSweeper(
        unitOfWork, inbound, worker, reconciliation, verifier, clock, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public IssuanceFindingService einvoiceFindingService(
      FindingStore findings, EInvoiceProperties properties, Clock clock) {
    return new IssuanceFindingService(
        findings, properties.getSeller().getId(), Mode.of(properties.getMode()), clock);
  }

  /**
   * The unauthenticated edge. Registered only in a servlet web application, only when the secret
   * keyring is configured, and only when there is a worker to hand an event to.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
  @ConditionalOnBean(IssuanceWorker.class)
  @ConditionalOnProperty(prefix = "einvoice.webhook", name = "enabled", matchIfMissing = true)
  public StripeWebhookController einvoiceWebhookController(
      InboundEventStore inbound,
      IssuanceWorker worker,
      EInvoiceProperties properties,
      Clock clock) {
    EInvoiceProperties.Webhook webhook = properties.getWebhook();
    StripeSecrets.requireWebhookSecrets(properties.getStripe().getWebhookSecrets());
    StripeSecrets.requireBoundedTolerance(webhook.getTolerance());
    log.info(
        "einvoice: the Stripe webhook endpoint is mapped at {} with {} active signing key(s) and a"
            + " {} byte body cap. The host application must permit this path unauthenticated: the"
            + " HMAC over the exact bytes is the authentication.",
        webhook.getPath(),
        properties.getStripe().getWebhookSecrets().size(),
        webhook.getMaxBodyBytes());
    return new StripeWebhookController(
        inbound,
        worker,
        properties.getStripe().getWebhookSecrets(),
        webhook.getTolerance(),
        webhook.getMaxBodyBytes(),
        Mode.of(properties.getMode()),
        clock);
  }

  /**
   * The operational health contributor, in its own group and outside readiness and liveness.
   *
   * <p>Deliberately NOT conditional on the sweeper. The starter contributes a health group naming
   * this contributor, and Boot refuses to start when a group names a contributor that does not
   * exist - so on a numbering-only host, which has no sweeper, the group made the HOST's
   * application fail to start over a bean name the host never chose. Found by running the
   * documented quick start from a clean clone. The contributor reports "not configured" there; an
   * application that meant to issue never gets that far, because {@link IssuanceIntakeWiringCheck}
   * refuses at startup.
   */
  @Bean(name = "einvoiceIssuance")
  @ConditionalOnMissingBean(name = "einvoiceIssuance")
  @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
  public EInvoiceHealthIndicator einvoiceHealthIndicator(
      ObjectProvider<IssuanceSweeper> sweeper, EInvoiceProperties properties, Clock clock) {
    return new EInvoiceHealthIndicator(
        Optional.ofNullable(sweeper.getIfAvailable()), properties, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
  public IssuanceFindingsEndpoint einvoiceFindingsEndpoint(
      FindingStore findings, EInvoiceProperties properties) {
    return new IssuanceFindingsEndpoint(
        findings, properties.getSeller().getId(), Mode.of(properties.getMode()));
  }

  /**
   * The Stripe SDK client, when the SDK is on the classpath and an API key is configured.
   *
   * <p>{@link StripeApiKeyCondition} rather than {@code @ConditionalOnProperty}: a YAML file that
   * offers an environment variable with a fallback ({@code api-key: ${EINVOICE_STRIPE_KEY:}}) makes
   * the property present and blank, and "present" was enough to run this factory and throw at
   * startup in a host that only wanted the numbering API.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "com.stripe.StripeClient")
  @Conditional(StripeApiKeyCondition.class)
  public StripeInvoiceSource einvoiceStripeInvoiceSource(EInvoiceProperties properties) {
    return StripeClientFactory.create(properties);
  }

  /** How long a caller may wait on the archive before the write is an outage. Documented only. */
  static Duration archiveTimeoutHint() {
    return Duration.ofSeconds(30);
  }
}
