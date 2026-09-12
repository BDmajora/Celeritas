package com.bdmajora.impetus.engine.impl.util.collections.quadtree;

import java.util.Objects;

public class Rect2i {
    protected final int x;
    protected final int y;
    protected final int width;
    protected final int height;

    public Rect2i(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Rect2i(Rect2i other) {
        this(other.x(), other.y(), other.width(), other.height());
    }

    // Inclusive of the origin, exclusive of the far edge
    public boolean contains(int x, int y) {
        return x >= this.x && y >= this.y && x < this.x + this.width && y < this.y + this.height;
    }

    public boolean contains(Rect2i r2)
    {
        Rect2i r1 = this;
        return r1.contains(r2.x(), r2.y()) && r1.contains(r2.x() + r2.width() - 1, r2.y() + r2.height() - 1);
    }

    // Left
    public int x() {
        return x;
    }

    // Top
    public int y() {
        return y;
    }

    // Width
    public int width() {
        return width;
    }

    // Height
    public int height() {
        return height;
    }

    // By all four fields
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Rect2i) obj;
        return this.x == that.x &&
                this.y == that.y &&
                this.width == that.width &&
                this.height == that.height;
    }

    // By all four fields
    @Override
    public int hashCode() {
        return Objects.hash(x, y, width, height);
    }

    // For debugging
    @Override
    public String toString() {
        return "Rect2i[" +
                "x=" + x + ", " +
                "y=" + y + ", " +
                "width=" + width + ", " +
                "height=" + height + ']';
    }

}
