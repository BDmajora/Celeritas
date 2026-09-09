package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.umbra.shaderpack.option.values.MutableOptionValues;
import com.bdmajora.impetus.umbra.shaderpack.option.values.OptionValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Discovers, merges and applies the pack's configurable options across every source file it ships
// Adapted from Iris's ShaderPackOptions, but the pipeline order differs. Iris works on an IncludeGraph: options are
// discovered on per-file un-flattened source and applied lazily as include-time line transforms, scoped to each
// weakly-connected include component. Impetus flattens includes textually AFTER option application, so this works
// directly on the raw source map and hands IncludeProcessor a map of already-EDITED sources to flatten
// One consequence of having no include graph: a boolean #define option is only confirmed configurable when its name
// is referenced by an #ifdef or #ifndef somewhere, and the reference set is taken across the WHOLE pack rather than
// per connected component. That is a safe over-approximation — it can expose an extra boolean option that nothing
// in that component reads, but it can never corrupt source — and it matches OptiFine's permissive behaviour
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

    // The pack's sources with every option edit already applied: define toggles flipped, const and value
    // assignments rewritten. This is what the include flattener consumes
    public Map<AbsolutePackPath, String> getEditedSources() {
        return editedSources;
    }
}
