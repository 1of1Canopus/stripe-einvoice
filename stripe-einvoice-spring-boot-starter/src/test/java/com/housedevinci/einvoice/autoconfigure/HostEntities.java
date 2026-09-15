package com.housedevinci.einvoice.autoconfigure;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Subselect;

/**
 * The mappings a host application could write over this module's tables, one per way the JPA
 * mapping model offers to name a table. Each one is a test case for the persistence-mapping guard;
 * together they are the reason the "no entity of ours" argument holds.
 */
final class HostEntities {

  private HostEntities() {}

  /** The obvious one: an entity whose primary table is ours. */
  @Entity(name = "HostOverIssuance")
  @Table(name = "einvoice_issuance")
  static class OverPrimaryTable {
    @Id Long id;
  }

  /** Unquoted and upper-case: PostgreSQL folds it, a raw string comparison does not. */
  @Entity(name = "HostOverIssuanceUpperCase")
  @Table(name = "EINVOICE_ISSUANCE")
  static class OverUpperCaseTable {
    @Id Long id;
  }

  /** Schema-qualified: the same table, a longer string. */
  @Entity(name = "HostOverIssuanceQualified")
  @Table(name = "einvoice_issuance", schema = "public")
  static class OverQualifiedTable {
    @Id Long id;
  }

  /** Not the entity's primary table, and therefore missed by a scan that only reads that. */
  @Entity(name = "HostWithSecondaryTable")
  @Table(name = "host_thing")
  @SecondaryTable(name = "einvoice_series")
  static class OverSecondaryTable {
    @Id Long id;
  }

  /** An @ElementCollection's collection table: a third place a table name can hide. */
  @Entity(name = "HostWithCollectionTable")
  @Table(name = "host_bag")
  static class OverCollectionTable {
    @Id Long id;

    @ElementCollection
    @CollectionTable(name = "einvoice_issuance_event", joinColumns = @JoinColumn(name = "counter"))
    @Column(name = "legal_number")
    List<String> numbers = new ArrayList<>();
  }

  /** A read-only view of our rows expressed in the mapping itself. */
  @Entity(name = "HostSubselect")
  @Subselect("select id as id from einvoice_issuance")
  static class OverSubselect {
    @Id Long id;
  }

  /** A host entity that has nothing to do with us: the application must start. */
  @Entity(name = "HostCleanEntity")
  @Table(name = "host_clean")
  static class Clean {
    @Id Long id;
  }
}
