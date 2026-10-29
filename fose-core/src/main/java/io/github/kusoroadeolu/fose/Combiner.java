package io.github.kusoroadeolu.fose;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

class CombinerPad0 extends AbstractOwnableSynchronizer {
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

class CombinerSequenceField<E> extends CombinerPad3<E> {
    long currSeq;

    public CombinerSequenceField(E item, int maxOps, boolean exposeMetrics) {
        super(item, maxOps, exposeMetrics);
    }
}


class CombinerPad4<E> extends CombinerSequenceField<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;


    public CombinerPad4(E item, int maxOps, boolean exposeMetrics) {
        super(item, maxOps, exposeMetrics);
    }
}



/**
 * A synchronization primitive that lets threads apply operations to a shared object on each other’s behalf.
 *
 * <p> In the uncontended case, this primitive behaves no differently than a standard lock, however when contention is detected,
 * other threads can request that the current lock holder apply their request and then wait on the result, periodically
 * trying to become the lock holder if their requests have not been applied yet.
 *
 * <p>An example usage of this class can look like so:
 * <pre> {@code
 *
 *  class ThreadSafePriorityQueue<E> {
 *      private final Combiner combiner = Combiner.builder(new PriorityQueue<E>()).build();
 *
 *      public void push(E e) {
 *          combiner.accept(queue -> queue.offer(e));
 *      }
 *
 *      public E popMinimum() {
 *          return combiner.apply(queue -> queue.poll());
 *      }
 *  }
 *
 * }
 * </pre>
 *
 * <p>An operation may run on a different thread than the one that submitted it, so it should not depend on thread-local state. This class is not reentrant and calling a combining operation within another combining operation can deadlock. Operations are also uninterruptible, the interrupt flag is
 * deferred and is restored on return. Exceptions thrown by an operation are rethrown in the submitting thread as {@link CombiningException}.
 *
 * @param <E> the type of the shared object
 */
