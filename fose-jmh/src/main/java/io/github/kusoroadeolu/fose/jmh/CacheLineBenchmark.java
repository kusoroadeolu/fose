package io.github.kusoroadeolu.fose.jmh;

import io.github.kusoroadeolu.fose.Combiner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;


@Fork(value = 3, jvmArgs = {JvmArgs.INITIAL_HEAP_SIZE, JvmArgs.MAX_HEAP_SIZE, JvmArgs.GC_TYPE})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CacheLineBenchmark {

    //More or less the structure we're working on
    static final class Data {
        static final int STRIDE = 16; // 16 longs = 128 bytes, should dodge intels adjacent-line prefetch, we could make this 8 though
        static final int PAD = 12;    // we keep the first counter away from the array header, is this necessary though? could remove this later on
        final long[] x;
        final int lines;

        Data(int lines) {
            this.lines = lines;
            this.x = new long[PAD + lines * STRIDE];
        }

        void add() {
            long old = x[PAD];
            for (int i = 0; i < lines; i++) {
                x[PAD + i * STRIDE] = old + 1;
            }
        }

        long fetchAdd() {
            long old = x[PAD];
            for (int i = 0; i < lines; i++) {
                x[PAD + i * STRIDE] = old + 1;
            }
            return old;
        }

        // Will throw if mutual exclusion was ever broken, returns the counter otherwise.
        void verify() {
            long v = x[PAD];
            for (int i = 1; i < lines; i++) {
                if (x[PAD + i * STRIDE] != v) {
                    throw new IllegalStateException("line " + i + " = " + x[PAD + i * STRIDE] + " but line 0 = " + v);
                }
            }
        }
    }

    //avoid allocations per call.
    static final Function<Data, Object> ADD = d -> {
        d.add();
        return null;
    };

    static final Function<Data, Object> FETCH_ADD = Data::fetchAdd; // will box the result
    private static final int MAX_COMBINING_OPS = 100;

    @State(Scope.Benchmark)
    public static class Shared {
        @Param({"5"})
        public int lines;

        @Param({"1000"})
        public int work;

        Data baseData;
        Data combData;
        Combiner<Data> combiner;
        Lock lock;
        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
            combiner.resetMetrics();
        }

        @Setup(Level.Trial)
        public void setup() {
            baseData = new Data(lines);
            combData = new Data(lines);
            lock = new StampedLock().asWriteLock();
            combiner = Combiner.builder(combData).exposeMetrics().maxCombiningOperations(MAX_COMBINING_OPS).build();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            baseData.verify();
            combData.verify();
        }

        @State(Scope.Thread)
        @AuxCounters(AuxCounters.Type.EVENTS)
        public static class SharedCounters {
            Shared shared;
            boolean reporter;

            @Setup(Level.Iteration)
            public void claim(Shared shared) {
                this.shared = shared;
                this.reporter = shared.claimed.compareAndSet(false, true);
            }

            public long combined() {
                if (reporter) {
                    return shared.combiner.metrics().combiningPasses();
                }

                return 0;
            }

            public long passes() {
                if (reporter) {
                    return shared.combiner.metrics().totalPasses();
                }

                return 0;
            }
        }
    }

    @Benchmark
    public void baselineAdd(Shared s,Shared.SharedCounters sc) {
        s.lock.lock();
        try {
            s.baseData.add();
        } finally {
            s.lock.unlock();
        }
        Blackhole.consumeCPU(s.work);
    }

    @Benchmark
    public Object baselineFetchAdd(Shared s, Shared.SharedCounters sc) {
        Object r;
        s.lock.lock();
        try {
            r = s.baseData.fetchAdd(); // boxed, to match the combiner's Function<E,Object>
        } finally {
            s.lock.unlock();
        }
        Blackhole.consumeCPU(s.work);
        return r;
    }


    @Benchmark
    public Object combinedAdd(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.apply(ADD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

    @Benchmark
    public Object combinedFetchAdd(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.apply(FETCH_ADD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

   static final int[] THREAD_SWEEP = {1, 2, 3, 4, 6, 8, 16, 24, 32, 48, 64};
    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int t : THREAD_SWEEP) {
            if (t > cores * 2) break;
            String resultFile = "jmh-threads-" + t + ".json";
            new Runner(
                    new OptionsBuilder()
                    .include(CacheLineBenchmark.class.getSimpleName())
                    .threads(t)
                    .resultFormat(ResultFormatType.JSON) //need this for my formatter, can remove later
                    .result(resultFile)
                    .build())
                    .run();

            // .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")

        }
    }
}


/*
=====================================================================
CACHE LINE BENCHMARK: combinedAdd vs baselineAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Combined
--------|---------------------|---------------------|----------|-------------|----------
1       | 1631.677 ± 18.553   | 1623.403 ± 15.459   |  -0.5%   |  18,497,165 | 0
2       | 1730.726 ± 33.169   | 1753.021 ± 28.288   |  +1.3%   |  34,245,708 | 22,698
3       | 1881.241 ± 44.079   | 1799.102 ± 15.940   |  -4.4%   |  49,954,176 | 108,796
4       | 2114.130 ± 340.773  | 1879.301 ± 17.198   | -11.1%   |  63,117,601 | 763,588
6       | 2452.909 ± 76.177   | 2081.865 ± 11.997   | -15.1%   |  81,123,148 | 5,068,870
8       | 3480.160 ± 243.218  | 2168.774 ± 33.825   | -37.7%   | 110,415,755 | 1,930,051
16      | 8214.974 ± 234.697  | 4340.719 ± 99.196   | -47.2%   | 122,097,677 | 2,019,508 oversubscribed


=====================================================================
CACHE LINE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Combined
--------|---------------------|---------------------|----------|-------------|----------
1       | 1637.747 ± 16.421   | 1642.353 ± 47.474   |  +0.3%   |  18,307,429 | 0
2       | 1804.282 ± 125.878  | 1743.515 ± 18.423   |  -3.4%   |  34,376,997 | 64,962
3       | 1936.079 ± 72.423   | 1807.115 ± 13.879   |  -6.7%   |  49,611,650 | 204,970
4       | 2342.238 ± 182.593  | 1912.405 ± 32.475   | -18.4%   |  61,803,152 | 879,482
6       | 3000.317 ± 270.656  | 2101.295 ± 16.281   | -30.0%   |  80,133,773 | 4,787,114
8       | 4752.249 ± 269.881  | 2175.700 ± 32.708   | -54.2%   | 108,364,764 | 2,750,603
16      | 9748.139 ± 576.991  | 4450.680 ± 77.904   | -54.3%   | 119,253,690 | 2,543,690 oversubscribed
* */


