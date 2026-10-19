package io.github.kusoroadeolu.fose;

//TODO make this pluggable
public record WaitPolicy(int spinSteps, int yieldSteps, int parkNanos) {
    public WaitPolicy {
        if (spinSteps < 0 || yieldSteps < 0 || parkNanos < 0) throw new IllegalArgumentException("No wait parameter can be less than zero");
    }
}
