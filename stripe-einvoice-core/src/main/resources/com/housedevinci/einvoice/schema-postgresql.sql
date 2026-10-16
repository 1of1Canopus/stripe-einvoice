-- Stripe E-Invoice schema (PostgreSQL). Idempotent, and serialised against other instances
-- starting at the same time: the whole script runs in one transaction under this module's own
-- advisory lock (see JdbcSupport.initializeSchema and AdvisoryLocks).
--
-- The lock is the TWO-ARGUMENT form with this module's own class id, never a sibling module's flat
-- constant (numbering design, N-05). Advisory lock keys are database-wide, not schema-wide and not
-- table-wide, and a host that installs two of these modules against one database is the intended
-- deployment: a copy-pasted constant would serialise every invoice issuance against every erasure,
-- visible only under load and looking like a database problem.
-- The key is length-prefixed, exactly as AdvisoryLocks.lengthPrefixed renders it, so this
-- literal and the Java constant name the same lock; a test asserts the two agree.
SELECT pg_advisory_xact_lock(1162432073, hashtext('|15:einvoice_schema'));

-- ---------------------------------------------------------------------------
-- The counter. One row per (seller, series, fiscal year, mode). Never deleted.
--
-- No SEQUENCE and no nextval anywhere in this file: a PostgreSQL sequence is deliberately
-- non-transactional, so every rolled-back transaction, every crash between allocation and archive
-- and every retry after a timeout would leave a permanent hole in a legal series (D-03).
-- Allocation is an UPDATE ... RETURNING on this row, inside the transaction that inserts the
-- issuance row, so a rollback returns the counter to where it was.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS einvoice_series (
  seller_id   varchar(64)  NOT NULL,
  series      varchar(32)  NOT NULL,
  fiscal_year integer      NOT NULL,          -- 0 = a continuous, non-resetting series
  mode        varchar(4)   NOT NULL,          -- 'live' | 'test' (D-01), in the key, not a filter
  prefix      varchar(32)  NOT NULL,
  width       integer      NOT NULL CHECK (width BETWEEN 4 AND 12),
  next_number bigint       NOT NULL CHECK (next_number >= 1),  -- the next value to hand out
  updated_at  timestamptz  NOT NULL,
  PRIMARY KEY (seller_id, series, fiscal_year, mode)
);

-- ---------------------------------------------------------------------------
-- The mapping row: one sale, one number, for all time. Append plus a state column.
-- ---------------------------------------------------------------------------
-- `id` is a bigserial, and that is deliberate and harmless: it is a surrogate row identifier, not
-- the legal number. Nothing prints it, nothing balances on it, and a hole in it means nothing. The
-- legal number comes from einvoice_series under a row lock, for exactly the reason a sequence
-- cannot carry it.
CREATE TABLE IF NOT EXISTS einvoice_issuance (
  id                bigserial PRIMARY KEY,
  seller_id         varchar(64)   NOT NULL,
  mode              varchar(4)    NOT NULL,
  stripe_invoice_id varchar(255)  NOT NULL,
  stripe_account_id varchar(255)  NOT NULL DEFAULT '',   -- '' = the platform account
  stripe_number     varchar(64)   NOT NULL DEFAULT '',   -- a reference only (Decision 2)
  series            varchar(32)   NOT NULL,
  fiscal_year       integer       NOT NULL,
  legal_number      varchar(64)   NOT NULL,
  counter           bigint        NOT NULL,
  issued_at         timestamptz   NOT NULL,              -- BT-2, in the seller's tax zone (D-15)
  allocated_at      timestamptz   NOT NULL,              -- when the counter was consumed (N-08)
  document_sha256   char(64)      NOT NULL DEFAULT '',   -- '' until the bytes exist
  archive_key       varchar(512)  NOT NULL DEFAULT '',
  rule_pack_version varchar(64)   NOT NULL DEFAULT '',
  state             varchar(24)   NOT NULL,
  void_reason       varchar(500)  NOT NULL DEFAULT '',   -- screened free text, VOID_UNUSED only
  void_rule_id      varchar(64)   NOT NULL DEFAULT '',
  UNIQUE (seller_id, mode, stripe_invoice_id),                   -- one sale, one number (D-03)
  UNIQUE (seller_id, series, fiscal_year, mode, legal_number)    -- no duplicate number
);
CREATE INDEX IF NOT EXISTS einvoice_issuance_series
  ON einvoice_issuance (seller_id, series, fiscal_year, mode, counter);
CREATE INDEX IF NOT EXISTS einvoice_issuance_open
  ON einvoice_issuance (state, allocated_at);

-- ---------------------------------------------------------------------------
-- The durable inbound record: every signature-valid event, written before any routing decision
-- and before the endpoint answers (I-01). Stripe's own redelivery is a backstop, never the retry
-- mechanism: it stops after three days, and an outage that lasted four would otherwise cost a
-- legal document with nothing left to show it was ever owed.
--
-- This table is deliberately NOT under the append-only triggers that protect the ledger above, and
-- it IS purgeable (I-07). The issuance row is legal evidence; this row is a transport artifact
-- holding a full invoice payload - buyer name, address, email, tax id, line descriptions. Copying
-- the append-only guards here by reflex would make the retention property impossible to honour and
-- turn the module into a permanent, undeletable copy of every buyer's details. The raw body is
-- nulled as soon as the event reaches a state it can never run from again, and a purge job
-- enforces einvoice.inbound.retention for anything that never got there.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS einvoice_inbound_event (
  event_id          varchar(255) PRIMARY KEY,     -- Stripe's own id: the idempotency key
  event_type        varchar(64)  NOT NULL,
  object_id         varchar(255) NOT NULL,
  api_version       varchar(64)  NOT NULL DEFAULT '',
  livemode          boolean      NOT NULL,
  stripe_account_id varchar(255) NOT NULL DEFAULT '',   -- '' = the platform account
  seller_id         varchar(64)  NOT NULL DEFAULT '',   -- bound once routing resolved it
  mode              varchar(4)   NOT NULL,
  signature_key_id  varchar(64)  NOT NULL DEFAULT '',   -- which keyring id verified it
  received_at       timestamptz  NOT NULL,
  updated_at        timestamptz  NOT NULL,
  state             varchar(32)  NOT NULL,
  attempts          integer      NOT NULL DEFAULT 0,
  next_attempt_at   timestamptz,
  last_code         varchar(16)  NOT NULL DEFAULT '',   -- the stable DEI code, never a message
  last_rule_id      varchar(64)  NOT NULL DEFAULT '',   -- the failing validation rule, for the void
  body              bytea,                              -- nulled at a state that cannot run again
  body_sha256       char(64)     NOT NULL               -- kept after the body is gone
);
-- The sweeper's query: everything not terminal, and every retryable terminal whose time has come.
CREATE INDEX IF NOT EXISTS einvoice_inbound_due
  ON einvoice_inbound_event (state, next_attempt_at);
