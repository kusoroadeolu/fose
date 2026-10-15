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
public class Fose1Benchmark {

    static final class HeapData {
        static final int DELTA_MASK = (1 << 20) - 1;
        final long[] h;
        final int n;
        long seed = 0x9E3779B97F4A7C15L;
        private static final int RANDOM_SEED = 32;

        HeapData(int n) {
            this.n = n;
            this.h = new long[n];
            var r = new java.util.SplittableRandom(RANDOM_SEED);
            for (int i = 0; i < n; i++) h[i] = r.nextInt(1 << 20);
            for (int i = n / 2 - 1; i >= 0; i--) siftDown(i, h[i]);
        }

        private long next() { // xor shift
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            return seed >>> 1;
        }

        private void siftDown(int i, long v) {
            for (;;) {
                int l = 2 * i + 1;
                if (l >= n) break;
                int r = l + 1;
                int c = (r < n && h[r] < h[l]) ? r : l;
                if (h[c] >= v) break;
                h[i] = h[c];
                i = c;
            }
            h[i] = v;
        }

        // pop min, insert min + random delta, size stays constant
        long hold() {
            long min = h[0];
            siftDown(0, min + (next() & DELTA_MASK));
            return min;
        }

        void verify() {
            for (int i = 1; i < n; i++) {
                if (h[(i - 1) / 2] > h[i]) throw new IllegalStateException("heap broken at " + i);
            }
        }
    }

    static final Function<HeapData, Object> HOLD = HeapData::hold;


    @State(Scope.Benchmark)
    public static class Shared {
        @Param({"65536"})   // 512 KB of longs, bigger than my L1 cache
        public int size;

        @Param({"0"})
        public int work;

        HeapData baseData;
        HeapData combData;
        Combiner<HeapData> combiner;

        @Setup(Level.Trial)
        public void setup() {
            baseData = new HeapData(size);
            combData = new HeapData(size);
            combiner = new Combiner<>(combData);
        }

        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
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
    public Object baselineHold(Shared s, Shared.SharedCounters sc) {
        Object r;
        s.combiner.lock();
        try {
            r = s.baseData.hold();
        } finally {
            s.combiner.unlock();
        }
        Blackhole.consumeCPU(s.work);
        return r;
    }

    @Benchmark
    public Object combinedHold(Shared s, Shared.SharedCounters sc) {
        Object r = s.combiner.accept(HOLD);
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
                            .include(Fose1Benchmark.class.getSimpleName())
                            .threads(t)
                            .forks(3)
                            .warmupIterations(5)
                            .warmupTime(TimeValue.seconds(1))
                            .measurementIterations(5)
                            .measurementTime(TimeValue.seconds(1))
                            .jvmArgs(JvmArgs.INITIAL_HEAP_SIZE, JvmArgs.MAX_HEAP_SIZE, JvmArgs.GC_TYPE)
                            .resultFormat(ResultFormatType.JSON)
                     //        .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")
                            .result(resultFile)
                            .build())
                    .run();
        }
    }
}


/*
=====================================================================
FOSE BENCHMARK: combinedHold vs baselineHold   (Size=65536, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 105.146 ± 5.729     | 105.349 ± 5.195     |  +0.2%   | 143,301,144 | 143,301,144
2       | 367.571 ± 58.012    | 241.617 ± 13.879    | -34.3%   |  66,782,944 |   8,079,854
3       | 482.603 ± 23.254    | 389.910 ± 15.402    | -19.2%   |  33,430,100 |     601,328
4       | 663.972 ± 78.125    | 455.291 ± 12.296    | -31.4%   |   3,605,413 |     253,345
6       | 941.600 ± 24.341    | 702.080 ± 28.493    | -25.4%   |   1,621,503 |     322,261
8       | 1223.727 ± 47.253   | 1161.117 ± 91.690   |  -5.1%   |   3,836,085 |   2,744,080
16      | 2431.645 ± 100.558  | 4497.960 ± 511.523  | +85.0%   |  11,470,853 |  11,008,813
*

=====================================================================
FOSE BENCHMARK: combinedHold vs baselineHold   (Size=65536, Work=500)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 972.420 ± 63.498    | 924.043 ± 4.528     |  -5.0%   |  16,246,096 |  16,246,096
2       | 1148.491 ± 74.376   | 1084.328 ± 50.979   |  -5.6%   |  27,083,843 |  25,194,009
3       | 1952.399 ± 444.693  | 1365.099 ± 49.310   | -30.1%   |  19,107,053 |   8,441,228
4       | 3581.258 ± 505.956  | 1438.459 ± 23.092   | -59.8%   |  16,827,468 |   3,475,485
6       | 4379.929 ± 110.438  | 1629.842 ± 18.929   | -62.8%   |  16,095,185 |   2,733,676
8       | 5978.076 ± 231.845  | 2002.037 ± 199.169  | -66.5%   |   9,761,480 |   1,170,174
16      | 11724.731 ± 715.380 | 7841.354 ± 1095.803 | -33.1%   |   8,297,946 |   3,800,715

*/
