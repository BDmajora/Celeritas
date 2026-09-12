package com.bdmajora.impetus.umbra.shaderpack;

import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Every program declared by one "dimension" of a pack — on 1.12.2 that is almost always the single world0 directory
// Holds the flattened ProgramSources keyed by ProgramId, the numbered families keyed by ProgramArrayId, and the
// pack's ShaderProperties
// Lookups walk OptiFine's fallback chain rather than returning empty: asking for gbuffers_terrain from a pack that
// never shipped one gives gbuffers_textured_lit, then gbuffers_textured, then gbuffers_basic. That chain is why a
// pack with three shader files can still shade the whole world
public final class ProgramSet {
    private final Map<ProgramId, ProgramSource> programs = new EnumMap<>(ProgramId.class);
    private final Map<ProgramArrayId, ProgramSource[]> programArrays = new EnumMap<>(ProgramArrayId.class);
    private final ShaderProperties properties;

    public ProgramSet(ShaderProperties properties) {
        this.properties = properties;
    }

    void put(ProgramId id, ProgramSource source) {
        this.programs.put(id, source);
    }

    void putArray(ProgramArrayId id, ProgramSource[] sources) {
        this.programArrays.put(id, sources);
    }

    // The pack's properties
    public ShaderProperties getProperties() {
        return this.properties;
    }

    // Resolves a program by id, walking the fallback chain when the requested one is absent OR invalid — an
    // unparseable program falls back the same way a missing one does, so one bad file does not kill a whole phase
    public Optional<ProgramSource> get(ProgramId id) {
        ProgramId current = id;
        while (current != null) {
            ProgramSource source = this.programs.get(current);
            if (source != null && source.isValid()) {
                return Optional.of(source);
            }
            current = current.getFallback();
        }
        return Optional.empty();
    }

    // The source declared for that id specifically, with no fallback — used where "did the pack actually ship
    // this?" is the question, rather than "what should run for this phase?"
    public Optional<ProgramSource> getDirect(ProgramId id) {
        ProgramSource source = this.programs.get(id);
        return (source != null && source.isValid()) ? Optional.of(source) : Optional.empty();
    }

    // The program at that index within a numbered family — composite, deferred, shadowcomp — or empty
    // No fallback chain here: a missing composite3 means the chain stops, not that composite2 runs twice
    public Optional<ProgramSource> get(ProgramArrayId id, int index) {
        ProgramSource[] arr = this.programArrays.get(id);
        if (arr == null || index < 0 || index >= arr.length) {
            return Optional.empty();
        }
        ProgramSource source = arr[index];
        return (source != null && source.isValid()) ? Optional.of(source) : Optional.empty();
    }

    // The composite/deferred/final family, indexed by pass number
    public ProgramSource[] getArray(ProgramArrayId id) {
        return this.programArrays.get(id);
    }

    // Every directly-declared valid program keyed by source name, e.g. gbuffers_terrain or composite2
    // Non-fallback on purpose: this drives compilation, and compiling a fallback under its own name would build the
    // same source several times over
    public Map<String, ProgramSource> collectDeclaredPrograms() {
        Map<String, ProgramSource> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<ProgramId, ProgramSource> e : this.programs.entrySet()) {
            if (e.getValue() != null && e.getValue().isValid()) {
                result.put(e.getKey().getSourceName(), e.getValue());
            }
        }
        for (Map.Entry<ProgramArrayId, ProgramSource[]> e : this.programArrays.entrySet()) {
            ProgramSource[] arr = e.getValue();
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != null && arr[i].isValid()) {
                    result.put(e.getKey().getSourceName(i), arr[i]);
                }
            }
        }
        return result;
    }

    // The directly-declared program names, for reporting what a pack actually ships
    public List<String> listDeclaredPrograms() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<ProgramId, ProgramSource> e : this.programs.entrySet()) {
            if (e.getValue() != null && e.getValue().isValid()) {
                names.add(e.getKey().getSourceName());
            }
        }
        for (Map.Entry<ProgramArrayId, ProgramSource[]> e : this.programArrays.entrySet()) {
            ProgramSource[] arr = e.getValue();
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != null && arr[i].isValid()) {
                    names.add(e.getKey().getSourceName(i));
                }
            }
        }
        return names;
    }
}
