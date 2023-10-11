package com.hellblazer.archipelago;

import org.slf4j.Logger;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * @author hal.hildebrand
 **/
public class Wrapped {

    public static <T> Callable<T> wrapped(Callable<T> c, Logger log) {
        return () -> {
            try {
                return c.call();
            } catch (Exception e) {
                log.error("Error in call", e);
                throw new IllegalStateException(e);
            }
        };
    }

    public static <T> Consumer<T> wrapped(Consumer<T> c, Logger log) {
        return t -> {
            try {
                c.accept(t);
            } catch (Exception e) {
                log.error("Error in call", e);
                throw new IllegalStateException(e);
            }
        };
    }

    public static Runnable wrapped(Runnable r, Logger log) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                log.error("Error in execution", e);
                throw new IllegalStateException(e);
            }
        };
    }
}
