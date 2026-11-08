package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;


class Node {
    byte kind;
    Object arg;
    Object result;
    Thread waiter;
    Node next;

    volatile Object operation;
    volatile int status;
    volatile byte signal;

    static final int WAITING = 1; //status
    static final byte FN = 1, CONSUMER = 2, BI_FN = 3, BI_CONSUMER = 4; //kind
    static final byte SERVED = 1, IS_COMBINER = 2, ERR_OCCURRED = 3; //signal


    public Node() {}

    public Node(byte signal) {
        SIGNAL.set(this, signal);
    }

    void clearStatus() {
        STATUS.setOpaque(this, 0);
    }

    void unsetWaitingStatus() {
        STATUS.getAndBitwiseAnd(this, ~Node.WAITING);
    }

    public Object opAcquire() {
        return OP.getAcquire(this);
    }

    public void clearOp() {
        OP.set(this, null);
    }

    public byte signalPlain() {
        return (byte) SIGNAL.get(this);
    }

    static final VarHandle STATUS;
    static final VarHandle OP;
    static final VarHandle SIGNAL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            SIGNAL = lookup.findVarHandle(Node.class, "signal", byte.class);
            OP = lookup.findVarHandle(Node.class, "operation", Object.class);
            STATUS = lookup.findVarHandle(Node.class, "status", int.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}