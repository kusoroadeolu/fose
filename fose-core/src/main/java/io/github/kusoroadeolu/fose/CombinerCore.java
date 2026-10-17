package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

class CombinerPad0 {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;
}

class CombinerLockField extends CombinerPad0 {
    final Lock lock;

    public CombinerLockField() {
        this.lock = new StampedLock().asWriteLock();
    }
}

class CombinerPad1 extends CombinerLockField {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerWaiterField<E> extends CombinerPad1 {
    volatile Node<E> tail;
}

class CombinerPad2<E> extends CombinerWaiterField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;
}

class CombinerFields<E> extends CombinerPad2<E> {
    Node<E> head;
    final E item;
    final int maxOps; //max operations per combining session

    //metrics for benchmarks, could expose em?
    int _uncombined;
    int _passes;

    public CombinerFields(E item, int maxOps) {
        this.item = item;
        this.maxOps = maxOps;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;


    public CombinerPad3(E item, int maxOps) {
        super(item, maxOps);
    }
}

abstract class CombinerCore<E> extends CombinerPad3<E> {

    private static final int MAX_COMBINING_OPERATIONS = 100;
    static final int NCPU = Runtime.getRuntime().availableProcessors();
    static final boolean IS_UNIPROCESSOR = NCPU == 1;
    private static final int SPIN_STEPS = 9;
    private static final int MAX_STEPS = SPIN_STEPS + 4;
    private static final int PARK_NS = 1_000; //should this be more? maybe 1M, 1K ns performs alright, no need tbh
    public CombinerCore(E item) {
        super(item, MAX_COMBINING_OPERATIONS);
        head = tail = new Node<>(null);
    }

    //these lock/unlock api's are easy to misuse? should I avoid exposing them?
    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public <R> R doAccept(Object op) {
        var lock = this.lock;

        if (lock.tryLock()) {
            try {
                return doCombine(op);
            }finally {
                lock.unlock();
            }
        }

        Node<E> node = new Node<>(op);
        Node<E> prev = xchgTail(node);
        prev.srNext(node);

        int misses = 0;
        int steps = 1;
        boolean wasInterrupted = false;

        try {
            outer: for (;;) {
                if (steps <= SPIN_STEPS) {
                    //spin from steps 1 to 9
                    int maxSpins = 1 << steps;
                    for (int spins = 0; spins++ < maxSpins;) {
                        Thread.onSpinWait();
                        if (node.laDone()) break outer;
                    }

                } else if (steps < MAX_STEPS) { //yield from steps 10 to 13 (4 yields)
                    Thread.yield();
                    if (node.laDone()) break outer;
                } else {
                    LockSupport.parkNanos(PARK_NS);

                    if (!wasInterrupted && Thread.interrupted()) wasInterrupted = true; // clear interrupt flag

                    if (node.laDone()) break outer;
                }

                if (lock.tryLock()) {
                    try {
                        return doCombineNode(node);
                    }finally {
                        lock.unlock();
                    }
                }

                if (misses++ == 2) {
                    steps = Math.min(steps + 1, MAX_STEPS);
                    misses = 0;
                }

            }
        }finally {
            if (wasInterrupted) Thread.currentThread().interrupt(); //reset interrupt flag
        }

        if (node.error() != null) throw new RuntimeException(node.error());
        else return (R) node.result();
    }


    abstract  <R> R doCombine(Object o);

    abstract  <R> R doCombineNode(Node<E> node);

    abstract void combinePass();



    public int uncombined() {
        lock.lock();
        try {
            return _uncombined;
        }finally {
            lock.unlock();
        }
    }

    void resetUncombined() {
        lock.lock();
        try {
            _uncombined = 0;
        }finally {
            lock.unlock();
        }
    }

    void resetPasses() {
        lock.lock();
        try {
            _passes = 0;
        }finally {
            lock.unlock();
        }
    }

    public int passes() {
        lock.lock();
        try {
            return _passes;
        }finally {
            lock.unlock();
        }
    }

    Node<E> xchgTail(Node<E> with) {
        return (Node<E>) TAIL.getAndSet(this, with);
    }


    static final VarHandle TAIL;

    static {
        var lookup = MethodHandles.lookup();
        try {
            TAIL = lookup.findVarHandle(CombinerCore.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
