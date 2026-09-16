package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.ArchiveCapabilityProbe;
import com.housedevinci.einvoice.application.ArchiveStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Probes whichever archive store this application ended up with (I-06).
 *
 * <p>A bean of its own, rather than a few lines inside the factory method that builds our store,
 * and the difference matters: a host application that supplies its own {@code ArchiveStore} bean
 * makes our factory back off, so a probe living there would silently not run for exactly the store
 * nobody has reviewed. The probe belongs to the store the application will actually write legal
 * documents to.
 *
 * <p>It writes two different byte strings to a scratch key that differs per startup and expects the
 * second to be refused. A store that accepts it overwrites, which makes "one number, one document"
 * a hope rather than a property, so the application does not start unless the operator has set the
 * WARNed weaker mode.
 */
public class ArchiveStoreStartupProbe implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(ArchiveStoreStartupProbe.class);

  private final ArchiveStore store;
  private final boolean allowNonAtomic;

  public ArchiveStoreStartupProbe(ArchiveStore store, boolean allowNonAtomic) {
    this.store = store;
    this.allowNonAtomic = allowNonAtomic;
  }

  @Override
  public void afterPropertiesSet() {
    ArchiveCapabilityProbe.probe(store, UUID.randomUUID().toString(), allowNonAtomic);
    log.info("einvoice: archiving to {}", store.describe());
  }
}
