CREATE TABLE resource_change_event (
    id bigserial PRIMARY KEY,
    resource_type varchar(16) NOT NULL CHECK (resource_type IN ('CLUSTER','NODE','NAMESPACE','POD')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    occurred_at timestamptz NOT NULL,
    previous_status varchar(16) NOT NULL CHECK (previous_status IN ('NORMAL','WARNING','ERROR')),
    next_status varchar(16) NOT NULL CHECK (next_status IN ('NORMAL','WARNING','ERROR')),
    source varchar(32) NOT NULL CHECK (source = 'MOCK_SIMULATOR'),
    before_attributes jsonb NOT NULL,
    after_attributes jsonb NOT NULL
);
CREATE INDEX idx_event_resource_id ON resource_change_event(resource_type, resource_id, id DESC);
CREATE INDEX idx_event_occurred_at ON resource_change_event(occurred_at, id);
