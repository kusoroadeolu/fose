package io.github.kusoroadeolu.fose;

public class CombiningException extends RuntimeException {
    public CombiningException(Throwable t) {
        super(t);
    }
}
