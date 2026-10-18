-- Extend GPU coverage without overwriting existing devices or resource state.
INSERT INTO infrastructure_node_gpu
    (node_id, device_name, model, memory_mib, utilization_percent,
     temperature_celsius, allocated_pods, updated_at)
SELECT n.id, 'gpu-' || device_number,
       'NVIDIA A100 40GB', 40960,
       mod(n.id * 17 + device_number * 13, 101)::numeric(5,2),
       (35 + mod(n.id * 3 + device_number * 7, 51))::numeric(5,2),
       mod(n.id + device_number, 5), n.updated_at
FROM infrastructure_node n
CROSS JOIN generate_series(1, 2) AS device_number
WHERE n.id % 2 = 0
ON CONFLICT (node_id, device_name) DO NOTHING;
