package io.github.kusoroadeolu.fose;

import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;

public class CombinerLincheckStressTest {
    private final Combiner<Queue<Integer>> combiner = CombinerTestFactory.create(new LinkedList<>());

    @Operation
    public void offerPoll() {
        combiner.accept(q -> {
            q.offer(1);
            q.poll();
        });
    }

    @Test
    void stress() {
        new StressOptions()
                .threads(4)
                .actorsPerThread(4)
                .iterations(200)
                .invocationsPerIteration(5_000)
                .check(this.getClass());
    }
}