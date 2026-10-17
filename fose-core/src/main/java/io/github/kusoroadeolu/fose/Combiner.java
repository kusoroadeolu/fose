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
        R result = fn.apply(item);
        combinePass();
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
            combinePass();
        }
    }

    <R> void doFn(Node<E> node, E e) {
        if (!node.done) {
            try {
                var fn = (Function<E, R>) node.op;
                node.result = fn.apply(e);
                node.op = null;
            }catch (Throwable t) {
                node.err = t;
            }

            node.srDone();
        }
    }

    void combinePass() {
        Node<E> prev = this.head;
        Node<E> curr;
        final E item = this.item;

        for (int ops = 0; ops < maxOps && (curr = prev.lvNext()) != null; ++ops) {
            doFn(curr, item);
            prev.next = null;
            prev = curr;
        }

        ++this._passes;
        if (head == prev) ++this._uncombined;
        head = prev;
    }
}
