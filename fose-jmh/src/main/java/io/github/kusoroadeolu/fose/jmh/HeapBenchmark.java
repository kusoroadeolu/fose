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
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HeapBenchmark {

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

        // pop min, insert min + random delta, so size stays constant, no gc interference
        long hold() {
            long min = h[0];
            siftDown(0, min + (next() & DELTA_MASK));
            return min;
        }

        //ensure mutual exclusion is upheld
        void verify() {
            for (int i = 1; i < n; i++) {
                if (h[(i - 1) / 2] > h[i]) throw new IllegalStateException("heap broken at " + i);
            }
        }
    }

    static final Function<HeapData, Object> HOLD = HeapData::hold;

    @State(Scope.Benchmark)
    public static class CombinerState {
        @Param({"65536"})   // 512 KB of longs, bigger than my L1 cache
        public int size;

        @Param({"0"})
        public int work;

        @Param({"25", "100", "175"})
        public int maxCombingOps;

        HeapData combData;
        Combiner<HeapData> combiner;


        @Setup(Level.Trial)
        public void setup() {
            combData = new HeapData(size);
            combiner = Combiner.builder(combData).exposeMetrics().maxCombiningOperations(maxCombingOps).build();
        }

        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
            combiner.resetMetrics();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            combData.verify();  //should we collect these in aux counters as well?
        }

        @State(Scope.Thread)
        @AuxCounters(AuxCounters.Type.EVENTS)
        public static class SharedCounters {
            CombinerState shared;
            boolean reporter;

            @Setup(Level.Iteration)
            public void claim(CombinerState shared) {
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

            public double avgOps() {
                if (reporter) {
                    return shared.combiner.metrics().combinedOperationsPerPass();
                }

                return 0;
            }
        }
    }

    @State(Scope.Benchmark)
    public static class LockState {
        @Param({"65536"})   // 512 KB of longs, bigger than my L1 cache
        public int size;

        @Param({"0"})
        public int work;

        HeapData baseData;
        Lock lock;

        @Setup(Level.Trial)
        public void setup() {
            baseData = new HeapData(size);
            lock = new StampedLock().asWriteLock();
        }

        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            baseData.verify();  //should we collect these in aux counters as well?
        }
    }



    @Benchmark
    public Object baselineHold(LockState s) {
        Object r;
        s.lock.lock();
        try {
            r = s.baseData.hold();
        } finally {
            s.lock.unlock();
        }
        Blackhole.consumeCPU(s.work);
        return r;
    }

    @Benchmark
    public Object combinedHold(CombinerState s, CombinerState.SharedCounters sc) {
        Object r = s.combiner.apply(HOLD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

    static final int[] THREAD_SWEEP = {2, 3, 4, 6, 8, 16, 24, 32, 48, 64};
    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int t : THREAD_SWEEP) {
            if (t > cores * 2) break;
            String resultFile = "jmh-threads-" + t + ".json";
            new Runner(
                    new OptionsBuilder()
                            .include(HeapBenchmark.class.getSimpleName())
                            .threads(t)
                            .resultFormat(ResultFormatType.JSON)
                            .result(resultFile)
                            .build())
                    .run();
        }
    }
}


/*
 * Machine: 4 core intel i5 cpu, 8 cpus and 16 ram, OS: Windows
 * */


/*
=====================================================================
HEAP BENCHMARK: combinedHold vs baselineHold   (Size=65536, Work=0)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Combined
--------|---------------------|---------------------|----------|-------------|------------
1       | 105.146 ± 5.729     | 97.261 ± 2.194      |  -7.5%   | 308,997,993 | 0       same here
2       | 367.571 ± 58.012    | 230.245 ± 6.314     | -37.4%   | 146,301,855 | 115,314,752
3       | 482.603 ± 23.254    | 389.318 ± 8.486     | -19.3%   |  47,249,388 | 46,488,379
4       | 663.972 ± 78.125    | 476.699 ± 7.309     | -28.2%   |   4,568,121 |  4,216,985
6       | 941.600 ± 24.341    | 735.762 ± 23.409    | -21.9%   |   2,870,981 |  2,516,279
8       | 1223.727 ± 47.253   | 1071.025 ± 13.676   | -12.5%   |   2,612,135 |  2,335,625
16      | 2431.645 ± 100.558  | 2190.943 ± 33.680   |  -9.9%   |   2,977,724 |  2,484,704 oversubscribed
I'm curious why we seem to have less combining passes when we have less work in between?

=====================================================================
HEAP BENCHMARK: combinedHold vs baselineHold   (Size=65536, Work=500)
=====================================================================
Threads | Baseline (ns/op)    | Combined (ns/op)    | Diff     | Passes      | Combined
--------|---------------------|---------------------|----------|-------------|------------
1       | 972.420 ± 63.498    | 894.957 ± 24.009    |  -8.0%   |  33,589,595 | 0       difference here is basically negligible
2       | 1148.491 ± 74.376   | 1031.413 ± 12.767   | -10.2%   |  56,652,347 |  1,577,943
3       | 1952.399 ± 444.693  | 1301.101 ± 23.390   | -33.4%   |  29,479,818 | 22,105,226
4       | 3581.258 ± 505.956  | 1414.081 ± 11.989   | -60.5%   |  28,488,913 | 23,325,490
6       | 4379.929 ± 110.438  | 1586.741 ± 12.507   | -63.8%   |  28,763,680 | 23,585,631
8       | 5978.076 ± 231.845  | 1779.432 ± 43.572   | -70.2%   |  12,990,477 | 12,519,564
16      | 11724.731 ± 715.380 | 3578.409 ± 67.923   | -69.5%   |  13,228,989 | 12,797,028 oversubscribed
*/