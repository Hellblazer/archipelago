package com.netflix.concurrency.limits.limit.functions;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SquareRootFunctionTest {
    @Test
    public void confirm0Index() {
        Function<Integer, Integer> func = SquareRootFunction.create(4);
        assertEquals(4, func.apply(0).intValue());
    }

    @Test
    public void confirmMaxIndex() {
        Function<Integer, Integer> func = SquareRootFunction.create(4);
        assertEquals(31, func.apply(999).intValue());
    }

    @Test
    public void confirmOutofLookupRange() {
        Function<Integer, Integer> func = SquareRootFunction.create(4);
        assertEquals(50, func.apply(2500).intValue());
    }
}
