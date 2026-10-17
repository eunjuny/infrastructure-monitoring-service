package dev.infrastructure.monitoring.resource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic mock scenarios, not Kubernetes measurements or inferred incident causes. */
final class MockDiagnostics {
    private MockDiagnostics() { }

    static Map<String, Object> node(ResourceStatus status, long id, Number cores, Number memoryMiB) {
        boolean memoryPressure = status == ResourceStatus.WARNING && id % 2 == 0;
        boolean diskPressure = status == ResourceStatus.WARNING && id % 2 != 0;
        return Map.of("ready", status != ResourceStatus.ERROR,
                "memoryPressure", memoryPressure, "diskPressure", diskPressure,
                "cpuCapacityCores", cores.intValue(), "memoryCapacityMiB", memoryMiB.longValue(),
                "conditionReason", status == ResourceStatus.ERROR ? "KubeletNotReady"
                        : memoryPressure ? "MemoryPressure" : diskPressure ? "DiskPressure" : "KubeletReady");
    }

    static Map<String, Object> pod(ResourceStatus status, long id, int containers,
                                   String image, String workloadKind, String workloadName) {
        boolean healthy = status == ResourceStatus.NORMAL;
        boolean pending = status == ResourceStatus.WARNING && id % 2 == 0;
        boolean failed = status == ResourceStatus.ERROR && id % 2 != 0;
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", pending ? "Pending" : failed ? "Failed" : "Running");
        result.put("containerCount", containers);
        result.put("readyContainerCount", healthy ? containers
                : status == ResourceStatus.WARNING && !pending ? containers - 1 : 0);
        result.put("containerState", pending || status == ResourceStatus.ERROR && !failed ? "Waiting"
                : failed ? "Terminated" : "Running");
        result.put("failureReason", healthy ? null : pending ? "ImagePullBackOff"
                : status == ResourceStatus.WARNING ? "ReadinessProbeFailed" : failed ? "Error" : "CrashLoopBackOff");
        result.put("lastTerminationReason", status != ResourceStatus.ERROR ? null : failed ? "Error" : "OOMKilled");
        result.put("image", image);
        result.put("workloadKind", workloadKind);
        result.put("workloadName", workloadName);
        return Collections.unmodifiableMap(result);
    }
}
