package com.bdmajora.impetus.engine.impl.util;

public interface Point2i {
    Point2i ZERO = new Point2i() {
        // x
        @Override
        public int x() {
            return 0;
        }
        // y
        @Override
        public int y() {
            return 0;
        }
    };

    int x();

    int y();
}
