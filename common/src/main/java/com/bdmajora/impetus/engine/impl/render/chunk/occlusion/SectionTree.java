package com.bdmajora.impetus.engine.impl.render.chunk.occlusion;

import com.bdmajora.impetus.engine.impl.render.viewport.CameraTransform;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import org.joml.Vector3ic;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * A dynamic loose octree over loaded render sections. The visibility graph is still the authority for occlusion, but
 * this gives non-occlusion traversal a spatial structure instead of walking a camera-rooted graph or scanning empty
 * coordinates.
 */
public final class SectionTree {
    private static final int LEAF_CAPACITY = 8;
    private static final int[] CHILD_ORDER = { 0, 1, 2, 4, 3, 5, 6, 7 };
    private static final float NODE_MARGIN = 1.125f;

    private Node root;
    private int sectionCount;

    public void add(OcclusionNode section) {
        int x = section.getChunkX();
        int y = section.getChunkY();
        int z = section.getChunkZ();

        if (this.root == null) {
            this.root = new Node(x, y, z, 1);
        }

        while (!this.root.contains(x, y, z)) {
            this.root = this.root.expandToward(x, y, z);
        }

        if (this.root.insert(section)) {
            this.sectionCount++;
        }
    }

    public void remove(OcclusionNode section) {
        if (this.root == null) {
            return;
        }

        if (this.root.remove(section)) {
            this.sectionCount--;

            if (this.sectionCount <= 0) {
                this.root = null;
                this.sectionCount = 0;
            }
        }
    }

    public void clear() {
        this.root = null;
        this.sectionCount = 0;
    }

    public boolean isEmpty() {
        return this.sectionCount == 0;
    }

    public void forEachVisible(Viewport viewport, float searchDistance, Consumer<OcclusionNode> consumer) {
        if (this.root != null) {
            this.root.visit(viewport, searchDistance, consumer);
        }
    }

    private static final class Node {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int size;

        private ArrayList<OcclusionNode> sections = new ArrayList<>(LEAF_CAPACITY);
        private Node[] children;

        private Node(int minX, int minY, int minZ, int size) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.size = size;
        }

        private boolean contains(int x, int y, int z) {
            return x >= this.minX && x < this.minX + this.size
                    && y >= this.minY && y < this.minY + this.size
                    && z >= this.minZ && z < this.minZ + this.size;
        }

        private Node expandToward(int x, int y, int z) {
            int newSize = this.size << 1;
            int newMinX = x < this.minX ? this.minX - this.size : this.minX;
            int newMinY = y < this.minY ? this.minY - this.size : this.minY;
            int newMinZ = z < this.minZ ? this.minZ - this.size : this.minZ;

            Node parent = new Node(newMinX, newMinY, newMinZ, newSize);
            parent.children = new Node[8];
            parent.sections = null;
            parent.children[parent.childIndex(this.minX, this.minY, this.minZ)] = this;
            return parent;
        }

        private boolean insert(OcclusionNode section) {
            if (this.children != null) {
                return this.getOrCreateChild(section).insert(section);
            }

            for (int i = 0; i < this.sections.size(); i++) {
                if (this.sections.get(i) == section) {
                    return false;
                }
            }

            this.sections.add(section);

            if (this.size > 1 && this.sections.size() > LEAF_CAPACITY) {
                this.split();
            }

            return true;
        }

        private boolean remove(OcclusionNode section) {
            if (!this.contains(section.getChunkX(), section.getChunkY(), section.getChunkZ())) {
                return false;
            }

            if (this.children != null) {
                int index = this.childIndex(section.getChunkX(), section.getChunkY(), section.getChunkZ());
                Node child = this.children[index];

                if (child == null) {
                    return false;
                }

                boolean removed = child.remove(section);

                if (removed && child.isEmpty()) {
                    this.children[index] = null;
                }

                return removed;
            }

            for (int i = 0; i < this.sections.size(); i++) {
                if (this.sections.get(i) == section) {
                    int last = this.sections.size() - 1;
                    this.sections.set(i, this.sections.get(last));
                    this.sections.remove(last);
                    return true;
                }
            }

            return false;
        }

        private boolean isEmpty() {
            if (this.children != null) {
                for (Node child : this.children) {
                    if (child != null) {
                        return false;
                    }
                }

                return true;
            }

            return this.sections.isEmpty();
        }

