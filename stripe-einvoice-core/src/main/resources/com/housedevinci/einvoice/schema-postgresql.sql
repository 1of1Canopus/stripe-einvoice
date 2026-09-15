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
  IF NEW.seller_id <> OLD.seller_id OR NEW.series <> OLD.series
     OR NEW.fiscal_year <> OLD.fiscal_year OR NEW.mode <> OLD.mode THEN
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
     OR NEW.rule_pack_version <> OLD.rule_pack_version THEN
    RAISE EXCEPTION 'einvoice_issuance: only state, document_sha256, archive_key, void_reason and void_rule_id may change';
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
      ('einvoice_issuance_guard',               'einvoice_issuance',        'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_issuance_guard'),
      ('einvoice_issuance_no_truncate',         'einvoice_issuance',        'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_issuance_event_append_only',   'einvoice_issuance_event',  'BEFORE UPDATE OR DELETE', 'ROW',       'einvoice_append_only'),
      ('einvoice_issuance_event_no_truncate',   'einvoice_issuance_event',  'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only'),
      ('einvoice_issuance_anchor_monotonic',    'einvoice_issuance_anchor', 'BEFORE UPDATE',           'ROW',       'einvoice_anchor_monotonic'),
      ('einvoice_issuance_anchor_no_delete',    'einvoice_issuance_anchor', 'BEFORE DELETE',           'ROW',       'einvoice_append_only'),
      ('einvoice_issuance_anchor_no_truncate',  'einvoice_issuance_anchor', 'BEFORE TRUNCATE',         'STATEMENT', 'einvoice_append_only')
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
