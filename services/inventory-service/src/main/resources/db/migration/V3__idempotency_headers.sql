ALTER TABLE http_idempotency ADD COLUMN headers jsonb NOT NULL DEFAULT '{}'::jsonb;
UPDATE http_idempotency
SET headers = jsonb_build_object('Content-Type',
    CASE WHEN status >= 400 THEN 'application/problem+json' ELSE 'application/json' END);
