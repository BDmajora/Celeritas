package com.bdmajora.impetus.api.options.structure;

import com.bdmajora.impetus.api.OptionGroupConstructionEvent;
import com.bdmajora.impetus.api.options.OptionIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OptionGroup {
    public static final OptionIdentifier<Void> DEFAULT_ID = OptionIdentifier.create("impetus", "empty");

    private final List<Option<?>> options;

    public final OptionIdentifier<Void> id;
    private OptionGroup(OptionIdentifier<Void> id, List<Option<?>> options) {
        this.id = id;
        this.options = options;
    }

    // Group id
    public OptionIdentifier<Void> getId() {
        return id;
    }

    // Starts a builder
    public static Builder createBuilder() {
        return new Builder();
    }

    // In declaration order
    public List<Option<?>> getOptions() {
        return this.options;
    }

    public static class Builder {
        private final List<Option<?>> options = new ArrayList<>();

        private OptionIdentifier<Void> id;

        // Required
        public Builder setId(OptionIdentifier<Void> id) {
            this.id = id;

            return this;
        }

        // Appends
        public Builder add(Option<?> option) {
            this.options.add(option);

            return this;
        }

        // Appends only when the condition holds, without building otherwise
        public Builder addConditionally(boolean shouldAdd, Supplier<Option<?>> option) {
            if (shouldAdd) {
                add(option.get());
            }

            return this;
        }

        // Fires the construction event so other mods can add options, then finalises
        public OptionGroup build() {
            if (this.id == null) {
                this.id = OptionGroup.DEFAULT_ID;
                // FIXME actually enforce IDs on groups
            }

            OptionGroupConstructionEvent.BUS.post(new OptionGroupConstructionEvent(this.id, this.options));

            return new OptionGroup(this.id, List.copyOf(this.options));
        }
    }
}
