-- Additive mock metadata only: preserve identities, relationships, state and history.
ALTER TABLE infrastructure_node
    ADD COLUMN cpu_capacity_cores integer NOT NULL DEFAULT 8 CHECK (cpu_capacity_cores > 0),
    ADD COLUMN memory_capacity_mib bigint NOT NULL DEFAULT 16384 CHECK (memory_capacity_mib > 0);
UPDATE infrastructure_node
SET cpu_capacity_cores = 8 * (1 + id % 4),
    memory_capacity_mib = 16384 * (1 + id % 4);

ALTER TABLE infrastructure_pod
    ADD COLUMN container_count integer NOT NULL DEFAULT 1 CHECK (container_count BETWEEN 1 AND 4),
    ADD COLUMN image varchar(240) NOT NULL DEFAULT 'registry.example.invalid/mock/application:1.0.0',
    ADD COLUMN workload_kind varchar(32) NOT NULL DEFAULT 'Deployment'
        CHECK (workload_kind IN ('Deployment', 'StatefulSet', 'Job')),
    ADD COLUMN workload_name varchar(160) NOT NULL DEFAULT 'mock-application';
UPDATE infrastructure_pod
SET container_count = CASE WHEN id % 3 = 0 THEN 2 ELSE 1 END,
    image = 'registry.example.invalid/mock/'
        || (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(id % 5) + 1] || ':1.0.0',
    workload_kind = CASE WHEN id % 5 = 4 THEN 'StatefulSet' WHEN id % 5 = 2 THEN 'Job' ELSE 'Deployment' END,
    workload_name = 'mock-' || (ARRAY['api', 'worker', 'scheduler', 'gateway', 'database'])[(id % 5) + 1]
        || '-' || lpad((((id - 1) / 1000) % 100 + 1)::text, 2, '0');
