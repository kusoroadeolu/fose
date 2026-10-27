package io.github.kusoroadeolu.fose;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

class CombinerPad0 {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;
}

class CombinerLockField extends CombinerPad0 {
    volatile long state = FREE;
    static final long FREE = 0;
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
    volatile Node<E> head;
    final E item;
    long currSeq;
    //ideally we'd use an int, but to avoid hotspot reordering this with the tail fail due to alignment gaps,
    // we use a long
    //will switch to byte padding later to avoid this

    final long maxOps; //max operations per combining session
    final boolean exposeMetrics;

    //metrics
    long _combined;
    long _passes;
    long _total_ops;

    public CombinerFields(E item, int maxOps, boolean exposeMetrics) {
        this.item = item;
        this.maxOps = maxOps;
        this.exposeMetrics = exposeMetrics;
    }
}

class CombinerPad3<E> extends CombinerFields<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;


    public CombinerPad3(E item, int maxOps, boolean exposeMetrics) {
        super(item, maxOps, exposeMetrics);
    }
}


/*
 * The idea of flat combining was introduced in the 2010 SPAA paper "Flat
 * Combining and the Synchronization-Parallelism Tradeoff" (Hendler, Incze,
 * Shavit, Tzafrir).
 *
 * Flat combining can be used as an alternative to fine-grained locking for
 * making a sequential structure thread-safe, while minimizing the
 * synchronization overhead and cache coherence traffic incurred when
 * acquiring a lock.
 *
 * The basic idea is that when a thread finds the lock contended, it can
 * request that its operation be applied by the lock holder. It does this by
 * enqueueing a node containing its operation (expressed as a function or
 * consumer lambda), and then periodically checking whether the operation has
 * been applied or the lock has become available.
 *
 * The publication queue is an unbounded MPSC queue.
 * An enqueue atomically tries to cas the queue's tail with the
 * thread's node and then links the previous tail's next pointer to that node.
 *. There is a brief window between swapping the tail and linking
 * the previous tail's next pointer in which the queue observed
 * by a traversing lock holder may be inconsistent. Hence, a
 * combining pass could be exited once the lock holder encounters this
 * inconsistency.
 *
 * A lock holder applies its own op, then applies nodes in fifo, bounding
 * the time it spends serving others.
 *
 *
 * Waiters near the head of the queue use an escalating backoff strategy,
 * from spin waiting to parking, waiters further from the head park immediately.
 *
 * Interrupts are deferred while waiting (parkNanos would otherwise return
 * immediately, and a node cannot be abandoned once published) and restored on
 * exit. Exceptions thrown by a combined op are rethrown in the owning thread
 * as CombiningException.
 *
 *
 * References:
 * [1] Flat Combining and the Synchronization-Parallelism Tradeoff
 *     (Hendler, Incze, Shavit, Tzafrir)
 * [2] Revisiting the combining synchronization technique
 *     (Fatourou, Kallimanis)
 */
abstract class CombinerCore<E> extends CombinerPad3<E> {

    /**
     * Default maximum number of operations a combiner can serve in one pass
     * */
    static final int DEFAULT_MAX_COMBINING_OPERATIONS = 25;

    static final int NCPU = Runtime.getRuntime().availableProcessors();
    static final int MAX_SPINNING_WAITERS = NCPU - 1;
    static final int MAX_SPINS = 1024;


    CombinerCore(E item) {
        super(item, DEFAULT_MAX_COMBINING_OPERATIONS, false);
        head = tail = new Node<>(null);
    }

    CombinerCore(E item, int maxCombiningOperations, boolean exposeMetrics) {
        super(item, maxCombiningOperations, exposeMetrics);
        head = tail = new Node<>(null);
    }


