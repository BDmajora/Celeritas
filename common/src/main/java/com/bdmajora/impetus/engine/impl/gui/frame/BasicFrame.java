package com.bdmajora.impetus.engine.impl.gui.frame;

import com.bdmajora.impetus.engine.impl.gui.widgets.AbstractWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class BasicFrame extends AbstractFrame {

    protected List<Function<Dim2i, AbstractWidget>> functions;

    public BasicFrame(Dim2i dim, boolean renderOutline, List<Function<Dim2i, AbstractWidget>> functions) {
        super(dim, renderOutline);
        this.functions = functions;
        this.buildFrame();
    }

    // Starts a builder
    public static Builder createBuilder() {
        return new Builder();
    }

    // Instantiates each child factory at the frame's dimensions
    @Override
    public void buildFrame() {
        this.children.clear();
        this.drawable.clear();
        this.controlElements.clear();

        this.functions.forEach(function -> this.children.add(function.apply(dim)));

        super.buildFrame();
    }

    public static class Builder {
        private final List<Function<Dim2i, AbstractWidget>> functions = new ArrayList<>();
        private Dim2i dim;
        private boolean renderOutline;

        // Frame bounds
        public Builder setDimension(Dim2i dim) {
            this.dim = dim;
            return this;
        }

        // Debug outline
        public Builder shouldRenderOutline(boolean renderOutline) {
            this.renderOutline = renderOutline;
            return this;
        }

        // A child built lazily from the frame's dimensions
        public Builder addChild(Function<Dim2i, AbstractWidget> function) {
            this.functions.add(function);
            return this;
        }

        // Finalises
        public BasicFrame build() {
            Objects.requireNonNull(this.dim, "Dimension must be specified");

            return new BasicFrame(this.dim, this.renderOutline, this.functions);
        }
    }
}