package com.housedevinci.einvoice.domain;

/**
 * Every stable error code this module can raise. A code outlives a message: it is what a host
 * application branches on, what an operator greps for, and what the docs index.
 *
 * <p>{@code DEI-1xx} is the numbering series and its dispositions (this module's first pull
 * request). {@code DEI-2xx} is the issuance unit of work, {@code DEI-3xx} the mapper and the
 * writers; they arrive with their own designs and are deliberately absent here rather than declared
 * empty.
 */
public final class ErrorCodes {

  /** A configuration value is missing, malformed, or outside its declared bound. */
  public static final String CONFIG = "DEI-100";

  /** A value handed to a domain type is not one this module will put on a legal document. */
  public static final String INVALID = "DEI-101";

  /** The database is unreachable. An outage, never a business outcome. */
  public static final String STORE_UNAVAILABLE = "DEI-102";

  /** The server behind the DataSource is not PostgreSQL. */
  public static final String UNSUPPORTED_DATABASE = "DEI-103";

  /** No series row and no configured definition for this (seller, series, mode). */
  public static final String SERIES_NOT_CONFIGURED = "DEI-110";

  /** Another transaction claimed this Stripe invoice first; re-read and use its number. */
  public static final String ISSUANCE_ALREADY_CLAIMED = "DEI-111";

  /** The counter reached the last number the configured width can render. */
  public static final String SERIES_EXHAUSTED = "DEI-112";

  /** A state transition outside the set the enum and the database trigger both declare. */
  public static final String ILLEGAL_TRANSITION = "DEI-113";

  /** The issuance this operation names does not exist. */
  public static final String ISSUANCE_NOT_FOUND = "DEI-114";

  /** A host entity, view or cache mapping reaches one of this module's tables. */
  public static final String PERSISTENCE_MAPPING_REFUSED = "DEI-115";

  /** The issuance chain did not verify. */
  public static final String CHAIN_BROKEN = "DEI-116";

  private ErrorCodes() {}
}
