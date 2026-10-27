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
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;


@Fork(value = 3, jvmArgs = {JvmArgs.INITIAL_HEAP_SIZE, JvmArgs.MAX_HEAP_SIZE, JvmArgs.GC_TYPE})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.SampleTime)
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


    @State(Scope.Benchmark)
    public static class CombinerState {
        @Param({"5"})
        public int lines;

        @Param({"1000"})
        public int work;

        @Param({"25"})
        public int maxCombOps;

        Data combData;
        Combiner<Data> combiner;
        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
            combiner.resetMetrics();
        }

        @Setup(Level.Trial)
        public void setup() {
            combData = new Data(lines);
            combiner = Combiner.builder(combData).exposeMetrics().maxCombiningOperations(maxCombOps).build();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            combData.verify();
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
        @Param({"5"})
        public int lines;

        @Param({"1000"})
        public int work;


        Data baseData;
        Lock lock;


        @Setup(Level.Trial)
        public void setup() {
            baseData = new Data(lines);
            lock = new ReentrantLock();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            baseData.verify();
        }
    }

    @Benchmark
    public void baselineAdd(LockState sc) {
        sc.lock.lock();
        try {
            sc.baseData.add();
        } finally {
            sc.lock.unlock();
        }
        Blackhole.consumeCPU(sc.work);
    }



    @Benchmark
    public Object combinedAdd(CombinerState s, CombinerState.SharedCounters sc) {
        Object r = s.combiner.apply(ADD);
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
* Machine: 4 core intel i5 cpu, 8 cpus and 16 ram, OS: Windows
* */

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
*/
