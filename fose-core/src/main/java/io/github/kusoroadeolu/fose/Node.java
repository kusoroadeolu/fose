package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;


//The main idea here is to introduce a new byte field which determines if an enqueued waiting thread can contend
//to become the combiner or has been served. So now rather than threads spinning on the head field to determine if they can contend
//for the lock and causing bidirectional coherency traffic between the combiner and waiters, they simply spin of their predecessors
// node's verdict field improving locality and reducing coherency traffic between the combiners and waiters
//This also reduces the amount of volatile reads needed per spin iteration, currently, enqueued threads read both
// their predecessors next field and the head field in volatile mode, with this change, waiters need only read one volatile field
// per spin. This does come at the cost of an extra byte per node allocation

class Node<E> {
    final byte kind;
    Object arg;
    Object op;

    Throwable err;
    Object result;

    Thread waiter;
    long seq;

    volatile int status;
    volatile Node<E> next;
    volatile byte verdict;

    static final int WAITING = 1;
    static final byte FN = 1, CONSUMER = 2, BI_FN = 3, BI_CONSUMER = 4;
    static final byte SERVED = 1, IS_COMBINER = 2;

    //We'll do something quite similar to the waiting scheme in aqs to support unbounded parks

    /*
    * Enqueue node, spin briefly (based on thread count, otherwise if oversubscribed, we try to park)
    * Create node - try to acquire lock - enqueue - try to acquire lock (if we're next on the queue i.e. head.next) - set status - retry acquiring the lock (if we're next on the queue) - then spin if we aren't oversubscribed before parking
    * in between these, after we've enqueued, we'll check if our operation has been applied so we don't wait unnecessarily
    * */

    public Node(byte kind, Object op, Object arg) {
        this.kind = kind;
        this.op = op;
        this.arg = arg;
    }

    public Node(byte kind, Object op, Object arg, byte verdict) {
        this.kind = kind;
        this.op = op;
        this.arg = arg;
        this.verdict = verdict;
    }

    void clearStatus() {
        STATUS.setOpaque(this, 0);
    }

    void unsetWaitingStatus() {
        STATUS.getAndBitwiseAnd(this, ~Node.WAITING);
    }

    void detach() {
        NEXT.set(this, null);
    }

    static final VarHandle STATUS;
    static final VarHandle NEXT;

    static {
        var lookup = MethodHandles.lookup();
        try {
            NEXT = lookup.findVarHandle(Node.class, "next", Node.class);
            STATUS = lookup.findVarHandle(Node.class, "status", int.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}