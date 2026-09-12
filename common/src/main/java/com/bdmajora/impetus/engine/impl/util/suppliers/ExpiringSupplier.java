package com.bdmajora.impetus.engine.impl.util.suppliers;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ExpiringSupplier<T> implements Supplier<@NotNull T> {
    private final Supplier<@NotNull T> supplier;
    private final long expiryTime;
    private long lastTime;
    private T lastValue;

    public ExpiringSupplier(Supplier<@NotNull T> supplier, long duration, TimeUnit unit) {
        this.supplier = supplier;
        this.expiryTime = TimeUnit.NANOSECONDS.convert(duration, unit);
    }

    // Recomputes once the cached value is older than the interval
    @Override
    public T get() {
        if (lastValue == null || (System.nanoTime() - lastTime) >= expiryTime) {
            lastValue = supplier.get();
            Objects.requireNonNull(lastValue, "Returned value may not be null");
            lastTime = System.nanoTime();
        }
        return lastValue;
    }
}
