/**
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.concurrency.limits.limit.measurement;

import java.util.function.Function;

public class ExpAvgMeasurement implements Measurement {
    private final int    window;
    private final int    warmupWindow;
    private       Double value = 0.0;
    private       Double sum   = 0.0;
    private       int    count = 0;

    public ExpAvgMeasurement(int window, int warmupWindow) {
        this.window = window;
        this.warmupWindow = warmupWindow;
        this.sum = 0.0;
    }

    private static double factor(int n) {
        return 2.0 / (n + 1);
    }

    @Override
    public Number add(Number sample) {
        if (count < warmupWindow) {
            count++;
            sum += sample.doubleValue();
            value = sum / count;
        } else {
            double factor = factor(window);
            value = value * (1 - factor) + sample.doubleValue() * factor;
        }
        return value;
    }

    @Override
    public Number get() {
        return value;
    }

    @Override
    public void reset() {
        value = 0.0;
        count = 0;
        sum = 0.0;
    }

    @Override
    public void update(Function<Number, Number> operation) {
        this.value = operation.apply(value).doubleValue();
    }
}
