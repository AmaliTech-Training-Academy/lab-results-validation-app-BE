package com.amalitech.labresultsvalidator.infrastructure.graph;

/**
 * Indirection over {@link Thread#sleep(long)} so retry tests can assert on the delays that
 * would have been waited, without actually waiting them.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    static Sleeper real() {
        return Thread::sleep;
    }
}
