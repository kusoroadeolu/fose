package io.github.kusoroadeolu.fose.jmh;

import io.github.kusoroadeolu.fose.InstrumentedCombiner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;


@Fork(value = 3, jvmArgs = {Args.INITIAL_HEAP_SIZE, Args.MAX_HEAP_SIZE, Args.GC_TYPE})
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
    }

    //avoid allocations per call.
    static final Consumer<Data> ADD = Data::add;


    @State(Scope.Benchmark)
    public static class CombinerState {
        @Param({"5"})
        public int lines;

        @Param({"100"})
        public int work;

        @Param({"25"})
        public int maxCombOps;

        Data combData;
        InstrumentedCombiner<Data> combiner;
        AtomicBoolean claimed;

        @Setup(Level.Iteration)
        public void claimed() {
            claimed = new AtomicBoolean(false);
            combiner.resetMetrics();
        }

        @Setup(Level.Trial)
        public void setup() {
            combData = new Data(lines);
            combiner = InstrumentedCombiner.create(combData, maxCombOps);
        }

        @State(Scope.Thread)
        @AuxCounters(AuxCounters.Type.EVENTS)
        public static class SharedMetrics {
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

        @Param({"100"})
        public int work;


        Data baseData;
        Lock lock;


        @Setup(Level.Trial)
        public void setup() {
            baseData = new Data(lines);
            lock = new ReentrantLock();
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
    public void combinedAdd(CombinerState s, CombinerState.SharedMetrics sc) {
        s.combiner.accept(ADD);
        Blackhole.consumeCPU(s.work);
    }


    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int t : Args.THREAD_SWEEP) {
            if (t > cores * 2) break;
            String resultFile = "jmh-threads-" + t + ".json";
            new Runner(
                    new OptionsBuilder()
                    .include(CacheLineBenchmark.class.getSimpleName())
                    .threads(t)
                    .resultFormat(ResultFormatType.JSON) //need this for my formatter, can remove later
                    .result(resultFile)
                  //          .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")
                    .build())
                    .run();

            // .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")

        }
    }
}

