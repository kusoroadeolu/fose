package io.github.kusoroadeolu.fose;

import java.util.Objects;

class BatchListPad0 {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;

}

class BatchListFields<Q, R> extends BatchListPad0 {
    Q[] requests;
    R[] results;
    Throwable[] errs;
    int size;


    public BatchListFields(int capacity) {
        requests = (Q[]) new Object[capacity];
        results = (R[]) new Object[capacity];
    }
}

class BatchListPad1<Q, R> extends BatchListFields<Q, R> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13;


    public BatchListPad1(int capacity) {
        super(capacity);
    }
}

class BatchList<Q, R> extends BatchListPad1<Q, R> {

    public BatchList(int capacity) {
        super(capacity);
    }

    public int size() {
        return size;
    }

    public void setRequest(int index, Q to) {
        if (index >= requests.length) {
            requests = (Q[]) grow(requests);
            results = (R[]) growTo(results, requests.length);
        }
        requests[index] = to;
        ++size;
    }

    public Q request(int index) {
        if (index >= requests.length) throw new IndexOutOfBoundsException();
        return requests[index];
    }

    public void complete(int index, R result) {
        if (index >= size) throw new IndexOutOfBoundsException();
        results[index] = result;
    }

    public void fail(int index, Throwable t) {
        Objects.requireNonNull(t);
        if (index >= size) throw new IndexOutOfBoundsException();
        if (errs == null || index >= errs.length) errs = (Throwable[]) growTo(errs, requests.length);
        errs[index] = t;
    }

    public Object result(int index) {
        return results[index];
    }

    public Throwable err(int index) {
        return errs[index];
    }

    public boolean hasErr(int index) {
        return errs != null && errs.length > index && errs[index] != null;
    }

    public void remove(int index) {
        requests[index] = null;
        results[index] = null;
        if (errs != null && index < errs.length) errs[index] = null;
        --size;
    }

    static Object[] grow(Object[] items) {
        int capacity = items.length;
        Object[] newItems = new Object[Math.max(capacity + (capacity >>> 1), capacity + 1)];
        System.arraycopy(items, 0, newItems, 0, capacity);
        return newItems;
    }

    static Object[] growTo(Object[] items, int len) {
        Object[] newItems = new Object[len];
        if (items == null) return newItems;

        int capacity = items.length;
        System.arraycopy(items, 0, newItems, 0, capacity);
        return newItems;
    }

}
