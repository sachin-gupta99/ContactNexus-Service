-- V5: favourite flag on contacts, used by the star toggle, the favourites
-- filter on /contacts and the overview stats.

ALTER TABLE contacts ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