CREATE INDEX IF NOT EXISTS einvoice_inbound_object
  ON einvoice_inbound_event (seller_id, mode, object_id);

-- ---------------------------------------------------------------------------
-- The compliance findings list (I-04): what an operator must act on, that is not an outage.
--
-- Deliberately not on the health indicator. A void that needs a credit note, a terminal mapping
-- failure or an allocation open for a week are business conditions with no automatic remedy, and a
-- health contributor that lands in the readiness or liveness group would let a three-week-old
-- accounting condition take the host application out of the load balancer.
--
-- Upserted on (seller, mode, code, subject), so a sweep that runs every fifteen minutes refreshes
-- one row rather than adding one. An acknowledgement records a decision and never deletes.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS einvoice_finding (
  seller_id       varchar(64)  NOT NULL,
  mode            varchar(4)   NOT NULL,
  code            varchar(16)  NOT NULL,
  subject_id      varchar(512) NOT NULL,
  first_seen      timestamptz  NOT NULL,
  last_seen       timestamptz  NOT NULL,
  acknowledged_at timestamptz,
  ack_reason      varchar(500) NOT NULL DEFAULT '',   -- screened free text, never a buyer field
  PRIMARY KEY (seller_id, mode, code, subject_id)
);
CREATE INDEX IF NOT EXISTS einvoice_finding_open
  ON einvoice_finding (seller_id, mode, acknowledged_at, first_seen);

-- ---------------------------------------------------------------------------
-- The privileged reprocess record (D9-03).
--
-- One action in this module can turn a recorded refusal into a legal document: an operator
-- re-opening a mapping refusal. Who asked, why and when therefore cannot live in einvoice_finding,
-- which is upserted on (seller, mode, code, subject) and overwritten by the next call.
--
-- Two rows per call and no UPDATE path at all: REQUESTED is written in the same transaction as the
-- re-open - so a re-opened event is always attributed - and CONCLUDED is appended when the run
-- ends, with the outcome or the error code. A REQUESTED with no CONCLUDED therefore means one
-- thing only: the process died mid-run. The reconciliation sweep raises DEI-276 for it.
--
-- Append-only against the application role, and that is the exact claim: SELECT and INSERT only,
-- with triggers refusing UPDATE, DELETE and TRUNCATE. It is NOT hash-chained, so a role that owns
-- the schema can disable the trigger and rewrite a row and no verifier will report it. The
-- issuance chain is the tamper-evident record; this table is not.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS einvoice_reprocess_request (
  seq           bigserial PRIMARY KEY,
  kind          varchar(16)  NOT NULL CHECK (kind IN ('REQUESTED', 'CONCLUDED')),
  request_seq   bigint,                            -- CONCLUDED -> its REQUESTED row
  event_id      varchar(255) NOT NULL,
  seller_id     varchar(64)  NOT NULL,
  mode          varchar(4)   NOT NULL,
  actor         varchar(64)  NOT NULL DEFAULT '',  -- its own column: never concatenated (D9-05)
  reason        varchar(500) NOT NULL DEFAULT '',  -- screened free text, refused never truncated
  at            timestamptz  NOT NULL,
  outcome_state varchar(32)  NOT NULL DEFAULT '',  -- CONCLUDED only
  outcome_code  varchar(16)  NOT NULL DEFAULT '',  -- CONCLUDED only: the DEI code, never a message
  legal_number  varchar(64)  NOT NULL DEFAULT '',
  CONSTRAINT einvoice_reprocess_bounds
    CHECK (char_length(actor) <= 64 AND char_length(reason) <= 500),
  CONSTRAINT einvoice_reprocess_kind_shape
    CHECK ((kind = 'REQUESTED' AND request_seq IS NULL) OR (kind = 'CONCLUDED' AND request_seq IS NOT NULL))
);
-- One conclusion per request: a second CONCLUDED for the same call is a rewrite with extra steps.
CREATE UNIQUE INDEX IF NOT EXISTS einvoice_reprocess_one_conclusion
  ON einvoice_reprocess_request (request_seq) WHERE kind = 'CONCLUDED';
CREATE INDEX IF NOT EXISTS einvoice_reprocess_by_event
  ON einvoice_reprocess_request (event_id, seq);

