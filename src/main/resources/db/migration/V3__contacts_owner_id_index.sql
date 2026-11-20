-- V3: index contacts.owner_id.
--
-- Every /api/v1/contacts query is filtered by owner_id (ContactService.list,
-- get, update, delete). Without this index Postgres does a sequential scan on
-- the whole table for every user's request - fine at 10 contacts, painful once
-- any one user crosses a few hundred.
--
-- A trigram index on (first_name, last_name, email) would also help the LIKE
-- search path, but requires the pg_trgm extension and only matters once we
-- have thousands of contacts per user. Punted until then.

CREATE INDEX idx_contacts_owner_id ON contacts (owner_id);
