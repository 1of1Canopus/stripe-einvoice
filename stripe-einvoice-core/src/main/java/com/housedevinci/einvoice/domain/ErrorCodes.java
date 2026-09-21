package com.housedevinci.einvoice.domain;

/**
 * Every stable error code this module can raise. A code outlives a message: it is what a host
 * application branches on, what an operator greps for, and what the docs index.
 *
 * <p>{@code DEI-1xx} is the numbering series and its dispositions (this module's first pull
 * request). {@code DEI-2xx} is the issuance unit of work, {@code DEI-3xx} the semantic model, the
 * mapper, the UBL writers and the validators.
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

  /**
   * An allocation gave up waiting for the series row's lock (SQLState {@code 55P03}), distinct from
   * a general store outage: the database answered, another allocation is simply ahead of this one
   * (D1-05).
   */
  public static final String ALLOCATION_TIMEOUT = "DEI-117";

  /**
   * A caller handed the store a connection in auto-commit mode as if it owned a transaction
   * (D1-04). Refused before any statement runs: each statement would commit on its own, so the
   * caller's rollback would keep the number.
   */
  public static final String HOST_AUTOCOMMIT = "DEI-118";

  /**
   * An allocation was called inside a read-only transaction. A caller's annotation, not an outage,
   * and it is refused before the series row lock is taken rather than surfacing as SQLState 25006.
   */
  public static final String HOST_TRANSACTION_READ_ONLY = "DEI-119";

  /**
   * A transaction that already holds the issuance chain's advisory lock asked for the series
   * counter's row lock. Nothing in this module takes the two in that order; two transactions doing
   * it in opposite orders deadlock (T-02).
   */
  public static final String LOCK_ORDER_VIOLATION = "DEI-120";

  /**
   * A caller-owned transaction that already failed inside the store after it had written. Nothing
   * further runs on it; it must be rolled back.
   */
  public static final String UNIT_OF_WORK_POISONED = "DEI-121";

  // -------------------------------------------------------------------------------------------
  // DEI-2xx - the issuance unit of work: intake, routing, fetch, mapping, archive, validation.
  // -------------------------------------------------------------------------------------------

  /** A body arrived that is not provably from Stripe, or whose identity fields do not parse. */
  public static final String INBOUND_UNREADABLE = "DEI-200";

  /** The event's {@code api_version} is not the pinned one this module was written against. */
  public static final String API_VERSION_SKEW = "DEI-201";

  /** A test-mode event reached a live-mode application, or the reverse (D-01). */
  public static final String MODE_MISMATCH = "DEI-202";

  /** The event's {@code account} resolves to no configured seller. Never a default seller. */
  public static final String UNKNOWN_ACCOUNT = "DEI-203";

  /** A collection was still reporting more pages after the last one was read (D-02). */
  public static final String TRUNCATED_COLLECTION = "DEI-204";

  /** An event type this module does not subscribe to. Recorded, then dropped. */
  public static final String UNSUBSCRIBED_EVENT_TYPE = "DEI-205";

  /** No active webhook secret verified the signature over the exact received bytes. */
  public static final String SIGNATURE_INVALID = "DEI-206";

  /** The invoice's fiscal year closed longer ago than {@code closed-year-cutoff} allows. */
  public static final String CLOSED_FISCAL_YEAR = "DEI-207";

  /** The request body is larger than {@code einvoice.webhook.max-body-bytes}. */
  public static final String BODY_TOO_LARGE = "DEI-208";

  /** The request's content type is not JSON. */
  public static final String UNSUPPORTED_CONTENT_TYPE = "DEI-209";

  /** A transition outside the set the inbound state machine declares. */
  public static final String INBOUND_ILLEGAL_TRANSITION = "DEI-210";

  /** Stripe was unreachable, rate-limited or answered 5xx. An outage, never a verdict. */
  public static final String STRIPE_UNAVAILABLE = "DEI-211";

  /** The re-fetched invoice is not finalised yet: parked, retried, never invented. */
  public static final String INVOICE_NOT_FINALISED = "DEI-212";

  /** The re-fetched invoice is void and this module never issued a document for it. */
  public static final String UPSTREAM_VOID_NOT_ISSUED = "DEI-213";

  /** Our recomputation of a total or of a tax bucket disagrees with Stripe's own (D-12). */
  public static final String TOTALS_MISMATCH = "DEI-220";

  /** A field EN 16931 requires is absent after the authoritative re-fetch. */
  public static final String MAPPING_INCOMPLETE = "DEI-221";

  /** An amount or a percentage is outside the magnitude or scale this module will carry. */
  public static final String AMOUNT_OUT_OF_BOUNDS = "DEI-222";

  /** The archived bytes no longer hash to the {@code document_sha256} recorded with them. */
  public static final String ARCHIVED_DOCUMENT_TAMPERED = "DEI-230";

  /** An invoice was voided in Stripe after this module issued its document. */
  public static final String VOID_AFTER_ISSUE_NEEDS_CREDIT_NOTE = "DEI-231";

  /** The archive key exists with different bytes. An alert, never an overwrite. */
  public static final String ARCHIVE_CONTENT_CONFLICT = "DEI-240";

  /** The archive store is unreachable. Retryable until the retry ceiling. */
  public static final String ARCHIVE_UNAVAILABLE = "DEI-241";

  /** The archive store cannot create an object only if it is absent, and is refused (I-06). */
  public static final String ARCHIVE_NOT_ATOMIC = "DEI-242";

  /**
   * A host-supplied {@code ArchiveStore} threw something other than this module's own exception
   * type (D2-04, checklist line 47). The cause is logged server-side only; this code is what the
   * row and any caller ever see. Terminal, not retryable: a host port's own bug does not improve by
   * running again.
   */
  public static final String ARCHIVE_FAILED = "DEI-243";

  /** The exact bytes did not pass validation. The failing rule id is recorded. */
  public static final String VALIDATION_REFUSED = "DEI-250";

  /** A validation could not run. Never PASSED by construction (checklist lines 18 and 57). */
  public static final String VALIDATION_NOT_EVALUATED = "DEI-251";

  /**
   * A host-supplied {@code DocumentValidator} threw something other than this module's own
   * exception type (D2-04, checklist line 47). The cause is logged server-side only. Terminal, not
   * retryable, for the same reason {@link #ARCHIVE_FAILED} is.
   */
  public static final String VALIDATOR_FAILED = "DEI-252";

  /** The issuance worker is at capacity. The event stays RECEIVED and the sweeper takes it. */
  public static final String WORKER_SATURATED = "DEI-260";

  /**
   * A document renderer failed on input that mapping had already accepted - including an unexpected
   * {@code RuntimeException} from a host-supplied renderer, not only this module's own exception
   * type (D2-04, checklist line 47). The cause is logged server-side only. Terminal, not retryable.
   */
  public static final String RENDER_FAILED = "DEI-261";

  /**
   * A renderer's pre-allocation preflight threw instead of answering. Fail closed: the same bug
   * would throw in the render a moment later, where it costs a legal number. Terminal, not
   * retryable, cause logged server-side only.
   */
  public static final String PREFLIGHT_FAILED = "DEI-262";

  /**
   * The wired renderer implements no preflight, so every invoice it maps is numbered before its
   * fields are screened - the cost this module removed. Not a failure of any one document: a
   * standing fact about the application, raised once per start as a compliance finding so that a
   * month of documents produced under the weaker path leaves more than a line in a boot log.
   */
  public static final String PREFLIGHT_NOT_SUPPORTED = "DEI-263";

  /** A finalised Stripe invoice has no issuance row at all. The finding D-09 exists for. */
  public static final String RECON_MISSING_ISSUANCE = "DEI-270";

  /** An {@code ISSUED} row names an archive object that is not there. */
  public static final String RECON_ARCHIVE_MISSING = "DEI-271";

  /** An archived document no longer hashes to what its row recorded. */
  public static final String RECON_ARCHIVE_DRIFT = "DEI-275";

  /** An archive object that no {@code ISSUED} row predicted. Alerted, never deleted. */
  public static final String RECON_ORPHAN_OBJECT = "DEI-272";

  /** A number allocated and open past {@code einvoice.issuance.alert-after}. */
  public static final String RECON_STUCK_ISSUANCE = "DEI-273";

  /** Reconciliation has not completed within two intervals. No result is never "healthy". */
  public static final String RECON_STALE = "DEI-274";

  // -------------------------------------------------------------------------------------------
  // DEI-3xx - the EN 16931 semantic model, the mapper, the UBL writers and the validators.
  // -------------------------------------------------------------------------------------------

  /** No exponent is recorded for this currency, and there is no default of 2 (D-05). */
  public static final String UNSUPPORTED_CURRENCY = "DEI-300";

  /**
   * A shape the upstream can produce and this edition does not map. Refused, never approximated.
   */
  public static final String MAPPING_UNSUPPORTED = "DEI-301";

  /**
   * No EN 16931 tax category can be established for a rate from the configured rule pack (D-14).
   */
  public static final String TAX_CATEGORY_UNKNOWN = "DEI-302";

  /** A zero-rated, exempt or reverse-charge category with no VATEX reason code (D-14). */
  public static final String EXEMPTION_REASON_REQUIRED = "DEI-303";

  /** The configured seller profile is missing a field the target profile requires. */
  public static final String SELLER_PROFILE_INCOMPLETE = "DEI-304";

  /** A document does not balance: EN 16931's BR-CO-* sums disagree with each other. */
  public static final String DOCUMENT_UNBALANCED = "DEI-305";

  /** A document type code and a sign convention that EN 16931 does not pair (D-13). */
  public static final String DOCUMENT_TYPE_UNSUPPORTED = "DEI-306";

  /** An identifier (VAT id, Leitweg-ID, electronic address) is not in its declared format. */
  public static final String IDENTIFIER_MALFORMED = "DEI-307";

  /** A vendored schema or stylesheet is absent, unreadable, or no longer hashes to its record. */
  public static final String ARTEFACT_TAMPERED = "DEI-320";

  /** No XSLT 2.0 processor is on the classpath, so the schematron could not run at all. */
  public static final String XSLT_PROCESSOR_MISSING = "DEI-321";

  /** XML this module was asked to read carries a DOCTYPE, an entity, or will not parse (D-07). */
  public static final String XML_REFUSED = "DEI-322";

  /** A validation run passed its wall-clock bound. Never a pass; the report says NOT_EVALUATED. */
  public static final String VALIDATION_TIMEOUT = "DEI-323";

  /** A validation run produced more output than the bound allows. Same disposition. */
  public static final String VALIDATION_OUTPUT_TOO_LARGE = "DEI-324";

  private ErrorCodes() {}
}
