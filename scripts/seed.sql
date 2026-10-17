-- Local mock fixtures only. Preserve runtime fields; align appended fixture parents on upgrade.
BEGIN;
INSERT INTO infrastructure_cluster(id, name, status, updated_at)
SELECT n, 'cluster-' || lpad(n::text, 2, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second'
FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_node(id, cluster_id, name, status, updated_at, cpu_usage, memory_usage,
                                cpu_capacity_cores, memory_capacity_mib)
SELECT n, ((n-1)/10)+1, 'node-' || lpad(n::text, 3, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second', (n*17)%101, (n*23)%101,
       8 * (1 + n % 4), 16384 * (1 + n % 4)
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
