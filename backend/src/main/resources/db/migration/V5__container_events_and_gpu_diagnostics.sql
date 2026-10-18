ALTER TABLE infrastructure_node
    ADD COLUMN cpu_allocatable_cores numeric(8,2) NOT NULL DEFAULT 7.20 CHECK (cpu_allocatable_cores > 0),
    ADD COLUMN cpu_requested_cores numeric(8,2) NOT NULL DEFAULT 4.00 CHECK (cpu_requested_cores >= 0),
    ADD COLUMN memory_allocatable_mib bigint NOT NULL DEFAULT 14745 CHECK (memory_allocatable_mib > 0),
    ADD COLUMN memory_requested_mib bigint NOT NULL DEFAULT 8192 CHECK (memory_requested_mib >= 0);

UPDATE infrastructure_node
SET cpu_allocatable_cores = cpu_capacity_cores * 0.90,
    cpu_requested_cores = cpu_capacity_cores * (0.35 + (id % 5) * 0.10),
    memory_allocatable_mib = (memory_capacity_mib * 0.90)::bigint,
    memory_requested_mib = (memory_capacity_mib * (0.35 + (id % 5) * 0.10))::bigint;

CREATE TABLE infrastructure_pod_container (
    id bigserial PRIMARY KEY,
    pod_id bigint NOT NULL REFERENCES infrastructure_pod(id) ON DELETE CASCADE,
    name varchar(80) NOT NULL,
    image varchar(240) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING', 'WAITING', 'TERMINATED')),
    ready boolean NOT NULL,
    restart_count integer NOT NULL CHECK (restart_count >= 0),
    cpu_request_millicores integer NOT NULL CHECK (cpu_request_millicores >= 0),
    cpu_usage_millicores integer NOT NULL CHECK (cpu_usage_millicores >= 0),
    memory_request_mib integer NOT NULL CHECK (memory_request_mib >= 0),
    memory_usage_mib integer NOT NULL CHECK (memory_usage_mib >= 0),
    UNIQUE (pod_id, name)
);
CREATE INDEX idx_pod_container_parent_id ON infrastructure_pod_container(pod_id, id);

CREATE TABLE infrastructure_node_gpu (
    id bigserial PRIMARY KEY,
    node_id bigint NOT NULL REFERENCES infrastructure_node(id) ON DELETE CASCADE,
    device_name varchar(80) NOT NULL,
    model varchar(120) NOT NULL,
    memory_mib integer NOT NULL CHECK (memory_mib > 0),
    utilization_percent numeric(5,2) NOT NULL CHECK (utilization_percent BETWEEN 0 AND 100),
    temperature_celsius numeric(5,2) NOT NULL CHECK (temperature_celsius BETWEEN -20 AND 150),
    allocated_pods integer NOT NULL CHECK (allocated_pods >= 0),
    updated_at timestamptz NOT NULL,
    UNIQUE (node_id, device_name)
);
CREATE INDEX idx_node_gpu_parent_id ON infrastructure_node_gpu(node_id, id);

