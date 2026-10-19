package io.github.kusoroadeolu.fose;

public record CombinerMetrics(long totalCombinedOperations, long totalPasses, long combiningPasses, double combinedOperationsPerPass) {
    static CombinerMetrics empty() {
        return new CombinerMetrics(0, 0 , 0, 0);
    }
}
