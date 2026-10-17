package dev.infrastructure.monitoring.resource;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockDiagnosticsTest {
    @Test void normalNodeIsReadyWithoutPressure() {
        var result = MockDiagnostics.node(ResourceStatus.NORMAL, 2, 16, 32768L);
        assertEquals(true, result.get("ready"));
        assertEquals(false, result.get("memoryPressure"));
        assertEquals(false, result.get("diskPressure"));
        assertEquals(32768L, result.get("memoryCapacityMiB"));
    }

    @Test void warningNodesHaveAConsistentPressureScenarioWhileRemainingReady() {
        var memory = MockDiagnostics.node(ResourceStatus.WARNING, 2, 16, 32768);
        var disk = MockDiagnostics.node(ResourceStatus.WARNING, 1, 8, 16384);
        assertEquals(true, memory.get("ready"));
        assertEquals(true, memory.get("memoryPressure"));
        assertEquals(false, memory.get("diskPressure"));
        assertEquals(true, disk.get("diskPressure"));
    }

    @Test void errorNodeIsNotReady() {
        var result = MockDiagnostics.node(ResourceStatus.ERROR, 1, 8, 16384);
        assertEquals(false, result.get("ready"));
        assertEquals("KubeletNotReady", result.get("conditionReason"));
    }

    @Test void normalPodHasAllContainersReadyAndNoCurrentFailure() {
        var result = pod(ResourceStatus.NORMAL, 1);
        assertEquals("Running", result.get("phase"));
        assertEquals(2, result.get("readyContainerCount"));
        assertNull(result.get("failureReason"));
        assertNull(result.get("lastTerminationReason"));
    }

    @Test void warningsDistinguishImagePullFromReadinessFailure() {
        var imagePull = pod(ResourceStatus.WARNING, 2);
        var readiness = pod(ResourceStatus.WARNING, 1);
        assertEquals("Pending", imagePull.get("phase"));
        assertEquals("Waiting", imagePull.get("containerState"));
        assertEquals("ImagePullBackOff", imagePull.get("failureReason"));
        assertEquals(0, imagePull.get("readyContainerCount"));
        assertEquals("Running", readiness.get("phase"));
        assertEquals(1, readiness.get("readyContainerCount"));
        assertEquals("ReadinessProbeFailed", readiness.get("failureReason"));
    }

    @Test void crashLoopIsAContainerReasonNotAPodPhase() {
        var crash = pod(ResourceStatus.ERROR, 2);
        var failed = pod(ResourceStatus.ERROR, 1);
        assertEquals("Running", crash.get("phase"));
        assertEquals("CrashLoopBackOff", crash.get("failureReason"));
        assertEquals("OOMKilled", crash.get("lastTerminationReason"));
        assertEquals("Failed", failed.get("phase"));
        assertEquals("Terminated", failed.get("containerState"));
    }

    @Test void everyScenarioHasAValidReadyCountAndPreservesMetadata() {
        for (var status : ResourceStatus.values()) {
            for (long id : new long[]{1, 2}) {
                for (int total : new int[]{1, 2, 4}) {
                    var data = MockDiagnostics.pod(status, id, total, "safe-image", "Deployment", "mock-worker");
                    int ready = (int) data.get("readyContainerCount");
                    assertTrue(ready >= 0 && ready <= total);
                    assertEquals("safe-image", data.get("image"));
                    assertEquals("mock-worker", data.get("workloadName"));
                }
            }
        }
    }

    private java.util.Map<String, Object> pod(ResourceStatus status, long id) {
        return MockDiagnostics.pod(status, id, 2, "safe-image", "Deployment", "mock-worker");
    }
}