public class Combiner<E> extends CombinerPad4<E> {


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
     * There is a brief window between swapping the tail and linking
     * the previous tail's next pointer in which the queue observed
     * by a traversing lock holder may be inconsistent. Hence, a
     * combining pass is exited once the lock holder encounters this
     * inconsistency.
     *
     * Waiters near the head of the queue (judged by how far their sequence number
     * is ahead of the last served operation) use an escalating backoff strategy,
     * from spin waiting to parking. Waiters further from the head, and virtual
     * threads, park immediately.
     *
     * Interrupts are deferred while waiting (park would otherwise return
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

    /**
     * Default maximum number of operations a combiner can serve in one pass
     * */
    static final int DEFAULT_MAX_COMBINING_OPERATIONS = 25;

    /**
     * Number of available cpus
     * */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Max number of waiters in the queue that are allowed to spin
     * */
    static final int MAX_SPINNING_WAITERS = (int) (NCPU * 0.6);


    static final int SPINS = 1 << 10;

    static final int UNPARK_SPINS = 1 << 7;

    Combiner(E item) {
        this(item, DEFAULT_MAX_COMBINING_OPERATIONS, false);
    }

    Combiner(E item, int maxCombiningOperations, boolean exposeMetrics) {
        super(item, maxCombiningOperations, exposeMetrics);
        head = tail = new Node<>(null);
    }

    /**
     * Applies the given operation to the shared object while holding exclusive
     * access, either on the calling thread or, under contention, on another
     * thread, and returns its result.
     *
     * @param operation the operation to apply; may run on a different thread
     * @param <R> the result type
     * @return the value returned by the operation
     * @throws CombiningException if the operation throws
     * @throws NullPointerException if operation is null
     */
    public <R> R apply(Function<? super E, ? extends R> operation) {
        Objects.requireNonNull(operation);

        if (acquire()) return combine(operation);
        return tryCombining(operation);
    }

    /**
     * Like {@link #apply(Function)} for operations with no result.
     * Same threading and exception behavior.
     */
    public void accept(Consumer<? super E> operation) {
        Objects.requireNonNull(operation);
        apply(e -> {
            operation.accept(e);
            return null;
        });
    }

    private  <R> R combine(Object op) {
        Function<? super E, ? extends R> fn = (Function<E, R>) op;
        final E item = this.item;
        R result;

        try {
            result = fn.apply(item);
        }catch (Throwable t) {
            throw new CombiningException(t);
        } finally {
            combinePass();
        }

        return result;
    }

    //Tries to run its own op, if it is not at the head
    //of the queue, its op has been applied, and we notify the next waiter
    //otherwise, combine pass
    private  <R> R combineEnqueued(Node<E> prev, Node<E> node) {
        Node<E> head = getHeadPlain();
        if (head == prev) {
            combinePass();
        } else {
            release();
            notifyNextWaiter(head);
        }

        return result(node);
    }

    private void combinePass() {
        Node<E> prev = getHeadPlain();
        Node<E> curr;
        final E item = this.item;
        Node<E>[] waiters = null;
        int ops = 0;
        int waiting = 0;

        try {
            while (ops < maxOps && (curr = prev.next) != null) {
                @SuppressWarnings("unchecked")
                var fn = (Function<E, Object>) curr.op;

                try {
                    curr.result = fn.apply(item);
                } catch (Throwable t) {
                    curr.err = t;
                }

                prev.next = null; //make visible

                updateSeq(curr.seq); //curr.seq made visible by read of pred.next, a weak write is also alright here

                if (curr.status == Node.PARKING) {
                    if (waiters == null)
                        waiters = new Node[(int) maxOps];

                    waiters[waiting++] = curr;
                }

                ops++;
                prev = curr;
            }

            recordMetrics(ops);
        }finally {
            head = prev;
            release();

            //recheck after release to handle the issue of a missed handoff signal
            //if the waiter is unavailable after setting head = prev, the handoff signal cannot be lost
            notifyNextWaiter(prev);

            if (waiters != null) {
                for (int i = 0; i < waiting; ++i) {
                    Node<E> w = waiters[i];
                    if (w.status == Node.PARKING) {
                        w.unsetStatus(Node.PARKING);
                        LockSupport.unpark(w.waiter);
                    }
                }
            }
        }
    }

    private void notifyNextWaiter(Node<E> prev) {
        Node<E> succ = prev.next;
        Thread next = (succ != null && succ.status == Node.PARKING) ? succ.waiter : null;
        if (next != null) LockSupport.unpark(next);
    }

    void recordMetrics(int ops) {
        if (exposeMetrics) {
            ++this._passes;
            if (ops > 0) {
                ++this._combined;
                _total_ops += ops;
            }
        }
    }

    private  <R> R tryCombining(Object op) {
        boolean wasInterrupted = false;
        Node<E> node = null, prev = null;
        Thread w = null;

        try {
            boolean first = false;
            long seenSeq;
            long remSpins = SPINS;
            int status;


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
                    node.waiter = w = Thread.currentThread();

                    if (!casTail(t, node)) {
                        node.waiter = null;
                        continue;
                    }

                    prev = t;
                    prev.next = node;

                } else if ((status = node.status) == 0) {
                    int s;

                    seenSeq = currSeq();
                    node.status = s = !w.isVirtual() && shouldSpin(node, seenSeq) ?
                            Node.SPINNING : Node.PARKING;

                    if (s == Node.SPINNING && remSpins <= 0) remSpins = UNPARK_SPINS;
                    //try to spin briefly if we've exhausted
                    //our spin budget but are near the head of the queue
                    //usually, a thread takes this branch after it's been unparked

                } else if (status == Node.SPINNING) {

                    if (remSpins > 0) {
                        for (long spins = 0; spins < remSpins; ++spins, --remSpins) {

                            if (prev.next == null) break o;

                            if (head == prev) continue o;      // we're first, try acquire

                            //we could a weaker mode here? backed by a full fence before the loop?

                            Thread.onSpinWait();
                        }

                    } else {
                        node.status = Node.PARKING; // budget spent, park
                    }


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

    private  <R> R result(Node<E> node) {
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
        node.waiter = null;
        node.result = null;
    }

    private boolean acquire() {
        if (state == FREE && (long) STATE.getAndAdd(this, 1) == FREE) {
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }

        return false;
    }

    private void release() {
        setExclusiveOwnerThread(null);
        state = 0;
    }

    private Node<E> getHeadPlain() {
        return (Node<E>) HEAD.get(this);
    }

    private long currSeq() {
        return (long) CURR_SEQ.getOpaque(this);
    }

    private void updateSeq(long seq) {
        CURR_SEQ.setOpaque(this, seq);
    }

    private boolean casTail(Node<E> from, Node<E> to) {
        return tail == from && TAIL.compareAndSet(this, from, to);
    }


    //For BENCHMARKS
    CombinerMetrics metrics() {
        while (!acquire()) Thread.onSpinWait();
        var h = getHeadPlain();
        try {
            return new CombinerMetrics(_total_ops, _passes, _combined, _passes == 0 ? 0 : (double) _total_ops / _passes);
        }finally {
            release();
            notifyNextWaiter(h);
        }
    }

    void resetMetrics() {
        while (!acquire()) Thread.onSpinWait();
        var h = getHeadPlain();
        try {
            _total_ops = 0;
            _passes = 0;
            _combined = 0;
        }finally {
            release();
            notifyNextWaiter(h);
        }
    }

    /**
     * Returns a builder for a {@code Combiner} guarding the given shared object.
     *
     * @param item the shared object; must not be null
     * @throws NullPointerException if item is null
     */
    public static <E> Builder<E> builder(E item) {
        return new Builder<>(item);
    }

    /**
     * Builder for {@link Combiner}. Unset options use the defaults.
     */
    public static final class Builder<E> {
        private final E item;
        private int maxCombiningOperations = DEFAULT_MAX_COMBINING_OPERATIONS;

        private Builder(E item) {
            this.item = Objects.requireNonNull(item, "Item cannot be null");
        }

        /**
         * Sets the maximum number of operations the lock holder may serve in one pass.
         *
         * @throws IllegalArgumentException if {@code max} is not positive
         */
        public Builder<E> maxCombiningOperations(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxCombiningOperations must be greater than zero, got " + max);
            }
            this.maxCombiningOperations = max;
            return this;
        }


        public Combiner<E> build() {
            return new Combiner<>(item, maxCombiningOperations, false);
        }
    }

    static final VarHandle TAIL;
    static final VarHandle HEAD;
    static final VarHandle STATE;
    static final VarHandle CURR_SEQ;

    static {
        var lookup = MethodHandles.lookup();
        try {
            CURR_SEQ = lookup.findVarHandle(Combiner.class, "currSeq", long.class);
            HEAD = lookup.findVarHandle(Combiner.class, "head", Node.class);
            STATE = lookup.findVarHandle(Combiner.class, "state", long.class);
            TAIL = lookup.findVarHandle(Combiner.class, "tail", Node.class);
        }catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}