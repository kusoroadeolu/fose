package io.github.kusoroadeolu.fose;

interface BatchHandler<E, Q, R> {
    void process(Batch<Q, R> batch, E item);
}