-- ---------------------------------------------------------------------------
-- The chained issuance log: one row per disposition, append-only, hash-chained.
--
-- Separate from einvoice_issuance on purpose. A chain over a row with a mutable state column
-- cannot verify: the row's own later, legitimate UPDATE changes the hashed material. The mapping
-- row therefore holds the current state, this log holds the immutable history, and the verifier
-- cross-checks the two - a disposed row whose latest chained event disagrees is BROKEN, which is
-- how a rewrite by a role that outranks the triggers is caught.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS einvoice_issuance_event (
  seq               bigserial PRIMARY KEY,
  ts                timestamptz   NOT NULL,
  seller_id         varchar(64)   NOT NULL,
  mode              varchar(4)    NOT NULL,
  stripe_account_id varchar(255)  NOT NULL DEFAULT '',
  series            varchar(32)   NOT NULL,
  fiscal_year       integer       NOT NULL,
  legal_number      varchar(64)   NOT NULL,
  counter           bigint        NOT NULL,
  stripe_invoice_id varchar(255)  NOT NULL,
  stripe_number     varchar(64)   NOT NULL DEFAULT '',
  state             varchar(24)   NOT NULL,
  issued_at         timestamptz,
  document_sha256   char(64)      NOT NULL DEFAULT '',
  archive_key       varchar(512)  NOT NULL DEFAULT '',
  rule_pack_version varchar(64)   NOT NULL DEFAULT '',
  void_reason       varchar(500)  NOT NULL DEFAULT '',
  void_rule_id      varchar(64)   NOT NULL DEFAULT '',
  chain_version     varchar(8)    NOT NULL,
  key_id            varchar(64)   NOT NULL,
  prev_hash         char(64)      NOT NULL,
  hash              char(64)      NOT NULL UNIQUE
);
CREATE INDEX IF NOT EXISTS einvoice_issuance_event_number
  ON einvoice_issuance_event (seller_id, mode, legal_number, seq);

-- Chain anchor: head hash, row count, and the log's keyed/unkeyed mode, written in the same
-- transaction as every append. `keyed` is set once, at the first append, and is immutable
-- afterwards: it is the external, attacker-unwritable record of what every row's chain_version
-- ought to be, because that column is itself part of what a table-owning attacker rewrites.
CREATE TABLE IF NOT EXISTS einvoice_issuance_anchor (
  id         smallint PRIMARY KEY CHECK (id = 1),
  head_hash  char(64)    NOT NULL,
  row_count  bigint      NOT NULL,
  updated_at timestamptz NOT NULL,
  keyed      boolean     NOT NULL
);


-- ---------------------------------------------------------------------------
-- Format version 2 (module 0.2.0): one numbered row belongs to exactly one TYPED series, a credit
-- note names the invoice it corrects, and a series records the latest issue date it numbered.
--
-- Written as an upgrade of the 0.1.0 tables above, so a fresh install and an upgraded one run the
-- same statements and end with the same schema. Every statement is re-runnable. The whole script
-- is one transaction under the advisory lock taken at the top, so an interrupted run leaves the
-- prior version, never half of this one, and two instances starting together run it one after the
-- other (credit-note series design, section 5).
--
-- This is a BLOCKING upgrade (S-11): adding the columns, swapping the series primary key and
-- building the unique indexes take ACCESS EXCLUSIVE locks inside that one transaction, so every
-- issuance on every instance waits until it commits. Stop every instance of the old version first
-- (S-07): an old binary pointed at this schema cannot write a number (see the next paragraph), and
-- its chain verifier would report BROKEN on rows written in the new form. That verdict is
-- meaningless; never act on it.
--
-- The downgrade guard lives in the database, because an old binary cannot be taught (section 4):
-- document_type is added NOT NULL with a default so existing rows read '380', then the DEFAULT is
-- dropped. Every 0.1.0 INSERT names its columns and does not name this one, so the 0.1.0 series
-- seed, issuance insert and chain append all fail with a not-null violation, inside the
-- allocation transaction, so the counter rolls back and nothing is misnumbered. Scope, stated
-- plainly (S-08): that covers the numbering paths only. An old binary can still upsert findings
-- and purge inbound rows, because those tables gain no column.
-- ---------------------------------------------------------------------------

-- The version gate (S-05). Read by the starter at every boot: only "relation does not exist" means
-- absent; any other error stops startup by name. The runtime role gets SELECT on it and nothing
-- else, and a trigger refuses DELETE, TRUNCATE and any UPDATE that lowers a version.
CREATE TABLE IF NOT EXISTS einvoice_schema (
  id                     smallint    PRIMARY KEY CHECK (id = 1),
  format_version         integer     NOT NULL,
  min_compatible_version integer     NOT NULL,
  updated_at             timestamptz NOT NULL
);

-- The document type: the BT-3 code, not a boolean or an enum name, so a later type is a new value
-- rather than a second format change. Only the two types this edition numbers are accepted.
ALTER TABLE einvoice_series         ADD COLUMN IF NOT EXISTS document_type varchar(4) NOT NULL DEFAULT '380';
ALTER TABLE einvoice_issuance       ADD COLUMN IF NOT EXISTS document_type varchar(4) NOT NULL DEFAULT '380';
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS document_type varchar(4) NOT NULL DEFAULT '380';
ALTER TABLE einvoice_series         ALTER COLUMN document_type DROP DEFAULT;
ALTER TABLE einvoice_issuance       ALTER COLUMN document_type DROP DEFAULT;
ALTER TABLE einvoice_issuance_event ALTER COLUMN document_type DROP DEFAULT;

-- The latest BT-2 this series numbered (T9). Moves only forward and only in the statement that
-- moves next_number (the series guard below). NULL until the first allocation of a new series; an
-- upgraded series gets its value from the backfill at the end of this script.
ALTER TABLE einvoice_series ADD COLUMN IF NOT EXISTS last_issue_date date;

-- The corrected-invoice link (credit-note slice, section 1). NULL for every invoice, so 0.1.0 rows
-- satisfy the CHECK below with no backfill. The bound on the target (an issued invoice of the same
-- seller and mode, whose stored hash still matches its chained issue event) is the insert guard
-- further down; immutability is the issuance guard.
ALTER TABLE einvoice_issuance ADD COLUMN IF NOT EXISTS corrects_issuance_id bigint
  REFERENCES einvoice_issuance (id);

