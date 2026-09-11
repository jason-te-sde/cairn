package io.cairn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The transition relation, enumerated rather than sampled.
 *
 * <p>A test that checks the transitions somebody remembered is a test that agrees with the
 * implementation by construction. These walk the whole table, so adding a stage without deciding
 * what it connects to fails here.
 */
class StageTest {

    @Test
    void theWholeRelationIsWhatItSaysItIs() {
        assertEquals(Set.of(Stage.PRODUCTION, Stage.ARCHIVED), Stage.STAGING.allowedTargets());
        assertEquals(Set.of(Stage.ARCHIVED, Stage.DEPRECATED), Stage.PRODUCTION.allowedTargets());
        assertEquals(Set.of(Stage.STAGING, Stage.DEPRECATED), Stage.ARCHIVED.allowedTargets());
        assertEquals(Set.of(), Stage.DEPRECATED.allowedTargets());
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void everyStageAcceptsItselfAsANoOp(Stage stage) {
        assertTrue(stage.canTransitionTo(stage));
        assertFalse(stage.allowedTargets().contains(stage),
                "a self-transition is a no-op, not a move, so it must not be in the relation");
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void nothingLeavesDeprecation(Stage target) {
        if (target == Stage.DEPRECATED) {
            return;
        }
        assertFalse(Stage.DEPRECATED.canTransitionTo(target),
                "DEPRECATED must be terminal, but it allows " + target);
    }

    @Test
    void productionCannotBeReachedWithoutPassingThroughStaging() {
        for (Stage from : Stage.values()) {
            if (from == Stage.STAGING || from == Stage.PRODUCTION) {
                continue;
            }
            assertFalse(from.canTransitionTo(Stage.PRODUCTION),
                    from + " reaches PRODUCTION directly, which skips the release decision");
        }
    }

    @Test
    void theRelationIsNotSymmetric() {
        // Stated as a test because it is the property a reader is most likely to assume wrongly:
        // a rollback is stage-then-promote, two commands, not one reversal.
        assertTrue(Stage.STAGING.canTransitionTo(Stage.PRODUCTION));
        assertFalse(Stage.PRODUCTION.canTransitionTo(Stage.STAGING));
    }
}
