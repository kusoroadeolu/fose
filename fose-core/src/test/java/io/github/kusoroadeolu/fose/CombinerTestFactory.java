package io.github.kusoroadeolu.fose;

public class CombinerTestFactory {
    public static <E> Combiner<E> create(E item) {
        return new Combiner<>(item, 1, false, 0, 0);
    }
}