-- The target's full identity on the chained event (D-PS1-37): the surrogate id alone is not
-- chained evidence, because nothing chains the mapping from an id to an invoice.
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_issuance_id  bigint;
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_seller_id    varchar(64);
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_mode         varchar(4);
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_series       varchar(32);
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_fiscal_year  integer;
ALTER TABLE einvoice_issuance_event ADD COLUMN IF NOT EXISTS corrects_legal_number varchar(64);

DO $$
DECLARE
  c record;
BEGIN
  FOR c IN
    SELECT * FROM (VALUES
      ('einvoice_series_document_type',         'einvoice_series',
       'CHECK (document_type IN (''380'', ''381''))'),
      ('einvoice_issuance_document_type',       'einvoice_issuance',
       'CHECK (document_type IN (''380'', ''381''))'),
      ('einvoice_issuance_event_document_type', 'einvoice_issuance_event',
       'CHECK (document_type IN (''380'', ''381''))'),
      -- A credit note names the invoice it corrects; an invoice names none.
      ('einvoice_issuance_correction_shape',    'einvoice_issuance',
       'CHECK ((document_type = ''381'') = (corrects_issuance_id IS NOT NULL))'),
      -- D-A1-C06: the six corrects_* columns are empty together (an invoice) or filled together
      -- (a credit note). A full identity planted on an invoice event is neither hashed nor read.
      ('einvoice_issuance_event_correction_shape', 'einvoice_issuance_event',
       'CHECK ((document_type = ''381'' AND corrects_issuance_id IS NOT NULL'
         || ' AND corrects_seller_id IS NOT NULL AND corrects_mode IS NOT NULL'
         || ' AND corrects_series IS NOT NULL AND corrects_fiscal_year IS NOT NULL'
         || ' AND corrects_legal_number IS NOT NULL)'
         || ' OR (document_type = ''380'' AND corrects_issuance_id IS NULL'
         || ' AND corrects_seller_id IS NULL AND corrects_mode IS NULL'
         || ' AND corrects_series IS NULL AND corrects_fiscal_year IS NULL'
         || ' AND corrects_legal_number IS NULL))')
    ) AS t(name, tbl, definition)
  LOOP
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = c.name
          AND conrelid = to_regclass(quote_ident(current_schema()) || '.' || c.tbl)) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s', c.tbl, c.name, c.definition);
    END IF;
  END LOOP;
END $$;

