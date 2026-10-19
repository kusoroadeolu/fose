package io.github.kusoroadeolu.fose.jmh;

public class BenchmarkRunner {
     public static void main(String[] args) throws Exception {
        HeapBenchmark.main(args);
        CacheLineBenchmark.main(args);
    }
}
