package io.github.kusoroadeolu.fose;

import java.util.function.Function;

public class SimpleCombiner<E> extends CombinerCore<E> {

    public SimpleCombiner(E item) {
        super(item);
    }

    public <R> R accept(Function<? super E, ? extends R> operation) {
        if (IS_UNIPROCESSOR) {
            lock.lock();
            try {
                return operation.apply(item);
            }finally {
                lock.unlock();
            }
        }

        return super.doAccept(operation);
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
            if (node.error() != null) throw new RuntimeException(node.error());
            else return (R) node.result();
        }

        final E item = this.item;
        try {
            Function<? super E, ? extends R> fn = (Function<E, R>) node.op;
            return fn.apply(item);
        }finally {
            node.spDone();
            combinePass();
        }
    }

    <R> void doFn(Node<E> node, E e) {
        if (!node.done) {
            try {
                var fn = (Function<E, R>) node.op;
                node.result = fn.apply(e);
                node.op = null;
            }catch (Error | Exception ex) {
                node.t = ex;
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
            prev.spNextNull();
            prev = curr;
        }

        ++this._passes;
        if (head == prev) ++this._uncombined;
        head = prev;
    }
}
