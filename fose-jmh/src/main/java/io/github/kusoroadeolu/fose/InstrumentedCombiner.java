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

    //Stuff for instrumenting benchmarks
    public CombinerMetrics metrics() {
        while (!acquire()) Thread.onSpinWait();
        try {
            return new CombinerMetrics(_total_ops, _passes, _combined, _passes == 0 ? 0 : (double) _total_ops / _passes);
        }finally {
            release();
        }
    }

    public void resetMetrics() {
        while (!acquire()) Thread.onSpinWait();
        try {
            _total_ops = 0;
            _passes = 0;
            _combined = 0;
        }finally {
            release();
        }
    }
}
