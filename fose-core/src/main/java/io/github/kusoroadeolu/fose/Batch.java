package io.github.kusoroadeolu.fose;

interface Batch<Q, R> {
    int size() ;
    Q request(int i);
    void complete(int index, R result);
    void fail(int index, Throwable t);
}