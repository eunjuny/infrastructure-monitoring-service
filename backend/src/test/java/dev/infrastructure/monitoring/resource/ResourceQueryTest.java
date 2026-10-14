package dev.infrastructure.monitoring.resource;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ResourceQueryTest {
    private final ResourceCursorCodec codec = new ResourceCursorCodec();

    @Test void acceptsLowercaseTypeAndStatus() {
        var q = ResourceQuery.list(" pod ", 1L, " worker ", " normal ", 50, null);
        assertEquals(ResourceType.POD, q.type());
        assertEquals(ResourceStatus.NORMAL, q.status());
        assertEquals("worker", q.query());
    }

    @Test void rejectsUnknownTypeAndStatus() {
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("DROP TABLE", null, "", null, 50, null));
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("POD", null, "", "RUNNING", 50, null));
    }

    @Test void rejectsInvalidPageSizes() {
        for (int n : new int[]{-1, 0, 101, Integer.MAX_VALUE}) {
            assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("POD", null, "", null, n, null));
        }
    }

    @Test void acceptsBoundaryPageSizes() {
        assertEquals(1, ResourceQuery.list("POD", null, "", null, 1, null).limit());
        assertEquals(100, ResourceQuery.list("POD", null, "", null, 100, null).limit());
    }

    @Test void validatesParentScopeAndIds() {
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("CLUSTER", 1L, "", null, 50, null));
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("NODE", 0L, "", null, 50, null));
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.requirePositiveId(-1));
        assertDoesNotThrow(() -> ResourceQuery.requirePositiveId(Long.MAX_VALUE));
    }

    @Test void requiresTermForGlobalSearch() {
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.search(null, " ", null, 50, null));
        assertNull(ResourceQuery.search(null, "pod", null, 50, null).type());
    }

    @Test void rejectsLongOrControlCharacterQueries() {
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.search(null, "x".repeat(161), null, 50, null));
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.search(null, "a\nb", null, 50, null));
        assertDoesNotThrow(() -> ResourceQuery.search(null, "x".repeat(160), null, 50, null));
    }

    @Test void escapesWildcardCharactersAsLiterals() {
        assertEquals("%a!!!%!_%", ResourceQuery.search(null, "a!%_", null, 50, null).likePattern());
        assertEquals("%a\\b%", ResourceQuery.search(null, "a\\b", null, 50, null).likePattern());
    }

    @Test void roundTripsCursorAndAllowsChangingPageSize() {
        var original = ResourceQuery.list("POD", 2L, "쿠버네티스", "NORMAL", 50, null);
        var position = new ResourceCursorCodec.Position(3, Long.MAX_VALUE);
        String token = codec.encode(original, position);
        assertEquals(position, codec.decode(ResourceQuery.list("POD", 2L, "쿠버네티스", "NORMAL", 100, token)));
        assertNull(codec.decode(original));
    }

    @Test void rejectsCursorFromDifferentQueryScope() {
        var original = ResourceQuery.list("POD", 2L, "worker", "NORMAL", 50, null);
        String token = codec.encode(original, new ResourceCursorCodec.Position(3, 50));
        for (var other : new ResourceQuery[]{
                ResourceQuery.list("POD", 3L, "worker", "NORMAL", 50, token),
                ResourceQuery.list("POD", 2L, "api", "NORMAL", 50, token),
                ResourceQuery.list("POD", 2L, "worker", "ERROR", 50, token),
                ResourceQuery.list("NODE", 2L, "worker", "NORMAL", 50, token)}) {
            assertThrows(ResourceRequestException.class, () -> codec.decode(other));
        }
    }

    @Test void rejectsMalformedTruncatedAndTrailingBytes() {
        var original = ResourceQuery.list("POD", null, "", null, 50, null);
        String valid = codec.encode(original, new ResourceCursorCodec.Position(3, 50));
        byte[] bytes = Base64.getUrlDecoder().decode(valid);
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 1);
        String[] tokens = {"not%base64", "AQ", Base64.getUrlEncoder().encodeToString(extra)};
        for (String token : tokens) {
            assertThrows(ResourceRequestException.class, () -> codec.decode(
                    ResourceQuery.list("POD", null, "", null, 50, token)));
        }
    }

    @Test void rejectsUnsupportedCursorVersionAndInvalidPosition() {
        var q = ResourceQuery.list("POD", null, "", null, 50, null);
        byte[] data = Base64.getUrlDecoder().decode(codec.encode(q, new ResourceCursorCodec.Position(3, 50)));
        data[0] = 2;
        String token = Base64.getUrlEncoder().encodeToString(data);
        assertThrows(ResourceRequestException.class, () -> codec.decode(ResourceQuery.list("POD", null, "", null, 50, token)));
        assertThrows(ResourceRequestException.class, () -> codec.encode(q, new ResourceCursorCodec.Position(2, 50)));
        assertThrows(ResourceRequestException.class, () -> codec.encode(q, new ResourceCursorCodec.Position(3, 0)));
    }

    @Test void rejectsOversizedCursor() {
        assertThrows(ResourceRequestException.class, () -> ResourceQuery.list("POD", null, "", null, 50, "a".repeat(1201)));
    }

    @Test void traversesHierarchyTypes() {
        assertEquals(ResourceType.NODE, ResourceType.CLUSTER.child());
        assertEquals(ResourceType.POD, ResourceType.NAMESPACE.child());
        assertEquals(ResourceType.NAMESPACE, ResourceType.POD.parent());
        assertNull(ResourceType.CLUSTER.parent());
        assertNull(ResourceType.POD.child());
    }
}
