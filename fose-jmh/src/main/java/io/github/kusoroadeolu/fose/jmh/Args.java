package io.github.kusoroadeolu.fose.jmh;

public class Args {
    public static final String INITIAL_HEAP_SIZE = "-Xms4g";
    public static final String MAX_HEAP_SIZE = "-Xmx4g";
    public static final String GC_TYPE = "-XX:+UseG1GC";
    public static final int[] THREAD_SWEEP = {1, 2, 4, 6, 8, 16, 24, 32, 48, 64};

}
