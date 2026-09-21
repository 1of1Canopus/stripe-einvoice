package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.file.FilesystemArchiveStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcFindingStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundEventStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcUnitOfWork;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.PreflightSupport;
import com.housedevinci.einvoice.application.ReconciliationSweep;
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

  @Bean
  @ConditionalOnMissingBean
  public InboundEventStore einvoiceInboundEventStore(JdbcUnitOfWork unitOfWork) {
    return new JdbcInboundEventStore(unitOfWork);
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

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({DocumentRenderer.class, DocumentValidator.class, StripeInvoiceSource.class})
  public IssuanceUnitOfWork einvoiceIssuanceUnitOfWork(
      InboundEventStore inbound,
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
        source,
        store,
        store,
        store,
        renderer,
        validator,
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
      StripeInvoiceSource source,
      JdbcIssuanceStore store,
      InboundEventStore inbound,
      ArchiveStore archive,
      FindingStore findings,
      Clock clock,
      EInvoiceProperties properties) {
    return new ReconciliationSweep(
        source,
        store,
        inbound,
        archive,
        findings,
        clock,
        new ReconciliationSweep.Settings(
            properties.getReconcile().getWindow(),
            properties.getReconcile().getGrace(),
            properties.getIssuance().getAlertAfter(),
            properties.getReconcile().getDriftSample(),
            properties.getReconcile().getPageSize()),
        configuration(properties, source));
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
