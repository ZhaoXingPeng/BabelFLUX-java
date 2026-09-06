package com.babelflux.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.babelflux.backend.domain.BoundedRevisionWindow;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedRevisionWindowTest {
    @Test
    void evictsOldestEntryInConstantSizeWindow() {
        var window = new BoundedRevisionWindow<Integer>(2);
        window.add(1);
        window.add(2);
        window.add(3);
        assertEquals(List.of(2, 3), window.snapshot());
        assertEquals(2, window.size());
    }

    @Test
    void rejectsInvalidCapacityAndNullValues() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedRevisionWindow<>(0));
        var window = new BoundedRevisionWindow<String>(1);
        assertThrows(NullPointerException.class, () -> window.add(null));
    }
}
