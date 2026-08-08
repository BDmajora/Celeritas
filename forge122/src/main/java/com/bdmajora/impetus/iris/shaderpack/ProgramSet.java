package com.bdmajora.impetus.iris.shaderpack;

import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of programs declared by one "dimension" of a shader pack (1.12.2 packs nearly always have a single
 * world directory, {@code world0}). Holds the flattened {@link ProgramSource}s keyed by {@link ProgramId} and the
 * numbered families keyed by {@link ProgramArrayId}, plus the pack's {@link ShaderProperties}.
 * <p>
 * Lookups honor OptiFine's fallback chain: requesting {@code gbuffers_terrain} when the pack didn't supply it returns
 * {@code gbuffers_textured_lit}, then {@code gbuffers_textured}, then {@code gbuffers_basic}.
 */
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

    public ShaderProperties getProperties() {
        return this.properties;
    }

    /**
     * Resolves a program by id, walking the OptiFine fallback chain when the requested program is absent or invalid.
     */
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

    /** @return the directly-declared source for {@code id}, without walking the fallback chain. */
    public Optional<ProgramSource> getDirect(ProgramId id) {
        ProgramSource source = this.programs.get(id);
        return (source != null && source.isValid()) ? Optional.of(source) : Optional.empty();
    }

    /**
     * @return the program at {@code index} within a numbered family ({@code composite}, {@code deferred},
     * {@code shadowcomp}), or empty if not present.
     */
    public Optional<ProgramSource> get(ProgramArrayId id, int index) {
        ProgramSource[] arr = this.programArrays.get(id);
        if (arr == null || index < 0 || index >= arr.length) {
            return Optional.empty();
        }
        ProgramSource source = arr[index];
        return (source != null && source.isValid()) ? Optional.of(source) : Optional.empty();
    }

    public ProgramSource[] getArray(ProgramArrayId id) {
        return this.programArrays.get(id);
    }

    /**
     * @return every directly-declared (non-fallback) valid program, keyed by its source name (e.g.
     * {@code gbuffers_terrain}, {@code composite2}). Used by the pipeline to compile the pack's programs.
     */
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

    /** @return the directly-declared (non-fallback) program names, for diagnostics. */
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
