CREATE TABLE http_idempotency (
    operation varchar(100) NOT NULL, key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL, status integer, body text,
    PRIMARY KEY(operation, key)
);
CREATE TABLE message_inbox (consumer_name varchar(100) NOT NULL, event_id uuid NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(consumer_name,event_id));
CREATE TABLE message_outbox (
    event_id uuid PRIMARY KEY, event_type varchar(100) NOT NULL, body text NOT NULL,
    published boolean NOT NULL DEFAULT false, lease_token uuid, lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_pending ON message_outbox(next_attempt, created_at) WHERE NOT published;
