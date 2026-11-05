package io.github.kusoroadeolu.fose.jmh;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;

public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            new Runner(new CommandLineOptions(args)).run();
            return;
        }

        HeapBenchmark.main(args);
        CacheLineBenchmark.main(args);
    }
}