-- S-03: the counter is per TYPED series, so the type joins the primary key. Found by what it is
-- (contype = 'p' on this schema's table), never by name: a host that created the table by hand
-- need not have used PostgreSQL's default constraint name. Dropped and re-added only when its
-- column list differs, so a second run is a no-op.
DO $$
DECLARE
  rel     regclass := to_regclass(quote_ident(current_schema()) || '.einvoice_series');
  pk_name name;
  pk_cols text[];
BEGIN
  SELECT con.conname,
         array_agg(att.attname::text ORDER BY k.ord)
    INTO pk_name, pk_cols
    FROM pg_constraint con
    CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
    JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
   WHERE con.contype = 'p' AND con.conrelid = rel
   GROUP BY con.conname;
  IF pk_cols IS DISTINCT FROM ARRAY['seller_id', 'document_type', 'series', 'fiscal_year', 'mode'] THEN
    IF pk_name IS NOT NULL THEN
      EXECUTE format('ALTER TABLE einvoice_series DROP CONSTRAINT %I', pk_name);
    END IF;
    ALTER TABLE einvoice_series
      ADD CONSTRAINT einvoice_series_pkey
      PRIMARY KEY (seller_id, document_type, series, fiscal_year, mode);
  END IF;
END $$;

-- One series name serves one type in a fiscal year, so the type of a number can be read from its
-- series, and a second row cannot re-assign it. Across fiscal years the insert guard below binds
-- the name to one type for a (seller, mode) (S-09).
CREATE UNIQUE INDEX IF NOT EXISTS einvoice_series_name_binding
  ON einvoice_series (seller_id, mode, fiscal_year, series);

-- Invariant 3: a credit note's legal number never collides with an invoice number of the same
-- seller and mode, as a string, across series and fiscal years. It already holds on a 0.1.0
-- database whose resetting series carries the year in its prefix. Where it does not, the upgrade
-- refuses by name with a count and no identifiers, and changes nothing (S-06): it never drops a
-- row to make itself pass. "Changes nothing" holds when the script is run as documented, in one
-- transaction with ON_ERROR_STOP (docs/index.md); the final block below holds it for any runner. SQLSTATE EIU01 is this module's own, mapped to DEI-134.
DO $$
DECLARE
  duplicated bigint;
BEGIN
  IF to_regclass(quote_ident(current_schema()) || '.einvoice_issuance_number_unique') IS NULL THEN
    SELECT count(*) INTO duplicated FROM (
      SELECT 1 FROM einvoice_issuance
       GROUP BY seller_id, mode, legal_number HAVING count(*) > 1) d;
    IF duplicated > 0 THEN
      RAISE EXCEPTION USING
        ERRCODE = 'EIU01',
        MESSAGE = format('schema upgrade refused: %s legal number(s) are carried by more than one'
          || ' row of one seller and mode. Format 2 requires a legal number to be unique per seller'
          || ' and mode. If run as documented, nothing was changed.', duplicated);
    END IF;
  END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS einvoice_issuance_number_unique
  ON einvoice_issuance (seller_id, mode, legal_number);
CREATE INDEX IF NOT EXISTS einvoice_issuance_corrects
  ON einvoice_issuance (corrects_issuance_id) WHERE corrects_issuance_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Triggers. A role that owns a table can still DISABLE TRIGGER, so run the application with a role
-- that holds only the grants in docs/schema-grants.sql; the startup check says so out loud when it
-- does not. Every guard resolves tgrelid against quote_ident(current_schema()) rather than matching
-- on the trigger name alone: pg_trigger is database-wide and trigger names are per-table, so a bare
-- tgname check is satisfied by the same-named trigger on another schema's copy of this table and
-- would leave this schema unguarded (the sibling module's CIPHER-05).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION einvoice_series_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'einvoice_series rows are never deleted or truncated (attempted %)', TG_OP;
  END IF;
  -- S-04: the document type is part of the identity. The binding of a series to a type lives in
  -- this row, so changing it would turn a year of invoices into credit notes in one statement.
  IF NEW.seller_id <> OLD.seller_id OR NEW.series <> OLD.series
     OR NEW.fiscal_year <> OLD.fiscal_year OR NEW.mode <> OLD.mode
     OR NEW.document_type IS DISTINCT FROM OLD.document_type THEN
    RAISE EXCEPTION 'einvoice_series identity is immutable';
  END IF;
  -- prefix and width are part of the rendered number: changing either mid-series changes the
  -- format of a legal number, which is exactly the defect this module refuses to inherit.
  IF NEW.prefix <> OLD.prefix OR NEW.width <> OLD.width THEN
    RAISE EXCEPTION 'einvoice_series prefix and width are immutable once the series exists';
  END IF;
  IF NEW.next_number <> OLD.next_number + 1 THEN
    RAISE EXCEPTION 'einvoice_series.next_number only advances by one (attempted % -> %)',
      OLD.next_number, NEW.next_number;
  END IF;
  -- D-PS1-38: the latest issue date moves only in the statement that moves the counter (the rule
  -- above refuses every other UPDATE), never backwards and never back to NULL, for every type.
  -- An invoice dated earlier than the series' latest date is still numbered: the statement keeps
  -- the later date with GREATEST and the allocator records a finding instead.
  IF NEW.last_issue_date IS DISTINCT FROM OLD.last_issue_date
     AND (NEW.last_issue_date IS NULL
          OR (OLD.last_issue_date IS NOT NULL AND NEW.last_issue_date < OLD.last_issue_date)) THEN
    RAISE EXCEPTION 'einvoice_series.last_issue_date never goes backwards (attempted % -> %)',
      OLD.last_issue_date, NEW.last_issue_date;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION einvoice_issuance_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'einvoice_issuance is append-only (attempted %)', TG_OP;
  END IF;
  IF NEW.id <> OLD.id
     OR NEW.seller_id <> OLD.seller_id OR NEW.mode <> OLD.mode
     OR NEW.stripe_invoice_id <> OLD.stripe_invoice_id
     OR NEW.stripe_account_id <> OLD.stripe_account_id
     OR NEW.stripe_number <> OLD.stripe_number
     OR NEW.series <> OLD.series OR NEW.fiscal_year <> OLD.fiscal_year
     OR NEW.legal_number <> OLD.legal_number OR NEW.counter <> OLD.counter
     OR NEW.issued_at <> OLD.issued_at OR NEW.allocated_at <> OLD.allocated_at
     OR NEW.rule_pack_version <> OLD.rule_pack_version
     OR NEW.document_type IS DISTINCT FROM OLD.document_type
     OR NEW.corrects_issuance_id IS DISTINCT FROM OLD.corrects_issuance_id THEN
    RAISE EXCEPTION 'einvoice_issuance: only state, document_sha256, archive_key, void_reason and void_rule_id may change, and the last two only while the row is open';
  END IF;
  -- N-06: write-once, not merely updatable. The chain detects a rewrite of either as BROKEN, but
  -- detection is not prevention when prevention costs one clause.
  IF OLD.document_sha256 <> '' AND NEW.document_sha256 <> OLD.document_sha256 THEN
    RAISE EXCEPTION 'einvoice_issuance.document_sha256 is write-once';
  END IF;
  IF OLD.archive_key <> '' AND NEW.archive_key <> OLD.archive_key THEN
    RAISE EXCEPTION 'einvoice_issuance.archive_key is write-once';
  END IF;
  IF OLD.void_reason <> '' AND NEW.void_reason <> OLD.void_reason THEN
    RAISE EXCEPTION 'einvoice_issuance.void_reason is write-once';
  END IF;
  -- RC-02: void_rule_id was freely rewritable, and void_reason was write-once only once it was
  -- non-empty - and a FAILED_VALIDATION burn leaves it empty, so a reason that was never chained
  -- could be planted on a burned row and read back by the series report as the explanation for a
  -- hole in the issued sequence. Both columns now change only in the statement that records the
  -- disposition itself: the move to VOID_UNUSED. A bare UPDATE of either is refused, and a planted
  -- disposition that does move the state writes no chained event, which the verifier's cross-check
  -- reports as BROKEN. Not "write-once" as the other two columns are: the void legitimately
  -- replaces the burn's rule id, in that one statement and nowhere else.
  IF (NEW.void_reason <> OLD.void_reason OR NEW.void_rule_id <> OLD.void_rule_id)
     AND NOT (NEW.state <> OLD.state AND NEW.state IN ('VOID_UNUSED', 'FAILED_VALIDATION')) THEN
    RAISE EXCEPTION 'einvoice_issuance: the void justification changes only when the number is voided';
  END IF;
  -- The same transition set the IssuanceState enum declares, re-asserted here so a transition is
  -- refused in two independent places. Voiding an ISSUED number is an attempt to unpublish a legal
  -- document and is refused in both (N-04).
  IF NEW.state <> OLD.state THEN
    IF NOT (
         (OLD.state = 'NUMBERED'          AND NEW.state IN ('ARCHIVING', 'FAILED_VALIDATION', 'FAILED_ARCHIVE', 'VOID_UNUSED'))
      OR (OLD.state = 'ARCHIVING'         AND NEW.state IN ('ISSUED', 'FAILED_ARCHIVE', 'VOID_UNUSED'))
      OR (OLD.state = 'FAILED_ARCHIVE'    AND NEW.state IN ('ARCHIVING', 'VOID_UNUSED'))
      OR (OLD.state = 'FAILED_VALIDATION' AND NEW.state IN ('VOID_UNUSED'))
    ) THEN
      RAISE EXCEPTION 'einvoice_issuance: % -> % is not a declared transition', OLD.state, NEW.state;
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- S-09 and the seed rule. A series name binds to one document type for a (seller, mode) across
-- every fiscal year, so "the type of a number is readable from its series" holds beyond one year.
-- A new series row starts with no issue date: the first allocation sets it. SQLSTATE EIS01.
CREATE OR REPLACE FUNCTION einvoice_series_insert_guard() RETURNS trigger AS $$
DECLARE
  other boolean;
BEGIN
  -- NR-1: the table is read through the trigger's own schema, never through search_path, where a
  -- temporary table of the same name would come first.
  EXECUTE format(
    'SELECT EXISTS (SELECT 1 FROM %I.einvoice_series s'
    || ' WHERE s.seller_id = $1 AND s.mode = $2 AND s.series = $3 AND s.document_type <> $4)',
    TG_TABLE_SCHEMA)
    INTO other USING NEW.seller_id, NEW.mode, NEW.series, NEW.document_type;
  IF other THEN
    RAISE EXCEPTION USING
      ERRCODE = 'EIS01',
      MESSAGE = 'einvoice_series: this series name already numbers another document type for this'
        || ' seller and mode; one series name serves one type';
  END IF;
  IF NEW.last_issue_date IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = 'EIS01',
      MESSAGE = 'einvoice_series: a new series row starts with no issue date';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- D-CNS-S12, enforced where the row is written, for every writer: a credit note may correct only
-- an invoice ('380') this module issued, of the same seller and mode, whose stored document hash
-- still equals the hash on the latest chained event of that number, and that event is ISSUED.
-- ISSUED is terminal and the identity columns are immutable, so the check holds for ever once it
-- passed. The CHECK on the table, not this function, says that a credit note must name a target.
-- SQLSTATE EIT01, mapped to DEI-128.
CREATE OR REPLACE FUNCTION einvoice_issuance_insert_guard() RETURNS trigger AS $$
DECLARE
  t record;
  latest record;
BEGIN
  IF NEW.corrects_issuance_id IS NULL THEN
    RETURN NEW;
  END IF;
  -- NR-1: every table read here is qualified with the trigger's own schema (a guard that reads a
  -- table qualifies it with TG_TABLE_SCHEMA). EXECUTE does not set FOUND, so absence is read from
  -- the NOT NULL columns.
  EXECUTE format(
    'SELECT id, seller_id, mode, document_type, state, legal_number,'
    || ' btrim(document_sha256) AS sha FROM %I.einvoice_issuance WHERE id = $1',
    TG_TABLE_SCHEMA)
    INTO t USING NEW.corrects_issuance_id;
  IF t.id IS NULL
     OR t.document_type <> '380' OR t.state <> 'ISSUED'
     OR t.seller_id <> NEW.seller_id OR t.mode <> NEW.mode OR t.sha = '' THEN
    RAISE EXCEPTION USING
      ERRCODE = 'EIT01',
      MESSAGE = 'einvoice_issuance: a credit note may correct only an issued invoice of the same'
        || ' seller and mode';
  END IF;
  EXECUTE format(
    'SELECT state, btrim(document_sha256) AS sha FROM %I.einvoice_issuance_event'
    || ' WHERE seller_id = $1 AND mode = $2 AND legal_number = $3 ORDER BY seq DESC LIMIT 1',
    TG_TABLE_SCHEMA)
    INTO latest USING t.seller_id, t.mode, t.legal_number;
  IF latest.state IS NULL OR latest.state <> 'ISSUED' OR latest.sha <> t.sha THEN
    RAISE EXCEPTION USING
      ERRCODE = 'EIT01',
      MESSAGE = 'einvoice_issuance: the corrected invoice''s stored hash does not match its chained'
        || ' issue event';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- S-05 (c): the version row cannot be deleted, truncated or lowered, by any role the trigger binds.
CREATE OR REPLACE FUNCTION einvoice_schema_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'einvoice_schema is never deleted or truncated (attempted %)', TG_OP;
  END IF;
  IF NEW.id <> OLD.id
     OR NEW.format_version < OLD.format_version
     OR NEW.min_compatible_version < OLD.min_compatible_version THEN
    RAISE EXCEPTION 'einvoice_schema versions never go down (attempted % -> %)',
      OLD.format_version, NEW.format_version;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION einvoice_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% is append-only (attempted %)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION einvoice_anchor_monotonic() RETURNS trigger AS $$
BEGIN
  -- `keyed` first: a caller that also gets the row count wrong must still be told the real
  -- problem, which is that it is trying to change the log's mode.
  IF NEW.keyed IS DISTINCT FROM OLD.keyed THEN
    RAISE EXCEPTION 'einvoice_issuance_anchor.keyed is immutable once set (attempted % -> %)',
      OLD.keyed, NEW.keyed;
  END IF;
  IF NEW.row_count <> OLD.row_count + 1 OR NEW.head_hash = OLD.head_hash THEN
    RAISE EXCEPTION 'einvoice_issuance_anchor only advances by one row (attempted % -> %)',
      OLD.row_count, NEW.row_count;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
  guard record;
BEGIN
  FOR guard IN
    SELECT * FROM (VALUES
      ('einvoice_series_guard',                 'einvoice_series',          'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_series_guard'),
      ('einvoice_series_no_truncate',           'einvoice_series',          'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_series_insert_guard',          'einvoice_series',          'BEFORE INSERT',           'ROW',       'einvoice_series_insert_guard'),
      ('einvoice_issuance_insert_guard',        'einvoice_issuance',        'BEFORE INSERT',           'ROW',       'einvoice_issuance_insert_guard'),
      ('einvoice_schema_guard',                 'einvoice_schema',          'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_schema_guard'),
      ('einvoice_schema_no_truncate',           'einvoice_schema',          'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_issuance_guard',               'einvoice_issuance',        'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_issuance_guard'),
      ('einvoice_issuance_no_truncate',         'einvoice_issuance',        'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_issuance_event_append_only',   'einvoice_issuance_event',  'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_append_only'),
      ('einvoice_issuance_event_no_truncate',   'einvoice_issuance_event',  'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_issuance_anchor_monotonic',    'einvoice_issuance_anchor', 'BEFORE UPDATE',           'ROW',       'einvoice_anchor_monotonic'),
      ('einvoice_issuance_anchor_no_delete',    'einvoice_issuance_anchor', 'BEFORE DELETE',           'ROW',       'einvoice_append_only'),
      ('einvoice_issuance_anchor_no_truncate',  'einvoice_issuance_anchor', 'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_reprocess_append_only',        'einvoice_reprocess_request', 'BEFORE UPDATE OR DELETE', 'ROW',     'einvoice_append_only'),
      ('einvoice_reprocess_no_truncate',        'einvoice_reprocess_request', 'BEFORE TRUNCATE',       'STATEMENT', 'einvoice_append_only')
    ) AS t(name, tbl, timing, level, fn)
  LOOP
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = guard.name
          AND tgrelid = to_regclass(quote_ident(current_schema()) || '.' || guard.tbl)) THEN
      EXECUTE format(
        'CREATE TRIGGER %I %s ON %I FOR EACH %s EXECUTE FUNCTION %I()',
        guard.name, guard.timing, guard.tbl, guard.level, guard.fn);
    END IF;
  END LOOP;
