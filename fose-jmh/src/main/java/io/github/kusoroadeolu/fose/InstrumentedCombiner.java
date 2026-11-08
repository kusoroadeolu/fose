package io.github.kusoroadeolu.fose;

import java.util.function.Consumer;
import java.util.function.Function;

public class InstrumentedCombiner<E>  {

    private final Combiner<E> combiner;

    InstrumentedCombiner(E item, int maxOperationsPerPass, boolean exposeMetrics) {
        combiner = new Combiner<>(item, maxOperationsPerPass, exposeMetrics);
    }

    public static <E>InstrumentedCombiner<E> create(E item, int maxOperationsPerPass) {
        return new InstrumentedCombiner<>(item, maxOperationsPerPass, true);
    }

    public static <E>InstrumentedCombiner<E> create(E item, int maxOperationsPerPass, boolean exposeMetrics) {
        return new InstrumentedCombiner<>(item, maxOperationsPerPass, exposeMetrics);
    }

    //Stuff for instrumenting benchmarks
    public CombinerMetrics metrics() {
        while (!combiner.tryAcquireOpaque()) Thread.onSpinWait();
        try {
            return new CombinerMetrics(combiner.totalCombinedOps, combiner.passes, combiner.combiningPasses, combiner.passes == 0 ? 0 : (double) combiner.totalCombinedOps / combiner.passes);
        }finally {
            combiner.release();
        }
    }

    public void resetMetrics() {
        while (!combiner.tryAcquireOpaque()) Thread.onSpinWait();
        try {
            combiner.totalCombinedOps = 0;
            combiner.passes = 0;
            combiner.combiningPasses = 0;
        }finally {
            combiner.release();
        }
    }

    public <R> R apply(Function<E, R> operation) {
        return combiner.apply(operation);
    }


    public void accept(Consumer<E> operation) {
        combiner.accept(operation);
    }
}
