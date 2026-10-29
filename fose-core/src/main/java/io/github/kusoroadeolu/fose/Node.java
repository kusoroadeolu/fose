package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class Node<E> {
    final int kind;
    Object arg;
    Object op;
    Throwable err;
    Object result;
    Thread waiter;
    long seq;

    volatile int status;
    volatile Node<E> next;

    static final int PARKING = 1; //indicates a thread is about to park
    static final int SPINNING = 2;


    //Here, we'll do something quite similar to the waiting scheme in aqs to support unbounded parks

    /*
    * Enqueue node, spin briefly (based on thread count, otherwise if oversubscribed, we try to park)
    * Create node - try to acquire lock - enqueue - try to acquire lock (if we're next on the queue i.e. head.next) - set status - retry acquiring the lock (if we're next on the queue) - then spin if we aren't oversubscribed before parking
    * in between these, after we've enqueued, we'll check if our operation has been applied so we don't wait unnecessarily
    * */

    public Node(int kind, Object op, Object arg) {
        this.kind = kind;
        this.op = op;
        this.arg = arg;
    }

    @Override
    public String toString() {
        return "Node{" +
                "status=" + status +
                ", waiter=" + waiter +
                '}';
    }

    void clearStatus() {
        STATUS.setOpaque(this, 0);
    }

    public void unsetStatus(int v) {
        STATUS.getAndBitwiseAnd(this, ~v);
    }


    static final VarHandle STATUS;

    static {
        var lookup = MethodHandles.lookup();
        try {
            STATUS = lookup.findVarHandle(Node.class, "status", int.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}