END $$;

-- The schema step never seeds an anchor row from an existing, non-empty log: deriving `keyed` from
-- row data is exactly the guess the anchor exists to make unnecessary. A log with rows and no
-- anchor is reported NO_ANCHOR by the verifier and refused on append; the operator remedy is to
-- archive the pair and start a new log. For an empty log there is nothing to seed: the first real
-- append creates the anchor row.


-- ---------------------------------------------------------------------------
-- Backfill of last_issue_date on an upgraded installation. A series that already numbered rows
-- gets the latest BT-2 of its rows, as a calendar date in the seller's tax zone. Only the caller
-- knows that zone: the starter sets the session setting einvoice.tax_zone from
-- einvoice.seller.tax-zone before it runs this script; an owner who runs it by hand sets it first
-- (SET einvoice.tax_zone = 'Europe/Paris'). When a backfill is needed and no zone is set, the
-- upgrade refuses by name and changes nothing (SQLSTATE EIU02, DEI-134). Guessing a zone would
-- record a date the documents do not carry. The zone must be a region id listed in
-- pg_timezone_names: an offset id is read by PostgreSQL with the opposite sign (D-A1-C05).
--
-- The series guard refuses any UPDATE that does not advance the counter, so the backfill runs
-- with that one trigger disabled, inside this transaction, as the schema owner. The date comes
-- from PostgreSQL's time-zone data, not the JVM's; the two can differ on a zone whose rules
-- changed, by at most the size of that change.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  zone text := nullif(current_setting('einvoice.tax_zone', true), '');
BEGIN
  IF EXISTS (
      SELECT 1 FROM einvoice_series s
       WHERE s.last_issue_date IS NULL
         AND EXISTS (
           SELECT 1 FROM einvoice_issuance i
            WHERE i.seller_id = s.seller_id AND i.mode = s.mode AND i.series = s.series
              AND i.fiscal_year = s.fiscal_year AND i.document_type = s.document_type)) THEN
    IF zone IS NULL THEN
      RAISE EXCEPTION USING
        ERRCODE = 'EIU02',
        MESSAGE = 'schema upgrade refused: series with numbered rows need their latest issue date,'
          || ' and that date depends on the seller''s tax zone. Set einvoice.tax_zone for this'
          || ' session (the starter does it from einvoice.seller.tax-zone). If run as documented,'
          || ' nothing was changed.';
    END IF;
    -- Region ids only. PostgreSQL reads an offset id such as +02:00 or UTC+02:00 as a POSIX zone
    -- with the sign reversed, and accepts invented names such as Nowhere+5; neither is in this
    -- list. Etc/GMT-2 is listed and means the same in Java and in PostgreSQL.
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names n WHERE n.name = zone) THEN
      RAISE EXCEPTION USING
        ERRCODE = 'EIU02',
        MESSAGE = 'schema upgrade refused: einvoice.tax_zone must be a region id such as'
          || ' Europe/Paris, known to PostgreSQL; an offset id (+02:00, UTC+02:00) or an unknown'
          || ' name is not accepted. If run as documented, nothing was changed.';
    END IF;
    ALTER TABLE einvoice_series DISABLE TRIGGER einvoice_series_guard;
    UPDATE einvoice_series s
       SET last_issue_date = (
         SELECT max((i.issued_at AT TIME ZONE zone)::date) FROM einvoice_issuance i
          WHERE i.seller_id = s.seller_id AND i.mode = s.mode AND i.series = s.series
            AND i.fiscal_year = s.fiscal_year AND i.document_type = s.document_type)
     WHERE s.last_issue_date IS NULL
       AND EXISTS (
         SELECT 1 FROM einvoice_issuance i
          WHERE i.seller_id = s.seller_id AND i.mode = s.mode AND i.series = s.series
            AND i.fiscal_year = s.fiscal_year AND i.document_type = s.document_type);
    ALTER TABLE einvoice_series ENABLE TRIGGER einvoice_series_guard;
  END IF;