        private void split() {
            ArrayList<OcclusionNode> oldSections = this.sections;

            this.children = new Node[8];
            this.sections = null;

            for (int i = 0; i < oldSections.size(); i++) {
                OcclusionNode section = oldSections.get(i);
                this.getOrCreateChild(section).insert(section);
            }
        }

        private Node getOrCreateChild(OcclusionNode section) {
            int index = this.childIndex(section.getChunkX(), section.getChunkY(), section.getChunkZ());
            Node child = this.children[index];

            if (child == null) {
                int half = this.size >> 1;
                child = new Node(
                        this.minX + ((index & 1) != 0 ? half : 0),
                        this.minY + ((index & 2) != 0 ? half : 0),
                        this.minZ + ((index & 4) != 0 ? half : 0),
                        half);
                this.children[index] = child;
            }

            return child;
        }

        private int childIndex(int x, int y, int z) {
            int half = this.size >> 1;
            int midX = this.minX + half;
            int midY = this.minY + half;
            int midZ = this.minZ + half;

            return (x >= midX ? 1 : 0)
                    | (y >= midY ? 2 : 0)
                    | (z >= midZ ? 4 : 0);
        }

        private void visit(Viewport viewport, float searchDistance, Consumer<OcclusionNode> consumer) {
            if (!this.isNodeVisible(viewport, searchDistance)) {
                return;
            }

            if (this.children != null) {
                this.visitChildren(viewport, searchDistance, consumer);
                return;
            }

            for (int i = 0; i < this.sections.size(); i++) {
                OcclusionNode section = this.sections.get(i);

                if (OcclusionCuller.isSectionVisible(section, viewport, searchDistance)) {
                    consumer.accept(section);
                }
            }
        }

        private void visitChildren(Viewport viewport, float searchDistance, Consumer<OcclusionNode> consumer) {
            Vector3ic cameraSection = viewport.getChunkCoord();
            int nearIndex = this.childIndex(cameraSection.x(), cameraSection.y(), cameraSection.z());

            for (int order : CHILD_ORDER) {
                Node child = this.children[nearIndex ^ order];

                if (child != null) {
                    child.visit(viewport, searchDistance, consumer);
                }
            }
        }

        private boolean isNodeVisible(Viewport viewport, float searchDistance) {
            int minBlockX = this.minX << 4;
            int minBlockY = this.minY << 4;
            int minBlockZ = this.minZ << 4;
            int maxBlockX = (this.minX + this.size) << 4;
            int maxBlockY = (this.minY + this.size) << 4;
            int maxBlockZ = (this.minZ + this.size) << 4;

            if (!isWithinRenderDistance(viewport.getTransform(), minBlockX, minBlockY, minBlockZ,
                    maxBlockX, maxBlockY, maxBlockZ, searchDistance)) {
                return false;
            }

            return viewport.isBoxVisible(
                    minBlockX - NODE_MARGIN,
                    minBlockY - NODE_MARGIN,
                    minBlockZ - NODE_MARGIN,
                    maxBlockX + NODE_MARGIN,
                    maxBlockY + NODE_MARGIN,
                    maxBlockZ + NODE_MARGIN);
        }

        private static boolean isWithinRenderDistance(CameraTransform camera, int minX, int minY, int minZ,
                                                      int maxX, int maxY, int maxZ, float maxDistance) {
            int ox = minX - camera.intX;
            int oy = minY - camera.intY;
            int oz = minZ - camera.intZ;
            int px = maxX - camera.intX;
            int py = maxY - camera.intY;
            int pz = maxZ - camera.intZ;

            float dx = nearestToZero(ox, px) - camera.fracX;
            float dy = nearestToZero(oy, py) - camera.fracY;
            float dz = nearestToZero(oz, pz) - camera.fracZ;

            return ((((dx * dx) + (dz * dz)) < (maxDistance * maxDistance)) && (Math.abs(dy) < maxDistance));
        }

        @SuppressWarnings("ManualMinMaxCalculation")
        private static int nearestToZero(int min, int max) {
            int clamped = 0;

            if (min > 0) {
                clamped = min;
            }

            if (max < 0) {
                clamped = max;
            }

            return clamped;
        }
    }
}
