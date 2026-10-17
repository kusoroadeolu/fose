package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class Node<E> {
    Object op; // to allow for handling errs? we should probably wrap the result in
    Throwable t;
    Node<E> next;
    Object result;
    boolean done;

    public Node(Object op) {
       this.op = op;
   }

    public void srDone() {
        DONE.setRelease(this, true);
    }

    public Throwable error() {
        return t;
    }

    public Object result() {
        return result;
    }

    public void spDone() {
        done = true;
    }

    public void srNext(Node<E> request) {
        NEXT.setRelease(this, request);
    }

    public boolean laDone() {
        return (boolean) DONE.getAcquire(this);
    }


    public Node<E> lvNext() {
        return (Node<E>) NEXT.getVolatile(this);
    }

    public void spNextNull() {
        next = null;
    }

    static final VarHandle DONE;
    static final VarHandle NEXT;

    static {
        var lookup = MethodHandles.lookup();
        try {
            DONE = lookup.findVarHandle(Node.class, "done", boolean.class);
            NEXT = lookup.findVarHandle(Node.class, "next", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}