    <R> R tryCombining(Object op) {
        boolean wasInterrupted = false;
        Node<E> node = null, prev = null;
        Thread w = Thread.currentThread();
        boolean isVirtual = w.isVirtual();

        try {
            boolean first = false;
            long seenSeq;
            long remSpins = MAX_SPINS;

            o: for (;;) {
                if (prev != null && prev.next == null) break;

                if (first || prev == null || (first = (prev == head))) {
                    if (acquire()) {
                        if (first) return combineEnqueued(prev, node);
                        else return combine(op);
                    }
                }

                if (node == null) {
                    node = new Node<>(op);
                } else if (prev == null) {

                    var t = tail;
                    long seq = t.seq;
                    node.seq = seq + 1;
                    node.waiter = w;

                    if (!casTail(t, node)) {
                        node.waiter = null;
                        continue;
                    }

                    prev = t;
                    prev.next = node;

                } else if (node.status == 0) {
                    int s;

                    seenSeq = currSeq();
                    node.status = s = !isVirtual && shouldSpin(node, seenSeq) ?
                            Node.SPINNING : Node.PARKING;

                    if (s == Node.SPINNING && remSpins <= 0) remSpins = Byte.MAX_VALUE; //try to spin briefly if we've exhausted
                    //our spin budget but are near the head of the queue

                } else if (node.status == Node.SPINNING) {
                    for (long spins = 0; spins < remSpins; ++spins, --remSpins) {
                        if (prev.next == null) break o;

                        if (head == prev) continue o;      // we're first, try acquire

                        Thread.onSpinWait();
                    }


                    node.status = Node.PARKING;            // budget spent, park

                } else {
                    LockSupport.park(this);
                    if (!wasInterrupted && Thread.interrupted()) wasInterrupted = true;
                    node.clearStatus();                    // clear status & loop top rechecked prev.next/head first
                }

            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
        return result(node);
    }

    private boolean shouldSpin(Node<E> node, long seenSeq) {
        long s = node.seq;
        return (s - seenSeq) <= MAX_SPINNING_WAITERS;
    }

    <R> R result(Node<E> node) {
        if (node.err != null) {
            Throwable t = node.err;
            cleanNode(node);
            throw new CombiningException(t);
        } else {
            Object o = node.result;
            cleanNode(node);
            return (R) o;
        }
    }

    private void cleanNode(Node<E> node) {
        node.err = null;
        node.op = null;
        node.result = null;
    }

    boolean acquire() {
        return state == FREE && (long) STATE.getAndAdd(this, 1) == FREE;
    }

    void release() {
        STATE.setRelease(this, FREE);
    }

    Node<E> getHeadPlain() {
        return (Node<E>) HEAD.get(this);
    }


    abstract <R> R combine(Object o);

    abstract <R> R combineEnqueued(Node<E> prev, Node<E> node);

    abstract void combinePass();

    public CombinerMetrics metrics() {
        while (!acquire()) Thread.onSpinWait();
        try {
            return new CombinerMetrics(_total_ops, _passes, _combined, _passes == 0 ? 0 : (double) _total_ops / _passes);
        }finally {
            release();
        }
    }

    public void resetMetrics() {
        while (!acquire()) Thread.onSpinWait();
        try {
            _total_ops = 0;
            _passes = 0;
            _combined = 0;
        }finally {
            release();
        }
    }


    Node<E> exchangeTail(Node<E> with) {
        return (Node<E>) TAIL.getAndSet(this, with);
    }

    long currSeq() {
        return (long) CURR_SEQ.getOpaque(this);
    }

    void updateSeq(long seq) {
        CURR_SEQ.setOpaque(this, seq);
    }

    boolean casTail(Node<E> from, Node<E> to) {
        return tail == from && TAIL.compareAndSet(this, from, to);
    }


    static final VarHandle TAIL;
    static final VarHandle HEAD;
    static final VarHandle STATE;
    static final VarHandle CURR_SEQ;

    static {
        var lookup = MethodHandles.lookup();
        try {
            CURR_SEQ = lookup.findVarHandle(CombinerCore.class, "currSeq", long.class);
            HEAD = lookup.findVarHandle(CombinerCore.class, "head", Node.class);
            STATE = lookup.findVarHandle(CombinerCore.class, "state", long.class);
            TAIL = lookup.findVarHandle(CombinerCore.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}