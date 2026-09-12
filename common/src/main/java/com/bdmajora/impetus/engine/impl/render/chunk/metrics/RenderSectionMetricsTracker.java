package com.bdmajora.impetus.engine.impl.render.chunk.metrics;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;

public class RenderSectionMetricsTracker {
    public static final Comparator<RenderSection> BY_BUILD_TIME = Comparator.comparingLong(RenderSection::getLastBuildDurationNanos);
    private static final int MAX_TRACKED_SECTIONS = 5;
    private final PriorityQueue<RenderSection> slowestSections = new PriorityQueue<>(BY_BUILD_TIME);
    private final ReferenceOpenHashSet<RenderSection> sectionsInHeap = new ReferenceOpenHashSet<>();

    // Tracks a section on its first build
    private void addSection(RenderSection section) {
        slowestSections.add(section);
        sectionsInHeap.add(section);
    }

    // Forgets an unloaded section
    public void removeSection(RenderSection section) {
        if (sectionsInHeap.remove(section)) {
            slowestSections.remove(section);
        }
    }

    // Records the latest build time
    public void updateSectionBuildDuration(RenderSection section, long duration) {
        removeSection(section);

        section.setLastBuildDurationNanos(duration);

        if (slowestSections.size() < MAX_TRACKED_SECTIONS) {
            addSection(section);
        } else if (duration > slowestSections.peek().getLastBuildDurationNanos()) {
            sectionsInHeap.remove(slowestSections.poll());
            addSection(section);
        }
    }

    // For the debug overlay
    public Collection<RenderSection> getSlowestSections() {
        return Collections.unmodifiableCollection(this.slowestSections);
    }
}