CREATE TABLE infrastructure_kubernetes_event (
    id bigserial PRIMARY KEY,
    resource_type varchar(16) NOT NULL CHECK (resource_type IN ('CLUSTER','NODE','NAMESPACE','POD')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    event_type varchar(16) NOT NULL CHECK (event_type IN ('NORMAL','WARNING')),
    reason varchar(80) NOT NULL,
    message varchar(500) NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    occurrence_count integer NOT NULL CHECK (occurrence_count > 0),
    UNIQUE (resource_type, resource_id)
);
CREATE INDEX idx_kubernetes_event_resource_latest
    ON infrastructure_kubernetes_event(resource_type, resource_id, last_seen_at DESC, id DESC);
CREATE INDEX idx_kubernetes_event_latest
    ON infrastructure_kubernetes_event(last_seen_at DESC, id DESC);

-- Materialize only bounded mock diagnostics. Container rows are fetched only for a selected Pod.
INSERT INTO infrastructure_pod_container
    (pod_id, name, image, state, ready, restart_count, cpu_request_millicores,
     cpu_usage_millicores, memory_request_mib, memory_usage_mib)
SELECT p.id, 'application', p.image,
       CASE WHEN p.status = 'ERROR' AND p.id % 2 = 0 THEN 'WAITING'
            WHEN p.status = 'ERROR' AND p.id % 2 = 1 THEN 'TERMINATED'
            WHEN p.status = 'WARNING' THEN 'WAITING' ELSE 'RUNNING' END,
       p.status = 'NORMAL', p.restart_count, 250 + (p.id % 4) * 100,
       (50 + (p.id % 4) * 25), 256 + (p.id % 4) * 128, 96 + (p.id % 4) * 48
FROM infrastructure_pod p;

INSERT INTO infrastructure_pod_container
    (pod_id, name, image, state, ready, restart_count, cpu_request_millicores,
     cpu_usage_millicores, memory_request_mib, memory_usage_mib)
SELECT p.id, 'metrics-sidecar', 'registry.example.invalid/mock/metrics-sidecar:1.0.0',
       CASE WHEN p.status = 'ERROR' OR p.status = 'WARNING' THEN 'WAITING' ELSE 'RUNNING' END,
       p.status = 'NORMAL', CASE WHEN p.status = 'WARNING' THEN 1 ELSE 0 END,
       100, 25 + (p.id % 3) * 10, 128, 48 + (p.id % 3) * 16
FROM infrastructure_pod p WHERE p.container_count > 1;

INSERT INTO infrastructure_node_gpu
    (node_id, device_name, model, memory_mib, utilization_percent, temperature_celsius, allocated_pods, updated_at)
SELECT n.id, 'gpu-' || device_number,
       CASE WHEN n.id % 2 = 0 THEN 'NVIDIA A100 40GB' ELSE 'NVIDIA L40S 48GB' END,
       CASE WHEN n.id % 2 = 0 THEN 40960 ELSE 49152 END,
       mod(n.id * 17 + device_number * 13, 101)::numeric(5,2),
       (35 + mod(n.id * 3 + device_number * 7, 51))::numeric(5,2),
       mod(n.id + device_number, 5), n.updated_at
FROM infrastructure_node n
CROSS JOIN generate_series(1, 2) AS device_number
WHERE n.id % 10 = 0;

INSERT INTO infrastructure_kubernetes_event
    (resource_type, resource_id, event_type, reason, message, first_seen_at, last_seen_at, occurrence_count)
SELECT resource_type, resource_id,
       'WARNING',
       CASE WHEN resource_type = 'NODE' AND status = 'ERROR' THEN 'NodeNotReady'
            WHEN resource_type = 'POD' AND status = 'ERROR' THEN 'BackOff'
            WHEN resource_type = 'POD' AND status = 'WARNING' THEN 'FailedScheduling'
            WHEN resource_type = 'NAMESPACE' THEN 'NamespaceDegraded'
            ELSE 'ResourceDegraded' END,
       CASE WHEN resource_type = 'NODE' AND status = 'ERROR' THEN 'Kubelet is not reporting Ready.'
            WHEN resource_type = 'POD' AND status = 'ERROR' THEN 'Container restart or termination condition detected.'
            WHEN resource_type = 'POD' AND status = 'WARNING' THEN 'Pod is waiting for scheduling or readiness.'
            ELSE 'Mock resource is in a non-normal state.' END,
       updated_at, updated_at, 1
FROM (
    SELECT 'CLUSTER'::varchar AS resource_type, id AS resource_id, status, updated_at FROM infrastructure_cluster
    UNION ALL
    SELECT 'NODE', id, status, updated_at FROM infrastructure_node
    UNION ALL
    SELECT 'NAMESPACE', id, status, updated_at FROM infrastructure_namespace
    UNION ALL
    SELECT 'POD', id, status, updated_at FROM infrastructure_pod
) resources
WHERE status <> 'NORMAL';
