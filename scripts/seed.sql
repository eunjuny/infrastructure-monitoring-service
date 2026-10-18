-- Local mock fixtures only. Preserve runtime fields; align appended fixture parents on upgrade.
BEGIN;
INSERT INTO infrastructure_cluster(id, name, status, updated_at)
SELECT n, 'cluster-' || lpad(n::text, 2, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second'
FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_node(id, cluster_id, name, status, updated_at, cpu_usage, memory_usage,
                                cpu_capacity_cores, memory_capacity_mib, cpu_allocatable_cores,
                                cpu_requested_cores, memory_allocatable_mib, memory_requested_mib)
SELECT n, ((n-1)/10)+1, 'node-' || lpad(n::text, 3, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second', (n*17)%101, (n*23)%101,
       8 * (1 + n % 4), 16384 * (1 + n % 4),
       7.2 * (1 + n % 4), 8 * (1 + n % 4) * (0.35 + (n % 5) * 0.10),
       (14745 * (1 + n % 4)), (16384 * (1 + n % 4) * (0.35 + (n % 5) * 0.10))::bigint
FROM generate_series(1, 200) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_namespace(id, node_id, name, status, updated_at)
SELECT n, ((n-1)/10)+1, 'namespace-' || lpad(n::text, 4, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second'
FROM generate_series(1, 2000) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_pod(id, namespace_id, name, status, updated_at, restart_count,
                               container_count, image, workload_kind, workload_name)
-- Keep the original 100,000 IDs/parents; append 50 Pods to each of the first 1,000 namespaces.
SELECT n, CASE WHEN n <= 100000 THEN ((n-1)/50)+1 ELSE ((n-100001)/50)+1 END,
       (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(n%5)+1] || '-pod-' || lpad(n::text, 6, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - (n%3600) * interval '1 second', n%9,
       CASE WHEN n % 3 = 0 THEN 2 ELSE 1 END,
       'registry.example.invalid/mock/' || (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(n%5)+1] || ':1.0.0',
       CASE WHEN n % 5 = 4 THEN 'StatefulSet' WHEN n % 5 = 2 THEN 'Job' ELSE 'Deployment' END,
       'mock-' || (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(n%5)+1]
           || '-' || lpad((((n - 1) / 1000) % 100 + 1)::text, 2, '0')
FROM generate_series(1, 150000) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_pod_container
    (pod_id, name, image, state, ready, restart_count, cpu_request_millicores,
     cpu_usage_millicores, memory_request_mib, memory_usage_mib)
SELECT p.id, 'application', p.image,
       CASE WHEN p.status = 'ERROR' AND p.id % 2 = 0 THEN 'WAITING'
            WHEN p.status = 'ERROR' AND p.id % 2 = 1 THEN 'TERMINATED'
            WHEN p.status = 'WARNING' THEN 'WAITING' ELSE 'RUNNING' END,
       p.status = 'NORMAL', p.restart_count, 250 + (p.id % 4) * 100,
       50 + (p.id % 4) * 25, 256 + (p.id % 4) * 128, 96 + (p.id % 4) * 48
FROM infrastructure_pod p
ON CONFLICT (pod_id, name) DO NOTHING;

INSERT INTO infrastructure_pod_container
    (pod_id, name, image, state, ready, restart_count, cpu_request_millicores,
     cpu_usage_millicores, memory_request_mib, memory_usage_mib)
SELECT p.id, 'metrics-sidecar', 'registry.example.invalid/mock/metrics-sidecar:1.0.0',
       CASE WHEN p.status = 'NORMAL' THEN 'RUNNING' ELSE 'WAITING' END,
       p.status = 'NORMAL', CASE WHEN p.status = 'WARNING' THEN 1 ELSE 0 END,
       100, 25 + (p.id % 3) * 10, 128, 48 + (p.id % 3) * 16
FROM infrastructure_pod p WHERE p.container_count > 1
ON CONFLICT (pod_id, name) DO NOTHING;

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
WHERE n.id % 2 = 0
ON CONFLICT (node_id, device_name) DO NOTHING;

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
            ELSE 'Resource is in a non-normal state.' END,
       updated_at, updated_at, 1
FROM (
    SELECT 'CLUSTER'::varchar AS resource_type, id AS resource_id, status, updated_at FROM infrastructure_cluster
    UNION ALL SELECT 'NODE', id, status, updated_at FROM infrastructure_node
    UNION ALL SELECT 'NAMESPACE', id, status, updated_at FROM infrastructure_namespace
    UNION ALL SELECT 'POD', id, status, updated_at FROM infrastructure_pod
) resources
WHERE status <> 'NORMAL'
ON CONFLICT (resource_type, resource_id) DO NOTHING;

-- Upgrade the previous 75k/75k fixture distribution without deleting rows or changing state/history.
-- Only recognizable appended seed rows at their old parents can move. Custom rows are untouched.
UPDATE infrastructure_pod p
SET namespace_id = ((p.id-100001)/50)+1
WHERE p.id BETWEEN 100001 AND 150000
  AND p.namespace_id = ((p.id-100001)/25)+1
  AND p.namespace_id IS DISTINCT FROM ((p.id-100001)/50)+1
  AND p.name = (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(p.id%5)+1]
      || '-pod-' || lpad(p.id::text, 6, '0');
COMMIT;
ANALYZE infrastructure_cluster;
ANALYZE infrastructure_node;
ANALYZE infrastructure_namespace;
ANALYZE infrastructure_pod;
SELECT 'Cluster' AS resource, count(*) AS rows FROM infrastructure_cluster
UNION ALL SELECT 'Node', count(*) FROM infrastructure_node
UNION ALL SELECT 'Namespace', count(*) FROM infrastructure_namespace
UNION ALL SELECT 'Pod', count(*) FROM infrastructure_pod;
