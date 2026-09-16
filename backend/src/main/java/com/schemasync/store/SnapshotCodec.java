package com.schemasync.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemasync.core.model.SchemaSnapshot;
import org.springframework.stereotype.Component;

/**
 * Serialises snapshots to and from the JSONB column.
 *
 * <p>Jackson handles records natively, so this is thin. Note that Jackson's field ordering is
 * deliberately NOT relied upon for anything semantic -- the content hash is computed by
 * {@code SnapshotHasher} from a hand-rolled canonical form, precisely so that a Jackson
 * configuration change can never silently alter a hash.
 */
@Component
public class SnapshotCodec {

    private final ObjectMapper mapper;

    public SnapshotCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(SchemaSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise snapshot", e);
        }
    }

    public SchemaSnapshot fromJson(String json) {
        try {
            return mapper.readValue(json, SchemaSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot deserialise snapshot", e);
        }
    }
}
