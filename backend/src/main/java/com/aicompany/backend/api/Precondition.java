package com.aicompany.backend.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The state a caller declares it was looking at when it decided to write.
 *
 * <p>This is the detector of ADR-009, and it is the only one. It exists because
 * the obvious alternative does not work: JPA's own optimistic check compares the
 * version <em>loaded into the persistence context</em> with the one in the
 * database at flush, and on every write path here the entity is loaded under
 * {@code PESSIMISTIC_WRITE}. Under READ COMMITTED a {@code SELECT … FOR UPDATE}
 * that waited re-reads the latest committed row, so it loads N+1 and writes
 * N+1 to N+2: the two agree and {@code OptimisticLockException} never arrives.
 * The lock has made that check trivially satisfiable -- it is comparing the wrong
 * two things.
 *
 * <p>So the comparison that matters is between what the <em>client</em> last saw
 * and what is there now, and it has exactly one correct place: inside the
 * transaction, after the exclusive lock on the target row, before any domain rule
 * reads it (rule P1). That is why this is a parameter of a service method and not
 * a filter, an interceptor, or thread state -- none of those hold the lock.
 *
 * <p>Immutable, and with one useful operation, so that a call site cannot read
 * the expected version, decide something else with it, and skip the check.
 */
public final class Precondition {

    private static final String WILDCARD = "*";

    /**
     * The versions the caller says it has seen. More than one is legal: RFC 9110
     * allows a list, and enumerating states one has actually observed is an
     * assertion. {@code *} is not on this list and never will be -- see
     * {@link #fromHeader}.
     */
    private final Set<Long> acceptedVersions;

    private Precondition(Set<Long> acceptedVersions) {
        this.acceptedVersions = acceptedVersions;
    }

    /**
     * Interprets an {@code If-Match} header value.
     *
     * <p>Rule P0: absent is not "no opinion", it is a refusal to state one, and it
     * gets a 428 rather than a silent pass. A 400 would say only that the caller
     * was wrong; 428 says what to do about it -- read the resource, take its ETag,
     * send the request again.
     *
     * <p>{@code *} is rejected, which is a deliberate divergence from RFC 9110 and
     * is declared as such in ADR-009 §5.3. The wildcard means "provided the
     * resource exists", so it asserts nothing at all about the state the caller
     * saw; honouring it would hand every client a documented way around the whole
     * protocol, and P0 would become a formality.
     *
     * <p>A weak entity-tag is rejected too, and not out of pedantry: RFC 9110
     * requires {@code If-Match} to use the strong comparison function, so a weak
     * tag can never match. Accepting the syntax and then always failing it would
     * answer 412 -- "you are out of date" -- to a caller who is not out of date
     * but is calling the API wrongly.
     */
    public static Precondition fromHeader(String ifMatch) {

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }

        String value = ifMatch.trim();

        if (WILDCARD.equals(value)) {
            throw new InvalidPreconditionException(
                    "If-Match: * asserts nothing about the state you saw; send the ETag you last read");
        }

        Set<Long> versions = new LinkedHashSet<>();

        // Splitting on the comma is safe for the tags this API issues -- they hold
        // digits only. A tag that did contain one would fail the check below and be
        // reported as unreadable, which is the right answer for a value this API
        // cannot have produced.
        for (String candidate : value.split(",")) {

            String tag = candidate.trim();

            if (tag.length() < 3 || tag.charAt(0) != '"' || tag.charAt(tag.length() - 1) != '"') {
                throw new InvalidPreconditionException(
                        "'" + tag + "' is not an entity-tag this API can evaluate");
            }

            versions.add(parseVersion(tag.substring(1, tag.length() - 1), tag));
        }

        return new Precondition(versions);
    }

    /**
     * Rule P1 and P2. Throws unless the row is still at one of the versions the
     * caller declared.
     *
     * <p>Called immediately after the lock and before everything else -- including
     * any idempotent short circuit. A request that would change nothing is still a
     * write request, and the fact that it happens to be a no-op against the
     * <em>new</em> state is a coincidence, not a confirmation that the caller knew
     * what it was doing.
     */
    public void requireSatisfiedBy(long currentVersion) {
        if (!acceptedVersions.contains(currentVersion)) {
            throw new PreconditionFailedException();
        }
    }

    private static long parseVersion(String digits, String originalTag) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new InvalidPreconditionException(
                    "'" + originalTag + "' is not an entity-tag this API can evaluate");
        }
    }
}
