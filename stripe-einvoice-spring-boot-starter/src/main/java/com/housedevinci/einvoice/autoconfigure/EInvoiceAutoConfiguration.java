package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/** Wires the numbering series, the issuance ledger and the guards that keep them ours. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(EInvoiceProperties.class)
@ConditionalOnBean(DataSource.class)
public class EInvoiceAutoConfiguration {

  /**
   * Injected everywhere a "now" is needed, so a test can move time across a fiscal-year boundary
   * without a restart and without sleeping. No production code in this module calls {@code
   * Instant.now()}, {@code LocalDate.now()} or {@code ZoneId.systemDefault()} directly; an ArchUnit
   * rule holds that line (D-15, checklist line 16).
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock einvoiceClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public SeriesDefinition einvoiceSeriesDefinition(EInvoiceProperties properties) {
    return new SeriesDefinition(
        properties.getNumbering().getPrefix(),
        properties.getNumbering().getWidth(),
        properties.getNumbering().isFiscalYearReset());
  }

  @Bean
  @ConditionalOnMissingBean
  public IssuanceChain einvoiceIssuanceChain(EInvoiceProperties properties) {
    EInvoiceProperties.Chain chain = properties.getChain();
    if (chain.isUnkeyed()) {
      if (!chain.getHmacSecret().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "einvoice.chain.unkeyed=true and einvoice.chain.hmac-secret is also set. A log is"
                + " keyed from row 1 or unkeyed forever, so this configuration cannot say which"
                + " one this application is. Remove one of the two.");
      }
      return IssuanceChain.unkeyed();
    }
    if (chain.getHmacSecret().isEmpty() || chain.getHmacKeyId().isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.chain.hmac-secret and einvoice.chain.hmac-key-id are required and have no"
              + " default: without them the issuance log can be recomputed by anyone who can write"
              + " to it. Supply them from the environment, or set einvoice.chain.unkeyed=true and"
              + " accept that the verifier will report INTACT_UNKEYED.");
    }
    return IssuanceChain.keyed(decodeSecret(chain.getHmacSecret()), chain.getHmacKeyId());
  }

  @Bean
  @ConditionalOnMissingBean
  public JdbcIssuanceStore einvoiceIssuanceStore(
      DataSource dataSource,
      IssuanceChain chain,
      SeriesDefinition definition,
      EInvoiceProperties properties,
      Clock clock) {
    Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series = new LinkedHashMap<>();
    series.put(
        new JdbcIssuanceStore.SeriesId(
            properties.getSeller().getId(),
            properties.getNumbering().getSeries(),
            Mode.of(properties.getMode())),
        definition);
    return new JdbcIssuanceStore(
        dataSource, chain, series, clock, properties.getNumbering().getAllocationTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  public IssuanceNumberingService einvoiceNumberingService(
      JdbcIssuanceStore store, EInvoiceProperties properties) {
    return new IssuanceNumberingService(store, store, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public IssuanceVoidService einvoiceVoidService(
      JdbcIssuanceStore store, EInvoiceProperties properties) {
    return new IssuanceVoidService(store, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public IssuanceChainVerifier einvoiceChainVerifier(
      JdbcIssuanceStore store, EInvoiceProperties properties) {
    Map<String, byte[]> keyring = new LinkedHashMap<>();
    EInvoiceProperties.Chain chain = properties.getChain();
    if (!chain.isUnkeyed()) {
      keyring.put(chain.getHmacKeyId(), decodeSecret(chain.getHmacSecret()));
      // Retired ids stay in the keyring: the key id is inside the hashed material from row 1, so a
      // rotation window has rows signed by different ids in one still-keyed log, and an id the
      // keyring does not hold is BROKEN rather than skipped.
      chain.getHmacKeys().forEach((id, secret) -> keyring.put(id, decodeSecret(secret)));
    }
    return new IssuanceChainVerifier(store, store, keyring);
  }

  @Bean
  @ConditionalOnMissingBean
  public EInvoiceStartupCheck einvoiceStartupCheck(
      EInvoiceProperties properties,
      DataSource dataSource,
      JdbcIssuanceStore store,
      SeriesDefinition definition,
      Clock clock,
      IssuanceChain chain) {
    return new EInvoiceStartupCheck(
        properties, dataSource, store, definition, clock, chain.isKeyed());
  }

  /**
   * The persistence-mapping guard, registered only when JPA is on the classpath. A host with no JPA
   * has no mapping to refuse; a host with JPA gets its factories scanned as they are built.
   */
  @Bean
  @ConditionalOnClass(name = "jakarta.persistence.EntityManagerFactory")
  @ConditionalOnMissingBean
  @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
  public static PersistenceMappingGuard einvoicePersistenceMappingGuard(
      ConfigurableListableBeanFactory beanFactory) {
    return new PersistenceMappingGuard(beanFactory);
  }

  /**
   * Base64, and only Base64.
   *
   * <p>Guessing the encoding is the bug here, not a convenience. A 32-character passphrase is also
   * valid Base64, and a decoder that tries Base64 first and falls back to raw bytes silently turns
   * it into 24 bytes of key material - a different key from the one the operator thinks they set,
   * and one that fails the 32-byte minimum for reasons the message would not explain. A value that
   * is not Base64 is refused by name, with the fix in the message.
   */
  private static byte[] decodeSecret(String value) {
    String trimmed = value.strip();
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(trimmed);
    } catch (IllegalArgumentException notBase64) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.chain.hmac-secret must be Base64. Generate one with:"
              + " head -c 32 /dev/urandom | base64");
    }
    if (decoded.length < IssuanceChain.MIN_KEY_BYTES) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.chain.hmac-secret decodes to "
              + decoded.length
              + " bytes; at least "
              + IssuanceChain.MIN_KEY_BYTES
              + " are required. A 32-character passphrase is valid Base64 and decodes to 24 bytes,"
              + " which is the usual cause of this message.");
    }
    return decoded;
  }
}
