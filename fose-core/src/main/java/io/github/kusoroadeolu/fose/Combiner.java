package io.github.kusoroadeolu.fose;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * <p>This class supports optional constructor parameters for tuning the maximum number of operations the lock holder can serve in one pass and exposing certain metrics
 * for instrumentation and monitoring. This can be checked using {@link Combiner#metrics()}.
 *
 *
 * <p>An operation may run on a different thread than the one that submitted it, so it should not depend on thread-local state. This class is not reentrant and calling a combining operation within another combining operation can deadlock. Operations are also uninterruptible, the interrupt flag is
 * deferred and is restored on return. Exceptions thrown by an operation are rethrown in the submitting thread as {@link CombiningException}.
 *
 * @param <E> the type of the shared object
 */
public class Combiner<E> extends CombinerCore<E> {
    Combiner(E item) {
        super(item);
    }

    Combiner(E item, int maxCombiningOperations, boolean exposeMetrics) {
        super(item, maxCombiningOperations, exposeMetrics);
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
        return super.tryCombining(operation);
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

    <R> R combine(Object op) {
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

    <R> R combineEnqueued(Node<E> prev, Node<E> node) {
        if (getHeadPlain() == prev) combinePass();
        else release();
        return result(node);
    }

    void combinePass() {
        Node<E> prev = getHeadPlain();
        Node<E> curr;
        final E item = this.item;
        List<Thread> waiters = null;
        int ops = 0;

        try {
            while (ops < maxOps && (curr = prev.next) != null) {
                @SuppressWarnings("unchecked")
                var fn = (Function<E, Object>) curr.op;

                try {
                    curr.result = fn.apply(item);
                } catch (Throwable t) {
                    curr.err = t;
                }

                prev.next = null;

                updateSeq(curr.seq); //curr.seq made visible by read of pred.next, a weak write is alright here

                if (curr.status == Node.PARKING) {
                    if (waiters == null) waiters = new ArrayList<>((int) (maxOps - ops));
                    curr.unsetStatus(Node.PARKING);
                    waiters.add(curr.waiter);
                }

                ops++;
                prev = curr;
            }

        }finally {

            head = prev;

            Node<E> succ = prev.next;

            Thread next = (succ != null && succ.status == Node.PARKING) ? succ.waiter : null;
            //recheck to handle the issue of a missed handoff signal
            //if the waiter is unavailable after setting head = prev, the handoff signal cannot be lost

            try {
                recordMetrics(ops);
            } finally {
                release();

                if (waiters != null) {
                    for (int i = 0; i < waiters.size(); ++i) {
                        LockSupport.unpark(waiters.get(i));
                    }
                }

                if (next != null) {
                    LockSupport.unpark(next);
                }
            }
        }
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
        private boolean exposeMetrics = false;

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

        /** Enables metrics collection, readable through {@link Combiner#metrics()}. */
        public Builder<E> exposeMetrics() {
            this.exposeMetrics = true;
            return this;
        }


        public Combiner<E> build() {
            return new Combiner<>(item, maxCombiningOperations, exposeMetrics);
        }
    }
}

