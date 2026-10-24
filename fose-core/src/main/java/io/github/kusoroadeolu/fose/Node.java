package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

class Node<E> {
    Object op;
    Throwable err;
    Node<E> next;
    Object result;

    public Node(Object op) {
        this.op = op;
    }

    public void srDone() {
        OP.setRelease(this, null);
    }

    public void srNext(Node<E> node) {
        NEXT.setRelease(this, node);
    }

    public boolean laDone() {
        return OP.getAcquire(this) ==  null;
    }


    public Node<E> lvNext() {
        return (Node<E>) NEXT.getVolatile(this);
    }

    static final VarHandle OP;
    static final VarHandle NEXT;

    static {
        var lookup = MethodHandles.lookup();
        try {
            OP = lookup.findVarHandle(Node.class, "op", Object.class);
            NEXT = lookup.findVarHandle(Node.class, "next", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}