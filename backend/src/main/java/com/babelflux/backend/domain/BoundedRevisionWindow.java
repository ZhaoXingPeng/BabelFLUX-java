package com.babelflux.backend.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Fixed-size FIFO window used by online revision; append and eviction are O(1). */
public final class BoundedRevisionWindow<T> {
    private final int capacity;
    private final Deque<T> values;

    public BoundedRevisionWindow(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.values = new ArrayDeque<>(capacity);
    }

    public void add(T value) {
        if (value == null) throw new NullPointerException("value");
        if (values.size() == capacity) values.removeFirst();
        values.addLast(value);
    }

    public List<T> snapshot() { return List.copyOf(new ArrayList<>(values)); }
    public int size() { return values.size(); }
}
