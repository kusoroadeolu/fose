package io.github.kusoroadeolu.fose.jmh;

import io.github.kusoroadeolu.fose.Combiner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
        @Param({"65536"})
        public int size;

        @Param({"500"})
        public int work;

        @Param({"25"})
        public int maxCombOps;

        HeapData combData;
        Combiner<HeapData> combiner;


        @Setup(Level.Trial)
        public void setup() {
            combData = new HeapData(size);
            combiner = Combiner.builder(combData).exposeMetrics().maxCombiningOperations(maxCombOps).build();
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

        @Param({"500"})
        public int work;

        HeapData baseData;
        Lock lock;

        @Setup(Level.Trial)
        public void setup() {
            baseData = new HeapData(size);
            lock = new ReentrantLock();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            baseData.verify();  //should we collect these in aux counters as well?
        }
    }



//    @Benchmark
//    public Object baselineHold(LockState s) {
//        Object r;
//        s.lock.lock();
//        try {
//            r = s.baseData.hold();
//        } finally {
//            s.lock.unlock();
//        }
//        Blackhole.consumeCPU(s.work);
//        return r;
//    }

    @Benchmark
    public Object combinedHold(CombinerState s, CombinerState.SharedCounters sc) {
        Object r = s.combiner.apply(HOLD);
        Blackhole.consumeCPU(s.work);
        return r;
    }

    static final int[] THREAD_SWEEP = {16, 24, 32, 48, 64};
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
                         //   .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")
                            .build())
                    .run();
        }
    }
}