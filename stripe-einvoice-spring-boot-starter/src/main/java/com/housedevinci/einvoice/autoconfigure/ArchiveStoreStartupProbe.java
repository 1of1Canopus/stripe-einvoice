package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.file.FilesystemArchiveStore;
import com.housedevinci.einvoice.application.ArchiveCapabilityProbe;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.Mode;
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
  private final String sellerId;
  private final Mode mode;

  /**
   * @param sellerId the seller whose folder this application writes: refused at startup when it
   *     cannot be a folder ({@code .}), or when it has upper-case letters on a filesystem archive
   *     that ignores letter case ({@code DEI-285}, D-PR52-3)
   */
  public ArchiveStoreStartupProbe(
      ArchiveStore store, boolean allowNonAtomic, String sellerId, Mode mode) {
    this.store = store;
    this.allowNonAtomic = allowNonAtomic;
    this.sellerId = sellerId;
    this.mode = mode;
  }

  @Override
  public void afterPropertiesSet() {
    ArchiveCapabilityProbe.probe(store, UUID.randomUUID().toString(), allowNonAtomic);
    // Fails fast on a seller id that cannot name its own folder (C4), before the first pass.
    ArchiveKey.sellerFolder(mode, sellerId);
    if (store instanceof FilesystemArchiveStore filesystem) {
      // C3: on a volume that ignores case, "Acme" and "acme" would share one folder. No opt-out.
      filesystem.requireSellerIdFitsVolume(sellerId);
    }
    log.info("einvoice: archiving to {}", store.describe());
  }
}
