package com.bdmajora.impetus.umbra.uniforms.custom;

/** A parsed custom-uniform expression that can be evaluated against a resolution context. */
@FunctionalInterface
public interface CompiledExpression {
    CustomUniformValue evaluate(CustomUniformContext context);
}
