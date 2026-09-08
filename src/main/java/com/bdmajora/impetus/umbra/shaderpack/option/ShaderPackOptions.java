package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.umbra.shaderpack.option.values.MutableOptionValues;
import com.bdmajora.impetus.umbra.shaderpack.option.values.OptionValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Discovers, merges, and applies shader-pack options across every source file in the pack.
 * <p>
 * Adapted from Umbra's {@code ShaderPackOptions}. Umbra operates on an {@code IncludeGraph} (options are discovered on
 * per-file un-flattened source, then applied lazily as include-time line transforms, scoped to each weakly-connected
 * include component). Impetus instead flattens includes textually <em>after</em> option application, so this class
 * works directly on the raw source map and produces a map of <em>edited</em> sources that the
 * {@code IncludeProcessor} then flattens.
 * <p>
 * Boolean {@code #define} options are only confirmed as configurable if the define name is referenced by an
 * {@code #ifdef}/{@code #ifndef} somewhere in the pack. Lacking an include graph, we take the union of boolean-define
 * references across the whole pack rather than per-connected-component. This is a safe over-approximation (it can only
 * expose more boolean options, never corrupt source), consistent with OptiFine's permissive behavior.
 */
public class ShaderPackOptions {
    private final OptionSet optionSet;
    private final OptionValues optionValues;
    private final Map<AbsolutePackPath, String> editedSources;

    public ShaderPackOptions(Map<AbsolutePackPath, String> sources, Map<String, String> changedConfigs) {
        Map<AbsolutePackPath, OptionAnnotatedSource> allAnnotations = new HashMap<>();
        Set<String> referencedBooleanDefines = new HashSet<>();

        sources.forEach((path, source) -> {
            OptionAnnotatedSource annotatedSource = new OptionAnnotatedSource(source);
            allAnnotations.put(path, annotatedSource);
            referencedBooleanDefines.addAll(annotatedSource.getBooleanDefineReferences().keySet());
        });

        Set<String> referencedBooleanDefinesU = Collections.unmodifiableSet(referencedBooleanDefines);

        OptionSet.Builder setBuilder = OptionSet.builder();
        allAnnotations.forEach((path, annotatedSource) -> {
            OptionSet set = annotatedSource.getOptionSet(path, referencedBooleanDefinesU);
            setBuilder.addAll(set);
        });

        this.optionSet = setBuilder.build();
        this.optionValues = new MutableOptionValues(optionSet, changedConfigs);

        Map<AbsolutePackPath, String> edited = new HashMap<>();
        allAnnotations.forEach((path, annotatedSource) -> edited.put(path, annotatedSource.apply(optionValues)));
        this.editedSources = Collections.unmodifiableMap(edited);
    }

    public OptionSet getOptionSet() {
        return optionSet;
    }

    public OptionValues getOptionValues() {
        return optionValues;
    }

    /** The pack's source files with option edits (define toggles, const/value rewrites) already applied. */
    public Map<AbsolutePackPath, String> getEditedSources() {
        return editedSources;
    }
}
