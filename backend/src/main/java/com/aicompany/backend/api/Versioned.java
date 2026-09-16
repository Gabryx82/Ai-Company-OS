package com.aicompany.backend.api;

/**
 * A response body together with the version of the row it was built from.
 *
 * <p>Exists because the version travels in the {@code ETag} header and
 * <strong>not</strong> in the JSON body. Putting it in the body would be the same
 * fact represented twice in two channels that can drift -- the argument ADR-004
 * used against a {@code deleted_at} alongside {@code status}, and ADR-006 §1
 * against a materialised cascade. So it needs a way to reach the controller that
 * is not the representation.
 *
 * <p>Only the task service needs this. A project or an agent is handed to its
 * controller as an entity and mapped after the commit, so the version is simply
 * read from it. A task is mapped inside the transaction, because
 * {@code Task.project} is lazy and {@code open-in-view} is off, so the version has
 * to be carried out alongside the record.
 */
public record Versioned<T>(T body, long version) {

    public String etag() {
        return ETags.of(version);
    }
}
