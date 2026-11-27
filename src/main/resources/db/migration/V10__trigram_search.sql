-- V10: typo-tolerant name search ("rvi" finds Ravi) via pg_trgm's
-- word_similarity(). Neon supports pg_trgm and lets the database owner create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
