package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.MappingMetamodelImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Refuses to start if any JPA mapping in the host application reaches one of this module's tables.
 *
 * <p><b>Why this exists at all.</b> This module ships no entity, no repository and nothing
 * cacheable over its own tables, which removes the Hibernate paths rather than protecting them one
 * at a time - there is no {@code find}, no derived query, no JPQL, no Criteria, no projection, no
 * lazy attribute, no first- or second-level cache and no dirty checking over a table nothing maps.
 * That argument only holds while it is true, and the only actor who can make it untrue is the host.
 * This guard is what keeps it true, so <em>its</em> coverage is the whole control (N-03).
 *
 * <p>What it covers, each because leaving it out would let a mapping through:
 *
 * <ol>
 *   <li><b>Identifier normalisation.</b> Quoting, a schema qualifier and letter case are all
 *       cosmetic here and all defeat a raw string comparison. See {@link EInvoiceTables#isOurs}.
 *   <li><b>Non-primary tables.</b> {@code @SecondaryTable}, subclass tables of every inheritance
 *       strategy, {@code @JoinTable}, {@code @CollectionTable} and {@code @ElementCollection} all
 *       name a table that is not the entity's primary one; embeddable attributes land in one of
 *       those. The walk asks Hibernate for every table each persister touches.
 *   <li><b>Views and {@code @Subselect}.</b> A {@code @Subselect} entity's mapped "table" is its
 *       SQL text, so the text is searched rather than compared. A database <em>view</em> over our
 *       tables is invisible here by construction - the metamodel only knows the view's own name -
 *       and is refused by {@link DatabaseViewGuard}, which asks the database instead.
 *   <li><b>Every {@code EntityManagerFactory}, whenever it is built.</b> This is a {@link
 *       BeanPostProcessor}, not a loop over the context at startup: a lazily initialised factory, a
 *       child context's factory, or one built by an auto-configuration that runs after ours is
 *       scanned as it is created. The {@link SmartInitializingSingleton} half additionally forces
 *       any factory that is still uninstantiated when the context finishes, so a {@code @Lazy} one
 *       is forced or refused, never skipped.
 * </ol>
 *
 * <p>A second-level or query cache over our tables needs a mapping over our tables, and there is
 * none: the cache question is closed by the same refusal rather than by a second check that could
 * disagree with this one.
 */
public class PersistenceMappingGuard implements BeanPostProcessor, SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(PersistenceMappingGuard.class);

  private final ConfigurableListableBeanFactory beanFactory;

  public PersistenceMappingGuard(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof EntityManagerFactory factory) {
      scan(factory, beanName);
    }
    return bean;
  }

  @Override
  public void afterSingletonsInstantiated() {
    // Anything the post-processor did not see: a factory created before this bean existed, or one
    // that is still lazy. getBean forces it on purpose - an unscanned factory is not a factory that
    // cannot reach our tables, it is one nobody looked at.
    for (String name : beanFactory.getBeanNamesForType(EntityManagerFactory.class, true, false)) {
      scan(beanFactory.getBean(name, EntityManagerFactory.class), name);
    }
  }

  private void scan(EntityManagerFactory factory, String beanName) {
    SessionFactoryImplementor sessionFactory;
    try {
      sessionFactory = factory.unwrap(SessionFactoryImplementor.class);
    } catch (RuntimeException e) {
      // A factory this module cannot read is a factory this module cannot clear. Saying so is the
      // only honest outcome: unverifiable is not clean.
      throw new EInvoiceException(
          ErrorCodes.PERSISTENCE_MAPPING_REFUSED,
          "the EntityManagerFactory '"
              + beanName
              + "' is not a Hibernate SessionFactory, so this module cannot check whether any"
              + " entity in it maps one of its tables. It refuses to start rather than assume.",
          e);
    }
    Set<String> offending = new LinkedHashSet<>();
    MappingMetamodelImplementor metamodel = sessionFactory.getMappingMetamodel();
    metamodel.forEachEntityDescriptor(persister -> collectEntityTables(persister, offending));
    metamodel.forEachCollectionDescriptor(
        collection -> {
          // @JoinTable, @CollectionTable and @ElementCollection all arrive here.
          if (EInvoiceTables.isOurs(collection.getTableName())) {
            offending.add(collection.getRole() + " -> " + collection.getTableName());
          }
        });
    if (!offending.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.PERSISTENCE_MAPPING_REFUSED,
          "a JPA mapping in this application reaches a table this module owns: "
              + String.join(", ", offending)
              + ". These tables are an append-only legal ledger, written by this module's own"
              + " statements and protected by database triggers; an entity over them brings dirty"
              + " checking, caching and unscoped queries that the triggers can only refuse after"
              + " the fact. Read the ledger through this module's own API instead.");
    }
    log.debug(
        "einvoice: EntityManagerFactory '{}' maps none of this module's tables ({})",
        beanName,
        String.join(", ", EInvoiceTables.ALL));
  }

  private static void collectEntityTables(EntityPersister persister, Set<String> offending) {
    for (String table : persister.getTableNames()) {
      if (EInvoiceTables.isOurs(table)) {
        offending.add(persister.getEntityName() + " -> " + table);
      }
    }
    for (int i = 0; i < persister.getSubclassTableSpan(); i++) {
      String table = persister.getSubclassTableName(i);
      if (EInvoiceTables.isOurs(table)) {
        offending.add(persister.getEntityName() + " -> " + table);
      }
    }
  }
}
