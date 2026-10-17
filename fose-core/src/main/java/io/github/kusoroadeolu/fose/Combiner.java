package io.github.kusoroadeolu.fose;

import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Function;

public class Combiner<E> extends CombinerCore<E> {

    public Combiner(E item) {
        super(item);
    }

    public <R> R apply(Function<? super E, ? extends R> operation) {
        Lock lock = this.lock;

        if (IS_UNIPROCESSOR) {
            lock.lock();
            try {
                return operation.apply(item);
            } catch (Throwable t) {
                throw new CombiningException(t);
            }finally {
                lock.unlock();
            }
        }

        return super.tryCombining(operation, lock);
    }


    public void accept(Consumer<? super E> operation) {
        apply(e -> {
            operation.accept(e);
            return null;
        });
    }

    <R> R doCombine(Object op) {
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

    <R> R doCombineNode(Node<E> node) {
        if (node.done) {
            if (node.err != null) throw new CombiningException(node.err);
            else return (R) node.result;
        }

        final E item = this.item;
        Function<? super E, ? extends R> fn = (Function<E, R>) node.op;
        try {
            node.done = true;
            return fn.apply(item);
        }catch (Throwable t) {
            throw new CombiningException(t);
        } finally {
            combinePass(node);
        }
    }

    <R> boolean doFn(Node<E> node, E e) {
        if (!node.done) {
            try {
                var fn = (Function<E, R>) node.op;
                node.result = fn.apply(e);
                node.op = null; //clear the operation, honestly this
            }catch (Throwable t) {
                node.err = t;
            }

            node.srDone();
            return true;
        }

        return false;
    }

    void combinePass() {
        Node<E> prev = this.head;
        Node<E> curr;
        final E item = this.item;

        int ops = 0;
        while (ops < maxOps && (curr = prev.lvNext()) != null) {
            if (doFn(curr, item)) ++ops;
            prev.next = null;
            prev = curr;
        }

        ++this._passes;
        if (ops > 0) ++this._combined;
        head = prev;
    }

    void combinePass(Node<E> self) {
        Node<E> prev = this.head;
        Node<E> curr;
        final E item = this.item;

        int ops = 0;
        boolean sawSelf = false;
        boolean gap = false;

        for (;;) {
            if (ops >= maxOps) break;

            if ((curr = prev.lvNext()) == null) {
                if (!sawSelf) gap = true; //if we didn't see our node, there's a gap in the queue, we'll continue combining from our node
                break;
            }

            if (!sawSelf && curr == self) sawSelf = true;

            if (doFn(curr, item)) ++ops;

            prev.next = null;
            prev = curr;
        }

        head = prev;


        //hop to our node, never modifies head, also never unlinks as we traverse, so garbage nodes are still reachable from the head
        if (gap) {
            Node<E> start = self;
            while (ops < maxOps && (curr = start.lvNext()) != null) {
                if (doFn(curr, item)) ++ops;
                start = curr;
            }
        }

        ++_passes;
        if (ops > 0) ++_combined;
    }
}
