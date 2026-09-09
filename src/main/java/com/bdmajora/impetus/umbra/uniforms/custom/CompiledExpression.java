package com.bdmajora.impetus.umbra.uniforms.custom;

// A custom-uniform expression that has already been parsed, ready to evaluate against a resolution context
// Compiling to one of these once at pack load is what keeps the per-frame cost to a tree walk instead of a reparse
@FunctionalInterface
public interface CompiledExpression {
    CustomUniformValue evaluate(CustomUniformContext context);
}
