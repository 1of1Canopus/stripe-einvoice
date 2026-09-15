package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Every advisory lock this module takes, in one place, with its class id.
 *
 * <p><b>Why a registry rather than a constant beside each caller (N-05).</b> PostgreSQL advisory
 * lock keys are <em>database-wide</em>: not schema-wide, not table-wide. Both sibling modules take
 * a flat one-argument {@code pg_advisory_xact_lock(bigint)} with a hard-coded module constant, and
 * a host that installs two of these modules against one database is the intended deployment - they
 * are sold as a line. Copying a sibling's constant would serialise every invoice issuance against
 * every erasure, for no reason, visible only under load and looking like a database problem.
 * "Reused verbatim" means the algorithm and the canonical form, never the namespace.
 *
 * <p>This module therefore uses the <b>two-argument</b> {@code pg_advisory_xact_lock(int4, int4)}
 * form, which is a genuinely separate namespace from the one-argument form, with its own class id
 * per purpose:
 *
 * <table>
 *   <caption>Advisory lock constants across the module line</caption>
 *   <tr><th>Module</th><th>Purpose</th><th>Form</th><th>Value</th></tr>
 *   <tr><td>agent-guard</td><td>audit chain append</td><td>1-arg</td><td>18374244850549833</td></tr>
 *   <tr><td>gdpr-shredding</td><td>schema init</td><td>1-arg</td><td>6072873668427846209</td></tr>
 *   <tr><td>gdpr-shredding</td><td>erasure chain append</td><td>1-arg</td><td>0x5348455241</td></tr>
 *   <tr><td>gdpr-shredding</td><td>(tenant, subject)</td><td>2-arg</td><td>class 0x5355424A</td></tr>
 *   <tr><td>stripe-einvoice</td><td>schema init</td><td>2-arg</td><td>class 0x45494E49</td></tr>
 *   <tr><td>stripe-einvoice</td><td>issuance chain append</td><td>2-arg</td><td>class 0x45494E43</td></tr>
 * </table>
 *
 * <p>Both of this module's locks are <b>transaction-scoped</b>. A lock released before commit would
 * be no lock at all for a chain append, because the row it protects is not visible to anyone else
 * until the commit it was meant to order.
 */
public final class AdvisoryLocks {

  /** Schema initialisation, so two instances starting together do not race the DDL. "EINI". */
  public static final int SCHEMA_CLASS_ID = 0x45494E49;

  /** The issuance chain append, so prev_hash is a real predecessor. "EINC". */
  public static final int CHAIN_CLASS_ID = 0x45494E43;

  /** The object each class id is keyed on inside its namespace. */
  public static final String SCHEMA_KEY = "einvoice_schema";

  public static final String CHAIN_KEY = "einvoice_issuance";

  /**
   * Every flat, one-argument constant taken by a module of this line, recorded so the distinctness
   * test has something to compare against rather than a promise. A two-argument class id cannot
   * collide with any of these by construction - the two forms are separate namespaces - and this
   * list exists so that stays a checked fact rather than a comment.
   */
  public static final long[] SIBLING_ONE_ARG_CONSTANTS = {
    18374244850549833L, 6072873668427846209L, 0x5348455241L
  };

  /** Two-argument class ids taken by a sibling module of this line. */
  public static final int[] SIBLING_TWO_ARG_CLASS_IDS = {0x5355424A};

  private AdvisoryLocks() {}

  /**
   * Takes a transaction-scoped advisory lock in the caller's current transaction.
   *
   * <p><b>No transaction holds both of this module's locks (N-07).</b> The series counter's row
   * lock is taken by the allocation transaction; the chain lock is taken by the disposition
   * transaction; nothing acquires both. That is a stronger position than a correct lock ordering,
   * and it is asserted by a test rather than left true by accident. If a future path must hold
   * both, the order is the series row first and the chain second.
   */
  public static void lock(Connection connection, int classId, String key) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
      ps.setInt(1, classId);
      // Length-prefixed, the same canonical form the chain uses, so two keys cannot hash alike by
      // sharing a boundary. A collision here is benign (two unrelated purposes serialise against
      // each other) but there is no reason to leave the question open for one line of code.
      ps.setString(2, lengthPrefixed(key));
      ps.execute();
    }
  }

  static String lengthPrefixed(String value) {
    if (value == null || value.isEmpty()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an advisory lock key must not be empty");
    }
    return "|" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }
}
