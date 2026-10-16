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
        @Param({"32"})
        public int lines;

        @Param({"0"})
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
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1631.677 ± 18.553   | 1641.383 ± 31.997   |  +0.6%   |  9,176,114 |  9,176,114
2       | 1730.726 ± 33.169   | 1831.961 ± 210.176  |  +5.8%   | 16,531,276 | 15,860,808
3       | 1881.241 ± 44.079   | 1791.101 ± 19.778   |  -4.8%   | 25,069,760 | 24,077,818
4       | 2114.130 ± 340.773  | 1834.800 ± 13.763   | -13.2%   | 32,541,607 | 31,136,668
6       | 2452.909 ± 76.177   | 2039.475 ± 15.972   | -16.9%   | 42,222,035 | 32,205,295
8       | 3480.160 ± 243.218  | 2150.836 ± 98.657   | -38.2%   | 55,849,149 | 48,076,834
16      | 8214.974 ± 234.697  | 4233.688 ± 90.016   | -48.5%   | 62,378,553 | 54,186,960


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=5, Work=1000)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes     | Uncombined
--------|---------------------|---------------------|----------|------------|-----------
1       | 1637.747 ± 16.421   | 1618.429 ± 26.531   |  -1.2%   |  9,351,453 |  9,351,453
2       | 1804.282 ± 125.878  | 1757.232 ± 25.342   |  -2.6%   | 17,057,132 | 16,013,466
3       | 1936.079 ± 72.423   | 1770.455 ± 24.272   |  -8.6%   | 25,393,303 | 24,615,755
4       | 2342.238 ± 182.593  | 1857.611 ± 23.009   | -20.7%   | 31,963,249 | 30,234,230
6       | 3000.317 ± 270.656  | 2059.981 ± 25.903   | -31.3%   | 41,373,658 | 31,262,406
8       | 4752.249 ± 269.881  | 2170.810 ± 147.901  | -54.3%   | 55,395,136 | 47,181,858
16      | 9748.139 ± 576.991  | 4447.737 ± 156.399  | -54.4%   | 59,850,880 | 50,405,838
* */


/*
/*
=====================================================================
FOSE BENCHMARK: combinedAdd vs baselineAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 22.146 ± 1.188      | 23.279 ± 0.404      |  +5.1%   | 644,996,376 | 644,996,376
2       | 98.579 ± 2.400      | 148.453 ± 48.509    | +50.6%   | 179,457,780 | 174,020,452
3       | 109.172 ± 5.313     | 315.158 ± 36.744    | +188.7%  | 102,176,752 |  94,924,271
4       | 144.502 ± 4.890     | 477.206 ± 19.010    | +230.3%  |  72,320,832 |  61,318,273
6       | 218.780 ± 4.306     | 829.688 ± 92.973    | +279.2%  |  47,338,495 |  30,023,690
8       | 299.234 ± 23.624    | 1260.846 ± 267.265  | +321.4%  |  32,102,711 |  14,716,334
16      | 578.724 ± 21.972    | 2563.745 ± 343.231  | +343.0%  |  36,388,137 |  18,902,235


=====================================================================
FOSE BENCHMARK: combinedFetchAdd vs baselineFetchAdd   (Lines=32, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 23.970 ± 0.631      | 25.647 ± 0.643      |  +7.0%   | 585,662,396 | 585,662,396
2       | 117.527 ± 6.596     | 147.571 ± 47.973    | +25.6%   | 146,185,106 | 141,567,110
3       | 148.856 ± 16.872    | 339.206 ± 145.955   | +127.9%  | 118,298,317 | 110,738,916
4       | 190.980 ± 15.961    | 514.661 ± 51.313    | +169.5%  |  74,553,183 |  62,911,922
6       | 312.380 ± 62.348    | 834.521 ± 103.883   | +167.1%  |  46,337,525 |  29,250,965
8       | 370.568 ± 25.848    | 1221.198 ± 176.098  | +229.5%  |  33,713,106 |  15,912,778
16      | 768.690 ± 51.491    | 2601.575 ± 443.067  | +238.4%  |  36,631,330 |  18,308,834
* */
