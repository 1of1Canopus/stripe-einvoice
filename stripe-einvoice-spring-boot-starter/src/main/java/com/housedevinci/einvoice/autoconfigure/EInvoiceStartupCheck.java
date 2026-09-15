package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcSupport;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Everything this module wants to be true before it serves a single invoice, checked once, loudly,
 * at every startup.
 *
 * <p>Every weaker mode is announced here at WARN on <em>every</em> boot, not once and not on first
 * use: an operator who reads one boot log a month has to be able to see, in that log, that this
 * application is in test mode or that its issuance log is unkeyed.
 */
public class EInvoiceStartupCheck implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(EInvoiceStartupCheck.class);

  private final EInvoiceProperties properties;
  private final DataSource dataSource;
  private final JdbcIssuanceStore store;
  private final SeriesDefinition definition;
  private final Clock clock;
  private final boolean chainKeyed;

  public EInvoiceStartupCheck(
      EInvoiceProperties properties,
      DataSource dataSource,
      JdbcIssuanceStore store,
      SeriesDefinition definition,
      Clock clock,
      boolean chainKeyed) {
    this.properties = properties;
    this.dataSource = dataSource;
    this.store = store;
    this.definition = definition;
    this.clock = clock;
    this.chainKeyed = chainKeyed;
  }

  @Override
  public void afterPropertiesSet() {
    JdbcSupport.requirePostgreSql(dataSource);
    if (properties.isInitializeSchema()) {
      JdbcSupport.initializeSchema(dataSource);
    }
    DatabaseViewGuard.refuseViewsOverOurTables(dataSource);
    // The tax zone is required and has no default, so an unknown id must fail here rather than on
    // the first invoice that crosses midnight.
    ZoneId zone = ZoneId.of(properties.getSeller().getTaxZone());
    seedSeries(zone);
    warnAboutWeakerModes();
    probeMaximumWidthNumber();
  }

  /**
   * Opens this year's series row if it does not exist. The allocator opens a new year's row itself,
   * inside the allocation transaction (N-01), so this is a convenience rather than the mechanism:
   * an application that was last restarted in November must not stop invoicing on 1 January.
   */
  private void seedSeries(ZoneId zone) {
    int fiscalYear =
        properties.getNumbering().isFiscalYearReset()
            ? clock.instant().atZone(zone).getYear()
            : SeriesKey.CONTINUOUS;
    store.openSeries(
        new SeriesKey(
            properties.getSeller().getId(),
            properties.getNumbering().getSeries(),
            fiscalYear,
            Mode.of(properties.getMode())));
  }

  private void warnAboutWeakerModes() {
    if (Mode.of(properties.getMode()) == Mode.TEST) {
      log.warn(
          "einvoice: einvoice.mode=test. Every number allocated in this application comes from the"
              + " TEST series and every document it produces is a test document. Nothing here is a"
              + " legal invoice. Set einvoice.mode=live in production.");
    }
    if (!chainKeyed) {
      log.warn(
          "einvoice: einvoice.chain.unkeyed=true. The issuance log's integrity then rests only on"
              + " database privilege separation: anyone who can write a row can recompute every"
              + " hash after it. The verifier will report INTACT_UNKEYED, never INTACT.");
    }
    if (!properties.getNumbering().isFiscalYearReset()) {
      log.warn(
          "einvoice: einvoice.numbering.fiscal-year-reset=false. The series runs continuously"
              + " across fiscal years instead of restarting at 1 each year. That is legal on some"
              + " readings and wrong on others; confirm it with the seller's accountant.");
    }
    for (String table : EInvoiceTables.ALL) {
      if (JdbcSupport.runtimeRoleOwnsTable(dataSource, table)) {
        log.warn(
            "einvoice: the database role running this application owns {}. That role can ALTER"
                + " TABLE ... DISABLE TRIGGER and remove the append-only protection. Run with a"
                + " role that has only the grants in docs/schema-grants.sql.",
            table);
      }
    }
  }

  /**
   * Validates the widest number this series can ever render, before any number is allocated.
   *
   * <p>A profile rule on BT-1 - a length bound, a pattern - that this series would eventually
   * violate must be discovered here, on a probe, and not three years in on a real invoice whose
   * number is already consumed. It is the residue of the ruling that byte determinism beats
   * gap-freeness: the cheapest way to keep that residue small is to refuse a series that cannot
   * render its own last number.
   */
  private void probeMaximumWidthNumber() {
    LegalNumber probe = LegalNumber.probeOfMaximumWidth(definition);
    log.info(
        "einvoice: numbering series {} renders {} .. {} (mode={})",
        properties.getNumbering().getSeries(),
        LegalNumber.render(definition, 1).value(),
        probe.value(),
        properties.getMode());
  }
}
