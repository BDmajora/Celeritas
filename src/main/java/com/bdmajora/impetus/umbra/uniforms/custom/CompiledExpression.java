package com.bdmajora.impetus.umbra.uniforms.custom;

// A custom-uniform expression already parsed and ready to evaluate against a context; compiling once at pack load keeps the per-frame cost to a tree walk
@FunctionalInterface
public interface CompiledExpression {
    CustomUniformValue evaluate(CustomUniformContext context);
}
