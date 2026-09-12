package com.bdmajora.impetus.engine.impl.util;

public record Dim2i(int x, int y, int width, int height) implements Point2i {
    // Right edge, exclusive
    public int getLimitX() {
        return this.x + this.width;
    }

    // Bottom edge, exclusive
    public int getLimitY() {
        return this.y + this.height;
    }

    // Hit test
    public boolean containsCursor(double x, double y) {
        return x >= this.x && x < this.getLimitX() && y >= this.y && y < this.getLimitY();
    }

    // Horizontal centre
    public int getCenterX() {
        return this.x + (this.width / 2);
    }

    // Vertical centre
    public int getCenterY() {
        return this.y + (this.height / 2);
    }

    // Copy with a new height
    public Dim2i withHeight(int newHeight) {
        return new Dim2i(x, y, width, newHeight);
    }

    // Copy with a new width
    public Dim2i withWidth(int newWidth) {
        return new Dim2i(x, y, newWidth, height);
    }

    // Copy at a new x
    public Dim2i withX(int newX) {
        return new Dim2i(newX, y, width, height);
    }

    // Copy at a new y
    public Dim2i withY(int newY) {
        return new Dim2i(x, newY, width, height);
    }

    // Whether the other fits inside by size alone
    public boolean canFitDimension(Dim2i anotherDim) {
        return this.x() <= anotherDim.x() && this.y() <= anotherDim.y() && this.getLimitX() >= anotherDim.getLimitX() && this.getLimitY() >= anotherDim.getLimitY();
    }

    // Axis-aligned overlap test
    public boolean overlapsWith(Dim2i other) {
        return this.x() < other.getLimitX() && this.getLimitX() > other.x() && this.y() < other.getLimitY() && this.getLimitY() > other.y();
    }

    // Translated by a parent's origin
    public Dim2i withParentOffset(Point2i parent) {
        return new Dim2i(parent.x() + x, parent.y() + y, width, height);
    }
}
