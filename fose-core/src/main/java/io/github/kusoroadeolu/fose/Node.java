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

class Node {
    byte kind;
    Object arg;
    Object result;
    Thread waiter;
    Node next;

    volatile Object op;
    volatile int status;
    volatile byte signal;

    static final int WAITING = 1;
    static final byte FN = 1, CONSUMER = 2, BI_FN = 3, BI_CONSUMER = 4;
    static final byte SERVED = 1, IS_COMBINER = 2, ERR_OCCURRED = 3;

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

    public Node(byte kind, Object op, Object arg, byte signal) {
        this.kind = kind;
        this.op = op;
        this.arg = arg;
        this.signal = signal;
    }

    public Node() {}

    public Node(byte signal) {
        this.signal = signal;
    }

    void clearStatus() {
        STATUS.setOpaque(this, 0);
    }

    void unsetWaitingStatus() {
        STATUS.getAndBitwiseAnd(this, ~Node.WAITING);
    }

    public Object getOpAcquire() {
        return OP.getAcquire(this);
    }

    public void clearOp() {
        OP.set(this, null);
    }

    public byte getSignalPlain() {
        return (byte) SIGNAL.get(this);
    }

    static final VarHandle STATUS;
    static final VarHandle OP;
    static final VarHandle SIGNAL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            SIGNAL = lookup.findVarHandle(Node.class, "signal", byte.class);
            OP = lookup.findVarHandle(Node.class, "op", Object.class);
            STATUS = lookup.findVarHandle(Node.class, "status", int.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}