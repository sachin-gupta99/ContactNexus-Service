-- V9: a public link to the user's own contact card.
--
-- The token is the whole capability (anyone with the link can read the chosen
-- fields), so it is 24 random bytes and can be rotated or deleted at any time.
-- `fields` is the comma-separated list of what the owner chose to show.
CREATE TABLE card_shares (
    user_id     BIGINT       PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token       VARCHAR(64)  NOT NULL UNIQUE,
    fields      VARCHAR(500) NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
