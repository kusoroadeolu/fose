package io.github.kusoroadeolu.fose;

import java.util.ArrayList;

abstract class BatchingCombinerPad0<E> extends CombinerCore<E> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16;

    public BatchingCombinerPad0(E item) {
        super(item);
    }
}

abstract class BatchingCombinerFields<E, Q> extends BatchingCombinerPad0<E> {
    final BatchList<Q, Object> list;
    final BatchHandler<E, Q, Object> handler;
    final ArrayList<Node<E>> waiters;

    BatchingCombinerFields(E item, BatchHandler<E, Q, Object> handler) {
        super(item);
        this.list = new BatchList<>(NCPU);
        this.handler = handler;
        waiters = new ArrayList<>();
    }
}

abstract class BatchingCombinerPad1<E, Q> extends BatchingCombinerFields<E, Q> {
    long a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15;

    BatchingCombinerPad1(E item, BatchHandler<E, Q, Object> handler) {
        super(item, handler);
    }
}

public class BatchingCombiner<E, Q> extends BatchingCombinerPad1<E, Q> {

    BatchingCombiner(E item, BatchHandler<E, Q, Object> handler) {
        super(item, handler);
    }

    public <R> R accept(Q operation) {
        return super.doAccept(operation);
    }

    @Override
    <R> R doCombine(Object o) {
        var list = this.list;
        list.setRequest(0, (Q) o);
        combinePass();
        return notifyWaiters();
    }

    @Override
    <R> R doCombineNode(Node<E> node) {
        if (node.done) {
            if (node.error() != null) throw new RuntimeException(node.error());
            else return (R) node.result();
        }

        var list = this.list;
        list.setRequest(0, (Q) node.op);
        node.spDone(); //mark as done
        combinePass();
        return notifyWaiters();
    }

    @Override
    void combinePass() {
        int index = 1;
        Node<E> prev = this.head;
        Node<E> curr;
        BatchList<Q, Object> list = this.list;
        final E item = this.item;

        for (int ops = 0; ops < maxOps && (curr = prev.lvNext()) != null; ++ops) {

            if (!curr.done) {
                list.setRequest(index++, (Q) curr.op);
                waiters.add(curr);
            }

            prev.spNextNull();
            prev = curr;
        }

        ++this._passes;
        if (head == prev) ++this._uncombined;

        Batch<Q, Object> b = new Batch<>(list);
        head = prev;
        handler.process(b, item);
    }

    <R> R notifyWaiters() {
        BatchList<Q, Object> list = this.list;
        int size = waiters.size();
        for (int index = 0; index < size; ++index) {
            int listIndex = index + 1;
            Node<E> waiter = waiters.get(index);

            if (list.hasErr(listIndex)) waiter.t = list.err(listIndex);
            else waiter.result = list.result(listIndex);

            waiter.srDone();

            list.remove(listIndex);
        }

        waiters.clear();

        if (list.hasErr(0)) {
            var t = list.err(0);
            list.remove(0);
            throw new RuntimeException(t);
        } else {
            var r = list.result(0);
            list.remove(0);
            return (R) r;
        }
    }
}
