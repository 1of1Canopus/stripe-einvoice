-- The database role this module should run as, and nothing more.
--
-- The append-only triggers refuse UPDATE, DELETE and TRUNCATE for every role, but a role that OWNS
-- a table can run ALTER TABLE ... DISABLE TRIGGER and walk past all of them. So the triggers are
-- the second line here, not the first: the first is that the runtime role does not own these
-- tables. The startup check WARNs at every boot when it does.
--
-- Run the CREATE TABLE half (the bundled schema) as a migration/owner role, then:

CREATE ROLE einvoice_runtime LOGIN PASSWORD 'set-me-from-the-environment';

GRANT SELECT, INSERT ON einvoice_series, einvoice_issuance,
                        einvoice_issuance_event, einvoice_issuance_anchor
  TO einvoice_runtime;

-- UPDATE is needed on three of the four, and the triggers bound what an UPDATE may change:
--   einvoice_series           the counter, and only by +1
--   einvoice_issuance         the state, the document hash and key (write-once), the void reason
--   einvoice_issuance_anchor  the head, and only forward by one row
GRANT UPDATE ON einvoice_series, einvoice_issuance, einvoice_issuance_anchor
  TO einvoice_runtime;

-- The surrogate row ids. These sequences number rows, never invoices.
GRANT USAGE ON SEQUENCE einvoice_issuance_id_seq, einvoice_issuance_event_seq_seq
  TO einvoice_runtime;

-- The durable inbound record is the one table with a different answer, deliberately (I-07). It
-- holds the raw signed webhook bodies - a full invoice payload per row, with buyer name, address,
-- email, tax id and line descriptions - so it is a transport artifact under a retention ceiling,
-- not legal evidence. It carries no append-only trigger and the runtime role may DELETE from it,
-- because a table nobody can ever delete from would make the retention property impossible to
-- honour and turn this module into a permanent copy of every buyer's details.
GRANT SELECT, INSERT, UPDATE, DELETE ON einvoice_inbound_event TO einvoice_runtime;

-- The findings list is operator-facing working state, not evidence: a finding is refreshed by
-- every sweep, acknowledged with a reason, and purged with the events it refers to.
GRANT SELECT, INSERT, UPDATE, DELETE ON einvoice_finding TO einvoice_runtime;

-- The privileged reprocess record: append-only against this role, SELECT and INSERT only, with the
-- triggers refusing UPDATE, DELETE and TRUNCATE. It is not hash-chained: a role that owns the
-- schema can disable the trigger and rewrite a row, and nothing in this module will report that.
GRANT SELECT, INSERT ON einvoice_reprocess_request TO einvoice_runtime;
GRANT USAGE ON SEQUENCE einvoice_reprocess_request_seq_seq TO einvoice_runtime;

-- Deliberately NOT granted: DELETE and TRUNCATE on the four ledger tables, and any DDL anywhere.
-- There is no code path in this module that needs them, and a legal ledger that can be deleted by
-- the application that writes it proves nothing about what it once held.
