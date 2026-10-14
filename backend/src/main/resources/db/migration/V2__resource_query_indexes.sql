CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Keyset ordering without a status condition also needs a parent + ID index.
CREATE INDEX idx_node_parent_id ON infrastructure_node(cluster_id, id);
CREATE INDEX idx_namespace_parent_id ON infrastructure_namespace(node_id, id);
CREATE INDEX idx_pod_parent_id ON infrastructure_pod(namespace_id, id);

CREATE INDEX idx_cluster_status_id ON infrastructure_cluster(status, id);
CREATE INDEX idx_node_status_id ON infrastructure_node(status, id);
CREATE INDEX idx_namespace_status_id ON infrastructure_namespace(status, id);

CREATE INDEX idx_cluster_name_trgm ON infrastructure_cluster USING gin (name gin_trgm_ops);
CREATE INDEX idx_node_name_trgm ON infrastructure_node USING gin (name gin_trgm_ops);
CREATE INDEX idx_namespace_name_trgm ON infrastructure_namespace USING gin (name gin_trgm_ops);
CREATE INDEX idx_pod_name_trgm ON infrastructure_pod USING gin (name gin_trgm_ops);
