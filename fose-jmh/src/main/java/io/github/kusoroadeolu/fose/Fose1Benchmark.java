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

        // pop min, insert min + random delta, so size stays constant
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

        @Param({"500"})
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
        Object r = s.combiner.apply(HOLD);
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
                            .resultFormat(ResultFormatType.JSON)
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
2       | 367.571 ± 58.012    | 240.884 ± 15.882    | -34.5%   |  68,139,562 |  10,614,326
3       | 482.603 ± 23.254    | 379.986 ± 12.488    | -21.3%   |  30,667,521 |     476,498
4       | 663.972 ± 78.125    | 451.092 ± 8.508     | -32.1%   |   3,437,575 |     199,349
6       | 941.600 ± 24.341    | 660.373 ± 15.565    | -29.9%   |   1,575,285 |     184,625
8       | 1223.727 ± 47.253   | 987.449 ± 15.489    | -19.3%   |   1,473,396 |     208,439
16      | 2431.645 ± 100.558  | 2050.732 ± 54.137   | -15.7%   |   1,769,656 |     464,178 oversubscribed

=====================================================================
FOSE BENCHMARK: combinedHold vs baselineHold   (Size=65536, Work=500)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Uncombined
--------|---------------------|---------------------|----------|-------------|------------
1       | 972.420 ± 63.498    | 924.043 ± 4.528     |  -5.0%   |  16,246,096 |  16,246,096
2       | 1148.491 ± 74.376   | 1018.158 ± 25.693   | -11.3%   |  29,170,530 |  28,162,651
3       | 1952.399 ± 444.693  | 1296.260 ± 26.686   | -33.6%   |  15,024,031 |   2,985,984
4       | 3581.258 ± 505.956  | 1406.492 ± 13.427   | -60.7%   |  14,388,139 |   2,253,470
6       | 4379.929 ± 110.438  | 1559.096 ± 25.577   | -64.4%   |  15,627,221 |   2,276,804
8       | 5978.076 ± 231.845  | 1726.234 ± 44.088   | -71.1%   |   9,046,718 |     312,255
16      | 11724.731 ± 715.380 | 3543.600 ± 114.402  | -69.8%   |  10,296,063 |     781,063  oversubscribed

*/
