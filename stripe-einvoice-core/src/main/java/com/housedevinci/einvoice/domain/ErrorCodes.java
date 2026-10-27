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

  /**
   * The database connections do not keep one server session: a pooler in transaction or statement
   * mode (PgBouncer, for example) sits between this module and PostgreSQL. The issuance row lock is
   * a session lock, and under such a pooler it would silently be no lock at all (D-RTR-7, R5), so
   * the module refuses to issue rather than run unlocked. Use session pooling or a direct
   * connection. There is no weaker mode.
   *
   * <p>Also the code of a run that <b>lost</b> its inbound run lock (D-ORPE-2): the server or the
   * network ended the holder's session mid-run. The run writes nothing more, is logged at WARN with
   * the event id, and its connection is closed. Never {@link #INBOUND_RUN_IN_PROGRESS}, which means
   * the lock was never taken.
   */
  public static final String SESSION_NOT_PINNED = "DEI-104";

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

  /**
   * The only number this module allocated for that Stripe invoice has been voided, so the invoice
   * is terminal here: no later event allocates a second number and no path re-enters the pipeline
   * for it (RC-01).
   *
   * <p>A void is irreversible and unrecoverable inside this module. The remedy for a sale that must
   * still be documented is upstream - void the Stripe invoice and raise a new one, which takes a
   * new number through the ordinary path - and for a sale that is already paid that remedy needs a
   * credit note, which this edition does not produce.
   */
  public static final String NUMBER_VOIDED = "DEI-122";

  /**
   * The allocator was asked for a number of a document type nothing in this application can
   * produce: through the public allocator, any type other than an invoice (S-02, D-PS1-06).
   *
   * <p>Raised before the series row is locked, so no number is consumed. A credit-note number that
   * no document will ever carry is a hole in a legal series, made by this module itself.
   */
  public static final String DOCUMENT_TYPE_NOT_ISSUABLE = "DEI-123";

  /**
   * The issue date (BT-2) is earlier than the latest issue date already numbered in the series.
   *
   * <p>A credit-note refusal, raised under the series row lock before the counter moves (T9). An
   * invoice never reaches it: its issue date is the numbering day, so a later number cannot carry
   * an earlier date (D-PS1-27). A clock that runs backwards is {@link #ISSUE_CLOCK_BEHIND_SERIES}.
   */
  public static final String ISSUE_DATE_OUT_OF_ORDER = "DEI-127";

  /**
   * A credit note names no invoice, or names one it may not correct: not an invoice, not issued,
   * another seller or mode, or a stored document hash that no longer matches its chained issue
   * event (D-CNS-S12). Refused before a number is consumed.
   */
  public static final String CREDIT_TARGET_INVALID = "DEI-128";

  /**
   * A series' latest issue date is more than one day after today on the database clock, in the tax
   * zone (D-PS1-42). A hand edit or a forward jump of the clock put it there, and every later
   * invoice would be refused until that day. Not retryable: find who moved the row in the chain,
   * then correct {@code last_issue_date} as the schema owner with a recorded reason.
   */
  public static final String SERIES_DATE_IN_FUTURE = "DEI-130";

  /**
   * The fiscal year changed between the clock read that chose the series and the read under its
   * lock (D-PS1-41). Nothing was numbered. Retry in a new transaction, which picks the new year's
   * series; the worker does that on its own, a host that allocates in its own transaction must.
   */
  public static final String ISSUE_YEAR_CHANGED = "DEI-131";

  /**
   * The clock the issue date comes from is behind the series, or the database and application
   * clocks disagree by more than {@code einvoice.numbering.max-clock-regression} (design 5.2,
   * D-PS1-46). Nothing was numbered. Retryable while the gap is small; terminal beyond one day,
   * where the remedy is to fix the clock and reprocess the event.
   */
  public static final String ISSUE_CLOCK_BEHIND_SERIES = "DEI-132";

  /**
   * An invoice was numbered more than one day after its supply date. Not a refusal: the number and
   * the true issue date are given. A finding names the number so the delay is seen (design 5.6).
   */
  public static final String ISSUED_LATE = "DEI-137";

  /**
   * The schema version row could not be read for a reason other than "it does not exist" (S-05).
   * Usually a missing grant: the runtime role needs {@code SELECT} on {@code einvoice_schema}.
   * Unreadable is not absent, so startup stops instead of attempting a migration.
   */
  public static final String SCHEMA_VERSION_UNREADABLE = "DEI-133";

  /**
   * The schema upgrade refused to run because existing data breaks a rule the new format enforces,
   * for example two numbered rows of one seller and mode sharing a legal number (S-06). The schema
   * is left unchanged. This is a data condition, not an outage: retrying changes nothing.
   */
  public static final String SCHEMA_UPGRADE_REFUSED = "DEI-134";

  /** The database carries a schema format newer than this version of the module understands. */
  public static final String SCHEMA_VERSION_UNSUPPORTED = "DEI-135";

  /**
   * The schema is older than this version of the module and {@code einvoice.initialize-schema} is
   * false, so nothing will upgrade it. Run the bundled schema script as the schema owner first.
   */
  public static final String SCHEMA_NOT_PREPARED = "DEI-136";

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

  /**
   * Another run of the same Stripe invoice holds its run lock (D-PR45-1). This run wrote nothing
   * and answers with the stored state; the sweeper picks the row again later. Only ever "the lock
   * was never acquired": a lock lost mid-run is {@link #SESSION_NOT_PINNED}. Logged at DEBUG.
   * {@code DEI-214} to {@code DEI-216} are reserved by approved plans ({@link ReservedErrorCodes}).
   */
  public static final String INBOUND_RUN_IN_PROGRESS = "DEI-217";

  /**
   * A run was started inside a caller's transaction (D-PR49-1), and a run never joins one. It
   * fetches from Stripe, renders and archives, so it would hold that transaction open for minutes;
   * its writes would show only at the caller's commit, after its run lock was released; and a
   * failed write would abort the caller's transaction before the lock could be released, leaving
   * the lock on a pooled connection. Refused before the lock is tried: nothing was locked, read or
   * written, and the caller's transaction is untouched. Call {@code IssuanceReprocess} and {@code
   * IssuanceUnitOfWork.process} outside a transaction.
   */
  public static final String RUN_IN_HOST_TRANSACTION = "DEI-218";

  /**
   * Stripe refused the key: HTTP 401 or 403 (design "sweeper retry edges", section 5). A revoked or
   * under-scoped restricted key does not improve with time, so the run stops with no retry, the
   * reconciliation pass names the event, and the first successful Stripe call after a refusal (or
   * the next startup) makes the stopped rows due again. A rate limit (429) and every other status
   * stay {@link #STRIPE_UNAVAILABLE}.
   */
  public static final String STRIPE_ACCESS_REFUSED = "DEI-219";

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

  /**
   * Another worker holds this issuance row's archive lock: it is between its ARCHIVING marker and
   * its outcome, so its write may still land (D-RTR-7). Retryable: this run read nothing, rendered
   * nothing and wrote nothing, and the next attempt decides from what the other worker left.
   */
  public static final String ISSUANCE_IN_FLIGHT = "DEI-245";

  /**
   * The issuance row lock could not be released on its own session (D-PR42-4): usually the backend
   * died (a failover, a terminated backend, a network cut). The connection is closed, never pooled,
   * so the lock goes with it. Logged at ERROR; the run's own outcome stands. DEI-246 is reserved by
   * the archive marker design.
   */
  public static final String ROW_LOCK_RELEASE_FAILED = "DEI-247";

  /**
   * The caller's transaction was already aborted before this call (SQLState 25P02 at the first
   * statement of a joined call): the module took no key and wrote nothing. Roll the transaction
   * back and call again (D-PR43-1).
   */
  public static final String HOST_TRANSACTION_ABORTED = "DEI-248";

  /**
   * Joined mode refuses a PostgreSQL connection whose driver option {@code autosave} is {@code
   * ALWAYS}, or whose driver cannot be read (D-PR43-6): that option rolls back a failed statement
   * to the driver's own savepoint, so a failed issuance step would not abort the caller's
   * transaction and its commit could keep a half-written invoice. Use NEVER or CONSERVATIVE. There
   * is no opt-out.
   */
  public static final String HOST_AUTOSAVE_UNSUPPORTED = "DEI-249";

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

  /**
   * The document renderer cannot carry the supply date (BT-72) or the invoicing period (BG-14).
   * Since the issue date is the numbering day, a document without the supply date would hide when
   * the sale happened, so the invoice is refused before a number exists (D-PS1-44).
   */
  public static final String SUPPLY_DATE_NOT_CARRIED = "DEI-280";

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

  /**
   * An operator re-opened a terminal mapping refusal so the pipeline could run over it again, after
   * the configuration that refused it was corrected (QUESTIONS 26). Recorded on the inbound row, so
   * a row that is running again says why it is running again and never looks like an ordinary
   * retry.
   */
  public static final String REPROCESS_REQUESTED = "DEI-264";

  /**
   * A reprocess run failed with an error this module does not type: not an {@link
   * EInvoiceException}, so none of the named DEI-2xx codes describes what happened. Concluded with
   * this rather than with a code whose documented meaning is a different failure.
   */
  public static final String REPROCESS_UNCLASSIFIED_FAILURE = "DEI-277";

  /** A finalised Stripe invoice has no issuance row at all. The finding D-09 exists for. */
  public static final String RECON_MISSING_ISSUANCE = "DEI-270";

  /** An {@code ISSUED} row names an archive object that is not there. */
  public static final String RECON_ARCHIVE_MISSING = "DEI-271";

  /** An archived document no longer hashes to what its row recorded. */
  public static final String RECON_ARCHIVE_DRIFT = "DEI-275";

  /**
   * An archive object that no row predicted, whatever the row's state. Alerted, never deleted. An
   * object at the key of a burned or voided row was predicted and is {@link
   * #RECON_OBJECT_FOR_DISPOSED_NUMBER} instead, reported once (D-RTR-4).
   */
  public static final String RECON_ORPHAN_OBJECT = "DEI-272";

  /** A number allocated and open past {@code einvoice.issuance.alert-after}. */
  public static final String RECON_STUCK_ISSUANCE = "DEI-273";

  /**
   * A privileged reprocess was recorded as requested and never concluded: the process died between
   * the re-open and the end of the run (R-03). Every run that throws still writes its conclusion,
   * so this means a dead process and nothing else - the event may have gone on without anybody
   * seeing where it stopped.
   */
  public static final String RECON_REPROCESS_UNFINISHED = "DEI-276";

  /**
   * A finalised Stripe invoice whose only number was voided: there will never be a document for
   * that sale from this module. Reported rather than re-enqueued, because re-running it can only
   * refuse again (RC-01).
   */
  public static final String RECON_VOIDED_NO_DOCUMENT = "DEI-278";

  /**
   * A finalised Stripe invoice whose number was burned by a validation refusal and not yet voided.
   * The operator's next step is the void; re-enqueueing it only repeats the refusal.
   */
  public static final String RECON_BURNED_NO_DOCUMENT = "DEI-279";

  /**
   * An archive object exists at the key of a burned or voided number (D-RTR-4). The ledger says the
   * number has no document; the archive holds bytes for it. Example: the PUT for {@code
   * F-2026-0042} timed out on the client, the resumed run found nothing and burned the number, and
   * the first PUT completed on the store afterwards. The finding's subject is the Stripe invoice
   * id; the WARN line names the legal number, the key and whether the bytes hash to the row's
   * recorded hash (other bytes are {@link #RECON_ARCHIVE_DRIFT} too). Alerted, never deleted: keep
   * the object as evidence and record it in the series report.
   */
  public static final String RECON_OBJECT_FOR_DISPOSED_NUMBER = "DEI-281";

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
