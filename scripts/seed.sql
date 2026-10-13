-- Local mock data only. Idempotent inserts do not delete or overwrite existing rows.
BEGIN;
INSERT INTO infrastructure_cluster(id, name, status, updated_at)
SELECT n, 'cluster-' || lpad(n::text, 2, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second'
FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_node(id, cluster_id, name, status, updated_at, cpu_usage, memory_usage)
SELECT n, ((n-1)/10)+1, 'node-' || lpad(n::text, 3, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second', (n*17)%101, (n*23)%101
FROM generate_series(1, 200) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_namespace(id, node_id, name, status, updated_at)
SELECT n, ((n-1)/10)+1, 'namespace-' || lpad(n::text, 4, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - n * interval '1 second'
FROM generate_series(1, 2000) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO infrastructure_pod(id, namespace_id, name, status, updated_at, restart_count)
SELECT n, ((n-1)/50)+1,
       (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(n%5)+1] || '-pod-' || lpad(n::text, 6, '0'),
       CASE WHEN n % 10 = 0 THEN 'ERROR' WHEN n % 4 = 0 THEN 'WARNING' ELSE 'NORMAL' END,
       current_timestamp - (n%3600) * interval '1 second', n%9
FROM generate_series(1, 100000) AS n
ON CONFLICT (id) DO NOTHING;
COMMIT;
ANALYZE infrastructure_cluster;
ANALYZE infrastructure_node;
ANALYZE infrastructure_namespace;
ANALYZE infrastructure_pod;
SELECT 'Cluster' AS resource, count(*) AS rows FROM infrastructure_cluster
UNION ALL SELECT 'Node', count(*) FROM infrastructure_node
UNION ALL SELECT 'Namespace', count(*) FROM infrastructure_namespace
UNION ALL SELECT 'Pod', count(*) FROM infrastructure_pod;
