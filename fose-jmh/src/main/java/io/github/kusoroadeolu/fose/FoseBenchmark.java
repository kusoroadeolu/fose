package io.github.kusoroadeolu.fose;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


@Fork(value = 3, jvmArgs = {JvmArgs.INITIAL_HEAP_SIZE, JvmArgs.MAX_HEAP_SIZE, JvmArgs.GC_TYPE})
@Measurement(iterations = 10, time = 1)
@Warmup(iterations = 5, time = 1)
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
        CombinerCore<Data> combiner;
        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
        }

        @Setup(Level.Trial)
        public void setup() {
            baseData = new Data(lines);
            combData = new Data(lines);
            combiner = new SimpleCombiner<>(combData);
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
        Object r = s.combiner.doAccept(ADD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

    @Benchmark
    public Object combinedFetchAdd(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.doAccept(FETCH_ADD);
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
                    .resultFormat(ResultFormatType.JSON) //need this for my formatter, can remove later
                    .result(resultFile)
                    .build())
                    .run();

            // .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")

        }
    }
}

/*
/*
=====================================================================
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1631.677 ± 18.553   | 1641.383 ± 31.997   |  +0.6%   |  9,176,114 |  9,176,114
2       | 1730.726 ± 33.169   | 1730.064 ± 30.090   |  -0.0%   | 11,565,263 | 11,083,420
3       | 1881.241 ± 44.079   | 1772.842 ± 45.226   |  -5.8%   | 16,775,410 | 15,508,337
4       | 2114.130 ± 340.773  | 1863.583 ± 51.781   | -11.9%   | 21,201,493 | 19,760,176
6       | 2452.909 ± 76.177   | 2043.489 ± 31.856   | -16.7%   | 27,797,548 | 20,798,711
8       | 3480.160 ± 243.218  | 2137.792 ± 71.816   | -38.6%   | 37,268,958 | 31,546,676
16      | 8214.974 ± 234.697  | 4297.285 ± 202.677  | -47.7%   | 41,182,291 | 34,918,553 oversubscribed


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1637.747 ± 16.421   | 1618.429 ± 26.531   |  -1.2%   |  9,351,453 |  9,351,453
2       | 1804.282 ± 125.878  | 1719.519 ± 26.744   |  -4.7%   | 11,630,066 | 11,342,984
3       | 1936.079 ± 72.423   | 1767.375 ± 24.663   |  -8.7%   | 16,949,147 | 16,448,207
4       | 2342.238 ± 182.593  | 1873.085 ± 47.298   | -20.0%   | 20,979,607 | 19,480,409
6       | 3000.317 ± 270.656  | 2054.323 ± 34.959   | -31.5%   | 27,431,747 | 20,854,421
8       | 4752.249 ± 269.881  | 2128.114 ± 72.870   | -55.2%   | 36,818,445 | 29,041,526
16      | 9748.139 ± 576.991  | 4290.719 ± 180.555  | -56.0%   | 40,927,081 | 34,891,279 oversubscribed
* */



/*
=====================================================================
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 22.146 ± 1.188      | 23.279 ± 0.404      |  +5.1%   | 644,996,376 | 644,996,376
2       | 98.579 ± 2.400      | 115.424 ± 5.321     | +17.1%   | 129,096,946 |  81,546,804
3       | 109.172 ± 5.313     | 213.778 ± 4.189     | +95.8%   |  64,107,848 |  12,507,296
4       | 144.502 ± 4.890     | 286.930 ± 11.561    | +98.6%   |  37,274,699 |   2,362,133
6       | 218.780 ± 4.306     | 337.392 ± 8.190     | +54.2%   |   7,720,925 |     955,664
8       | 299.234 ± 23.624    | 436.506 ± 14.722    | +45.9%   |   3,329,721 |   1,312,933
16      | 578.724 ± 21.972    | 890.128 ± 17.410    | +53.8%   |   5,850,340 |   3,865,091


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 23.970 ± 0.631      | 25.647 ± 0.643      |  +7.0%   | 585,662,396 | 585,662,396
2       | 117.527 ± 6.596     | 123.020 ± 5.869     |  +4.7%   | 118,015,205 |  71,843,381
3       | 148.856 ± 16.872    | 219.112 ± 7.288     | +47.2%   |  67,054,358 |  19,728,767
4       | 190.980 ± 15.961    | 279.547 ± 7.724     | +46.4%   |  39,636,644 |   3,424,770
6       | 312.380 ± 62.348    | 341.556 ± 8.541     |  +9.3%   |  13,333,182 |     942,999
8       | 370.568 ± 25.848    | 445.241 ± 10.463    | +20.2%   |   3,166,005 |   1,214,063
16      | 768.690 ± 51.491    | 867.250 ± 20.804    | +12.8%   |   5,311,006 |   3,294,319
* */
