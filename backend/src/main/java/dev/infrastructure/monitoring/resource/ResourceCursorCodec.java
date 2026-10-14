package dev.infrastructure.monitoring.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Public navigation state, not an authentication or authorization token. */
public final class ResourceCursorCodec {
    public record Position(int depth, long id) { }

    public String encode(ResourceQuery query, Position position) {
        validatePosition(query, position);
        try {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            data.writeByte(1);
            data.writeByte(query.type() == null ? -1 : query.type().ordinal());
            data.writeLong(query.parentId() == null ? 0 : query.parentId());
            data.writeUTF(query.query());
            data.writeByte(query.status() == null ? -1 : query.status().ordinal());
            data.writeByte(position.depth());
            data.writeLong(position.id());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("커서를 생성할 수 없습니다.", exception);
        }
    }

    public Position decode(ResourceQuery query) {
        if (query.cursor().isEmpty()) return null;
        try {
            var data = new DataInputStream(new ByteArrayInputStream(
                    Base64.getUrlDecoder().decode(query.cursor())));
            int version = data.readUnsignedByte();
            int type = data.readByte();
            long parent = data.readLong();
            String term = data.readUTF();
            int status = data.readByte();
            var position = new Position(data.readUnsignedByte(), data.readLong());
            if (version != 1 || type != (query.type() == null ? -1 : query.type().ordinal())
                    || parent != (query.parentId() == null ? 0 : query.parentId())
                    || !term.equals(query.query())
                    || status != (query.status() == null ? -1 : query.status().ordinal())
                    || data.available() != 0) {
                throw invalidCursor();
            }
            validatePosition(query, position);
            return position;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static void validatePosition(ResourceQuery query, Position position) {
        if (position == null || position.depth() < 0 || position.depth() > 3 || position.id() <= 0
                || (query.type() != null && position.depth() != query.type().depth())) {
            throw invalidCursor();
        }
    }

    private static ResourceRequestException invalidCursor() {
        return ResourceRequestException.invalid("cursor", "유효하지 않거나 조회 조건과 다른 커서입니다.");
    }
}
