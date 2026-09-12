package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;
import com.bdmajora.impetus.umbra.shaderpack.option.values.MutableOptionValues;
import com.bdmajora.impetus.umbra.shaderpack.option.values.OptionValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Discovers, merges and applies the pack's options across every source file, adapted from Iris
// Impetus flattens includes after option application, so this edits the raw source map and hands IncludeProcessor
// the result. #define references are taken pack-wide rather than per include component: a safe over-approximation
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

    // Every option the pack declares
    public OptionSet getOptionSet() {
        return optionSet;
    }

    // Current values
    public OptionValues getOptionValues() {
        return optionValues;
    }

    // The pack's sources with every option edit already applied: define toggles flipped, const and value
    // assignments rewritten. This is what the include flattener consumes
    public Map<AbsolutePackPath, String> getEditedSources() {
        return editedSources;
    }
}
