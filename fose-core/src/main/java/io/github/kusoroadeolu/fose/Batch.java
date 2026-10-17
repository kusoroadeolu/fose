package io.github.kusoroadeolu.fose;

public class Batch<Q, R> {
    final BatchList<Q, R> list;
    final int size;

    Batch(BatchList<Q, R> list) {
        this.list = list;
        size = list.size();
    }

    public int size() {
        return size;
    }

    public Q request(int i) {
        return list.request(i);
    }

    public void complete(int index, R result) {
        list.complete(index, result);
    }

    public void fail(int index, Throwable t) {
        list.fail(index, t);
    }
}