package io.github.kusoroadeolu.fose;

public class InstrumentedCombiner<E> extends Combiner<E> {


    InstrumentedCombiner(E item, int maxCombiningOperations, boolean exposeMetrics) {
        super(item, maxCombiningOperations, exposeMetrics);
    }

    public static <E>InstrumentedCombiner<E> create(E item, int maxOperationsPerPass) {
        return new InstrumentedCombiner<>(item, maxOperationsPerPass, true);
    }

    public static <E>InstrumentedCombiner<E> create(E item, int maxOperationsPerPass, boolean exposeMetrics) {
        return new InstrumentedCombiner<>(item, maxOperationsPerPass, exposeMetrics);
    }

    public CombinerMetrics metrics() {
        return super.metrics();
    }

    public void resetMetrics() {
        super.resetMetrics();
    }
}