END $$;

-- Last statement: the version stamp, in ONE block that first checks every format-2 condition and
-- raises EIU03 (mapped to DEI-134) naming the first one missing. The stamp is inside the same block,
-- so a refusal can never be followed by the stamp, whatever runs the script: a runner that goes on
-- after an error (psql without ON_ERROR_STOP) still cannot mark an unfinished upgrade as format 2
-- (D-A1-C01). Raised only, never lowered (the guard above refuses a lower one anyway).
DO $$
DECLARE
  missing text;
  rel     regclass;
  pk_cols text[];
  c       record;
BEGIN
  -- the new columns exist, and the three document_type columns have no default
  FOR c IN
    SELECT * FROM (VALUES
      ('einvoice_series', 'document_type', true), ('einvoice_issuance', 'document_type', true),
      ('einvoice_issuance_event', 'document_type', true), ('einvoice_series', 'last_issue_date', false),
      ('einvoice_issuance', 'corrects_issuance_id', false),
      ('einvoice_issuance_event', 'corrects_issuance_id', false),
      ('einvoice_issuance_event', 'corrects_seller_id', false),
      ('einvoice_issuance_event', 'corrects_mode', false),
      ('einvoice_issuance_event', 'corrects_series', false),
      ('einvoice_issuance_event', 'corrects_fiscal_year', false),
      ('einvoice_issuance_event', 'corrects_legal_number', false)
    ) AS t(tbl, col, no_default)
  LOOP
    IF missing IS NULL AND NOT EXISTS (
        SELECT 1 FROM pg_attribute a
         WHERE a.attrelid = to_regclass(quote_ident(current_schema()) || '.' || c.tbl)
           AND a.attname = c.col AND NOT a.attisdropped
           AND (NOT c.no_default OR NOT a.atthasdef)) THEN
      missing := format('column %s.%s is missing or still has a default', c.tbl, c.col);
    END IF;
  END LOOP;

  -- the series primary key carries the document type
  IF missing IS NULL THEN
    rel := to_regclass(quote_ident(current_schema()) || '.einvoice_series');
    SELECT array_agg(att.attname::text ORDER BY k.ord) INTO pk_cols
      FROM pg_constraint con
      CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
     WHERE con.contype = 'p' AND con.conrelid = rel;
    IF pk_cols IS DISTINCT FROM ARRAY['seller_id', 'document_type', 'series', 'fiscal_year', 'mode'] THEN
      missing := 'the einvoice_series primary key on (seller_id, document_type, series, fiscal_year, mode)';
    END IF;
  END IF;

  -- the five CHECK constraints
  IF missing IS NULL THEN
    FOR c IN
      SELECT * FROM (VALUES
        ('einvoice_series_document_type', 'einvoice_series'),
        ('einvoice_issuance_document_type', 'einvoice_issuance'),
        ('einvoice_issuance_event_document_type', 'einvoice_issuance_event'),
        ('einvoice_issuance_correction_shape', 'einvoice_issuance'),
        ('einvoice_issuance_event_correction_shape', 'einvoice_issuance_event')
      ) AS t(name, tbl)
    LOOP
      IF missing IS NULL AND NOT EXISTS (
          SELECT 1 FROM pg_constraint
           WHERE conname = c.name AND contype = 'c' AND convalidated
             AND conrelid = to_regclass(quote_ident(current_schema()) || '.' || c.tbl)) THEN
        missing := format('the constraint %s', c.name);
      END IF;
    END LOOP;
  END IF;

  -- the unique indexes
  IF missing IS NULL THEN
    FOR c IN
      SELECT * FROM (VALUES
        ('einvoice_issuance_number_unique'), ('einvoice_series_name_binding')) AS t(name)
    LOOP
      IF missing IS NULL AND NOT EXISTS (
          SELECT 1 FROM pg_index x
           WHERE x.indexrelid = to_regclass(quote_ident(current_schema()) || '.' || c.name)
             AND x.indisunique AND x.indisvalid) THEN
        missing := format('the unique index %s', c.name);
      END IF;
    END LOOP;
  END IF;

  -- the three triggers that are new in format 2
  IF missing IS NULL THEN
    FOR c IN
      SELECT * FROM (VALUES
        ('einvoice_series_insert_guard', 'einvoice_series'),
        ('einvoice_issuance_insert_guard', 'einvoice_issuance'),
        ('einvoice_schema_guard', 'einvoice_schema')
      ) AS t(name, tbl)
    LOOP
      IF missing IS NULL AND NOT EXISTS (
          SELECT 1 FROM pg_trigger
           WHERE tgname = c.name AND NOT tgisinternal
             AND tgrelid = to_regclass(quote_ident(current_schema()) || '.' || c.tbl)) THEN
        missing := format('the trigger %s', c.name);
      END IF;
    END LOOP;
  END IF;

  -- no series that numbered rows may still lack its latest issue date (zone missing or refused)
  IF missing IS NULL AND EXISTS (
      SELECT 1 FROM einvoice_series s
       WHERE s.last_issue_date IS NULL
         AND EXISTS (
           SELECT 1 FROM einvoice_issuance i
            WHERE i.seller_id = s.seller_id AND i.mode = s.mode AND i.series = s.series
              AND i.fiscal_year = s.fiscal_year AND i.document_type = s.document_type)) THEN
    missing := 'the latest issue date of every series that numbered rows (the tax zone was missing'
      || ' or refused)';
  END IF;

  IF missing IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = 'EIU03',
      MESSAGE = 'schema upgrade refused: format 2 is not complete, missing: ' || missing
        || '. The version was not stamped. Fix the cause and run the script again.';
  END IF;

  INSERT INTO einvoice_schema (id, format_version, min_compatible_version, updated_at)
  VALUES (1, 2, 2, now())
  ON CONFLICT (id) DO UPDATE
    SET format_version = EXCLUDED.format_version,
        min_compatible_version = EXCLUDED.min_compatible_version,
        updated_at = EXCLUDED.updated_at
    WHERE einvoice_schema.format_version < EXCLUDED.format_version;
END $$;
