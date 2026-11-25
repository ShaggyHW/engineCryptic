package com.projectx.traversal;

import com.projectx.script.JavaScript;
import com.projectx.script.JavaState;
import com.projectx.script.JavaStateMachineScript;
import com.projectx.script.Wait;
import com.projectx.script.api.Lodestone;

/** Compiles only if a Java script can build, run and route a traversal the way the Java API documents. */
public final class JavaTraversalUsage {

    static TraversalNodeList toBank() {
        return JavaTraversal.nodes(b -> {
            b.useLodestone(Lodestone.VARROCK, null);
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
