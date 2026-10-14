\echo 'Representative read queries: selected columns are reduced; endpoint timing is measured separately.'
\echo 'Selective name search with ancestor joins'
EXPLAIN (ANALYZE, BUFFERS)
SELECT pod.id, pod.name, pod.status, namespace.name, node.name, cluster.name
FROM infrastructure_pod pod
JOIN infrastructure_namespace namespace ON namespace.id = pod.namespace_id
JOIN infrastructure_node node ON node.id = namespace.node_id
JOIN infrastructure_cluster cluster ON cluster.id = node.cluster_id
WHERE pod.name ILIKE '%worker-pod-000001%' ESCAPE '!'
ORDER BY pod.id LIMIT 101;

\echo 'Status-filtered first page'
EXPLAIN (ANALYZE, BUFFERS)
SELECT pod.id, pod.name, pod.status, namespace.name, node.name, cluster.name
FROM infrastructure_pod pod
JOIN infrastructure_namespace namespace ON namespace.id = pod.namespace_id
JOIN infrastructure_node node ON node.id = namespace.node_id
JOIN infrastructure_cluster cluster ON cluster.id = node.cluster_id
WHERE pod.status = 'ERROR'
ORDER BY pod.id LIMIT 101;

\echo 'Keyset page near the end of 100k rows'
EXPLAIN (ANALYZE, BUFFERS)
SELECT pod.id, pod.name, pod.status, namespace.name, node.name, cluster.name
FROM infrastructure_pod pod
JOIN infrastructure_namespace namespace ON namespace.id = pod.namespace_id
JOIN infrastructure_node node ON node.id = namespace.node_id
JOIN infrastructure_cluster cluster ON cluster.id = node.cluster_id
WHERE pod.id > 99000
ORDER BY pod.id LIMIT 101;

\echo 'Short missing substring: a trigram index may not help'
EXPLAIN (ANALYZE, BUFFERS)
SELECT pod.id, pod.name FROM infrastructure_pod pod
WHERE pod.name ILIKE '%z%' ESCAPE '!'
ORDER BY pod.id LIMIT 101;

\echo 'Exact count is not repeated by foreground polling'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM infrastructure_pod;
