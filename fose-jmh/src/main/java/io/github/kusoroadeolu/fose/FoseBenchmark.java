package io.github.kusoroadeolu.fose;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FoseBenchmark {

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

    @State(Scope.Benchmark)
    public static class Shared {
        @Param({"5"})
        public int lines;

        @Param({"1000"})
        public int work;

        Data baseData;
        Data combData;
        Combiner<Data> combiner;
        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
        }

        @Setup(Level.Trial)
        public void setup() {
            baseData = new Data(lines);
            combData = new Data(lines);
            combiner = new Combiner<>(combData);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            baseData.verify();
            combData.verify();  //should we collect these in aux counters as well?
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

            public long uncombined() {
                if (reporter) {
                    int uncombined = shared.combiner.uncombined();
                    shared.combiner.resetUncombined();
                    return uncombined;
                }

                return 0;
            }

            public long passes() {
                if (reporter) {
                    int passes = shared.combiner.passes();
                    shared.combiner.resetPasses();
                    return passes;
                }

                return 0;
            }
        }
    }

    @Benchmark
    public void baselineAdd(Shared s,Shared.SharedCounters sc) {
        s.combiner.lock();
        try {
            s.baseData.add();
        } finally {
            s.combiner.unlock();
        }
        Blackhole.consumeCPU(s.work);
    }

    @Benchmark
    public Object baselineFetchAdd(Shared s, Shared.SharedCounters sc) {
        Object r;
        s.combiner.lock();
        try {
            r = s.baseData.fetchAdd(); // boxed, to match the combiner's Function<E,Object>
        } finally {
            s.combiner.unlock();
        }
        Blackhole.consumeCPU(s.work);
        return r;
    }


    @Benchmark
    public Object combinedAdd(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.accept(ADD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

    @Benchmark
    public Object combinedFetchAdd(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.accept(FETCH_ADD);
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
                    .include(FoseBenchmark.class.getSimpleName())
                    .threads(t)
                    .forks(3)
                    .warmupIterations(5)
                    .warmupTime(TimeValue.seconds(1))
                    .measurementIterations(5)
                    .measurementTime(TimeValue.seconds(1))
                    .jvmArgs(JvmArgs.INITIAL_HEAP_SIZE, JvmArgs.MAX_HEAP_SIZE, JvmArgs.GC_TYPE)
                   // .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")
                    .resultFormat(ResultFormatType.JSON)
                    .result(resultFile)         // raw data per sweep step
                    .build())
                    .run();
        }
    }
}

/*
=====================================================================
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1631.677 ± 18.553   | 1641.383 ± 31.997   |  +0.6%   |  9,176,114 |  9,176,114
2       | 1730.726 ± 33.169   | 1714.090 ± 21.050   |  -1.0%   | 17,586,822 | 17,531,128
3       | 1881.241 ± 44.079   | 1909.278 ± 29.827   |  +1.5%   | 21,791,733 | 19,529,302
4       | 2114.130 ± 340.773  | 2076.767 ± 24.808   |  -1.8%   | 23,354,234 | 18,100,356
6       | 2452.909 ± 76.177   | 2312.501 ± 145.096  |  -5.7%   | 30,223,697 | 23,097,390
8       | 3480.160 ± 243.218  | 2495.880 ± 188.214  | -28.3%   | 31,076,578 | 20,670,969
16      | 8214.974 ± 234.697  | 5913.767 ± 503.517  | -28.0%   | 27,767,389 | 19,032,227


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1637.747 ± 16.421   | 1618.429 ± 26.531   |  -1.2%   |  9,351,453 |  9,351,453
2       | 1804.282 ± 125.878  | 1756.399 ± 47.043   |  -2.7%   | 17,166,980 | 17,084,614
3       | 1936.079 ± 72.423   | 1910.458 ± 30.277   |  -1.3%   | 21,783,118 | 19,587,822
4       | 2342.238 ± 182.593  | 2061.754 ± 37.055   | -12.0%   | 23,936,384 | 19,002,255
6       | 3000.317 ± 270.656  | 2276.595 ± 48.813   | -24.1%   | 29,787,735 | 22,473,538
8       | 4752.249 ± 269.881  | 2521.606 ± 113.537  | -46.9%   | 28,257,446 | 18,378,720
16      | 9748.139 ± 576.991  | 5848.343 ± 262.516  | -40.0%   | 25,877,361 | 17,939,962
* */

/*
* /*
=====================================================================
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 22.146 ± 1.188      | 23.279 ± 0.404      |  +5.1%   | 644,996,376 | 644,996,376
2       | 98.579 ± 2.400      | 96.028 ± 3.570      |  -2.6%   | 238,559,812 | 154,311,772
3       | 109.172 ± 5.313     | 198.643 ± 11.284    | +81.9%   | 114,531,844 |  30,881,229
4       | 144.502 ± 4.890     | 262.049 ± 3.261     | +81.4%   |  76,336,463 |   8,403,159
6       | 218.780 ± 4.306     | 324.704 ± 6.038     | +48.4%   |  24,811,640 |   4,430,871
8       | 299.234 ± 23.624    | 422.424 ± 19.531    | +41.2%   |  27,771,273 |  24,766,424
16      | 578.724 ± 21.972    | 1499.950 ± 164.682  | +159.2%  | 109,247,627 | 108,059,633


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 23.970 ± 0.631      | 25.647 ± 0.643      |  +7.0%   | 585,662,396 | 585,662,396
2       | 117.527 ± 6.596     | 100.621 ± 4.331     | -14.4%   | 220,351,462 | 137,222,290
3       | 148.856 ± 16.872    | 207.390 ± 3.861     | +39.3%   | 109,304,462 |  31,711,482
4       | 190.980 ± 15.961    | 273.377 ± 11.912    | +43.1%   |  75,320,022 |  11,402,774
6       | 312.380 ± 62.348    | 332.854 ± 6.917     |  +6.6%   |  22,154,842 |   2,990,626
8       | 370.568 ± 25.848    | 437.116 ± 32.047    | +18.0%   |  24,365,566 |  21,323,392
16      | 768.690 ± 51.491    | 1640.361 ± 248.771  | +113.4%  | 108,942,694 | 107,913,375
* */
