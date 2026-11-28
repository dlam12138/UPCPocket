CREATE TABLE pocket_module_switch (
    module_key VARCHAR(32) PRIMARY KEY CHECK (module_key IN ('timetable', 'card', 'grades')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO pocket_module_switch (module_key, enabled, revision, updated_at)
VALUES
    ('timetable', FALSE, 1, CURRENT_TIMESTAMP),
    ('card', FALSE, 1, CURRENT_TIMESTAMP),
    ('grades', FALSE, 1, CURRENT_TIMESTAMP);

CREATE TABLE pocket_audit.module_switch_detail (
    event_id UUID PRIMARY KEY REFERENCES pocket_audit.event(event_id),
    module_key VARCHAR(32) NOT NULL CHECK (module_key IN ('timetable', 'card', 'grades')),
    previous_enabled BOOLEAN NOT NULL,
    new_enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0)
);
