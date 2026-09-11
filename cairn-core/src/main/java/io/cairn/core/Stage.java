package io.cairn.core;

import java.util.Set;

/**
 * Where a version stands in its life, and which moves are legal from here.
 *
 * <p>The transition relation is data rather than a chain of {@code if} statements so that the
 * illegal moves are as visible as the legal ones, and so a test can enumerate the whole relation
 * instead of the cases somebody remembered.
 *
 * <pre>
 *   STAGING ──────► PRODUCTION ──────► DEPRECATED
 *      │  ▲              │                  ▲
 *      │  │              ▼                  │
 *      └──┴────────── ARCHIVED ─────────────┘
 * </pre>
 *
 * <p>Two properties are worth stating because they are the ones the kernel enforces. Promotion to
 * {@link #PRODUCTION} demotes the incumbent in the *same* transition, so there is never an instant
 * with two production versions — not even one that a reader would have to be unlucky to observe.
 * And {@link #DEPRECATED} is terminal, so "we have stopped supporting this" cannot be quietly
 * undone; re-releasing means publishing a new version, which leaves a record.
 */
public enum Stage {

    /** Published, addressable, and not what production traffic gets. */
    STAGING,

    /** The one version of this model that serving infrastructure should load. */
    PRODUCTION,

    /** Kept and addressable, but no longer a candidate. */
    ARCHIVED,

    /** End of life. Terminal: nothing transitions out of it. */
    DEPRECATED;

    /**
     * The stages reachable from this one in a single command.
     *
     * <p>Computed rather than stored in a field because an enum constant cannot reference another
     * constant in its own initializer, and a static map initialised afterwards would be a second
     * place for the relation to live.
     */
    public Set<Stage> allowedTargets() {
        return switch (this) {
            // A staged version either becomes the live one or is set aside. It cannot be
            // deprecated directly: deprecation is a statement about something that was in use.
            case STAGING -> Set.of(PRODUCTION, ARCHIVED);

            // Coming out of production is either "superseded" or "end of life". Going straight
            // back to STAGING is not offered, because the thing serving traffic is not a
            // candidate, and the transition that supersedes it is the promotion of its successor.
            case PRODUCTION -> Set.of(ARCHIVED, DEPRECATED);

            // An archived version can be re-staged, which is the rollback path: stage the old
            // version, then promote it. Promoting straight from ARCHIVED is deliberately absent so
            // that a rollback passes through the same review point as any other release.
            case ARCHIVED -> Set.of(STAGING, DEPRECATED);

            case DEPRECATED -> Set.of();
        };
    }

    /** Whether {@code target} may be reached from this stage. A no-op move counts as legal. */
    public boolean canTransitionTo(Stage target) {
        return this == target || allowedTargets().contains(target);
    }
}
