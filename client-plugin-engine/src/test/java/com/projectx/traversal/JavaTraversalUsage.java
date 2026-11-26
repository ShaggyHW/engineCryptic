package com.projectx.traversal;

import com.projectx.script.JavaScript;
import com.projectx.script.JavaState;
import com.projectx.script.JavaStateMachineScript;
import com.projectx.script.Wait;
import com.projectx.script.api.Lodestone;
import com.projectx.util.Area;

/** Compiles only if a Java script can build, run and route a traversal the way the Java API documents. */
public final class JavaTraversalUsage {

    static boolean insideBankArea(int x, int y) {
        return new Area(3180, 3430, 3190, 3440).inside(x, y);
    }

    static TraversalNodeList toBank() {
        return JavaTraversal.nodes(b -> {
            b.useLodestone(Lodestone.VARROCK, null);
            b.webWalk(3185, 3436, 0);
            b.path(3185, 3436, 3190, 3440, 0);
            b.walkExact(3190, 3440, 0);
            b.dive(3195, 3440, 0, true);
            b.surge(3200, 3440, 0, true, () -> true);
            b.chebychevPath(3200, 3440, 0, new int[]{3205, 3210}, new int[]{3440, 3445});
            b.interactObj("Bank booth", "Bank", 30, 3210, 3445, 0);
            b.interactObj("Bank booth", "Bank", 30, () -> true);
        });
    }

    public static final class Walker extends JavaStateMachineScript<Walker> {
        boolean arrived;
        boolean attacked;

        @Override
        public JavaState<Walker> getStartState() {
            return Phase.WALK;
        }
    }

    enum Phase implements JavaState<Walker> {
        WALK {
            @Override
            public JavaState<Walker> checkNext(Walker s) {
                return new JavaTraversal<>(BANKING, toBank(), () -> s.arrived, Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS)
                        .interruptWhen("under attack", () -> s.attacked, FLEE)
                        .pollOnTick(true);
            }

            @Override
            public Wait onLoop(Walker s) {
                return Wait.ms(0);
            }
        },
        BANKING {
            @Override
            public JavaState<Walker> checkNext(Walker s) {
                return null;
            }

            @Override
            public Wait onLoop(Walker s) {
                return Wait.ms(600);
            }
        },
        FLEE {
            @Override
            public JavaState<Walker> checkNext(Walker s) {
                return null;
            }

            @Override
            public Wait onLoop(Walker s) {
                return Wait.ms(600);
            }
        }
    }

    public static final class Inline extends JavaScript {
        TraversalOutcome last;

        @Override
        public Wait onLoop() {
            return Wait.traversal(toBank(), () -> false, null, 15_000L, outcome -> last = outcome);
        }

        @Override
        public void onTickLoop() {
            WalkOutlook.continuesFor(0);
        }
    }
}
