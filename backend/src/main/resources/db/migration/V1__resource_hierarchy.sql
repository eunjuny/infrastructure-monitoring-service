CREATE TABLE infrastructure_cluster (
    id bigint PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    status varchar(16) NOT NULL CHECK (status IN ('NORMAL', 'WARNING', 'ERROR')),
    updated_at timestamptz NOT NULL
);
CREATE TABLE infrastructure_node (
    id bigint PRIMARY KEY,
    cluster_id bigint NOT NULL REFERENCES infrastructure_cluster(id),
    name varchar(160) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('NORMAL', 'WARNING', 'ERROR')),
    updated_at timestamptz NOT NULL,
    cpu_usage numeric(5,2) NOT NULL CHECK (cpu_usage BETWEEN 0 AND 100),
    memory_usage numeric(5,2) NOT NULL CHECK (memory_usage BETWEEN 0 AND 100),
    UNIQUE (cluster_id, name)
);
CREATE TABLE infrastructure_namespace (
    id bigint PRIMARY KEY,
    node_id bigint NOT NULL REFERENCES infrastructure_node(id),
    name varchar(160) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('NORMAL', 'WARNING', 'ERROR')),
    updated_at timestamptz NOT NULL,
    UNIQUE (node_id, name)
);
CREATE TABLE infrastructure_pod (
    id bigint PRIMARY KEY,
    namespace_id bigint NOT NULL REFERENCES infrastructure_namespace(id),
    name varchar(160) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('NORMAL', 'WARNING', 'ERROR')),
    updated_at timestamptz NOT NULL,
    restart_count integer NOT NULL CHECK (restart_count >= 0),
    UNIQUE (namespace_id, name)
);
CREATE INDEX idx_node_parent_status_id ON infrastructure_node(cluster_id, status, id);
CREATE INDEX idx_namespace_parent_status_id ON infrastructure_namespace(node_id, status, id);
CREATE INDEX idx_pod_parent_status_id ON infrastructure_pod(namespace_id, status, id);
CREATE INDEX idx_pod_status_id ON infrastructure_pod(status, id);
