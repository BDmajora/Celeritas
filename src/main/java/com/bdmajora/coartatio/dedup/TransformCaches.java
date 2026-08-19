package com.bdmajora.coartatio.dedup;

import com.bdmajora.coartatio.CoartatioConfig;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;

import java.util.Objects;

/**
 * Bake-scoped pools for the two objects every baked model carries but almost never varies.
 *
 * <p>This is the tractable core of FoamFix's {@code geDeduplicate}. FoamFix walks the whole baked
 * model graph reflectively and shares every structurally identical leaf; the overwhelming majority
 * of what it finds is these two, because a model's camera transforms come from its JSON parent and
 * its override list is empty unless the model declares overrides.
 *
 * <p>Doing it at construction rather than by a post-bake graph walk keeps the code honest — there is
 * no reflection, nothing to keep in sync with Forge's model classes, and the sharing happens before
 * the duplicates are ever reachable.
 */
public final class TransformCaches {
    /**
     * {@code ItemCameraTransforms} has no {@code equals}, so identical transform blocks from
     * different model files never compare equal on their own. The strategy compares the eight
     * vectors it holds.
     */
    private static final Hash.Strategy<ItemCameraTransforms> TRANSFORMS_STRATEGY =
            new Hash.Strategy<ItemCameraTransforms>() {
                @Override
                public int hashCode(ItemCameraTransforms transforms) {
                    if (transforms == null) {
                        return 0;
                    }

                    int hash = vectorHash(transforms.thirdperson_left);
                    hash = 31 * hash + vectorHash(transforms.thirdperson_right);
                    hash = 31 * hash + vectorHash(transforms.firstperson_left);
                    hash = 31 * hash + vectorHash(transforms.firstperson_right);
                    hash = 31 * hash + vectorHash(transforms.head);
                    hash = 31 * hash + vectorHash(transforms.gui);
                    hash = 31 * hash + vectorHash(transforms.ground);
                    return 31 * hash + vectorHash(transforms.fixed);
                }

                @Override
                public boolean equals(ItemCameraTransforms a, ItemCameraTransforms b) {
                    if (a == b) {
                        return true;
                    }
                    if (a == null || b == null) {
                        return false;
                    }

                    return Objects.equals(a.thirdperson_left, b.thirdperson_left)
                            && Objects.equals(a.thirdperson_right, b.thirdperson_right)
                            && Objects.equals(a.firstperson_left, b.firstperson_left)
                            && Objects.equals(a.firstperson_right, b.firstperson_right)
                            && Objects.equals(a.head, b.head)
                            && Objects.equals(a.gui, b.gui)
                            && Objects.equals(a.ground, b.ground)
                            && Objects.equals(a.fixed, b.fixed);
                }
            };

    public static final DeduplicationCache<ItemCameraTransforms> TRANSFORMS =
            new DeduplicationCache<>("Camera transforms", CoartatioConfig.get().poolSizeLimit, TRANSFORMS_STRATEGY);

    private TransformCaches() {
    }

    private static int vectorHash(ItemTransformVec3f vector) {
        return vector == null ? 0 : vector.hashCode();
    }

    /**
     * Override lists are shared by identity against the canonical empty one, then pooled.
     *
     * <p>{@code ItemOverrideList.NONE} already exists for the empty case, but a model built from
     * JSON with no {@code overrides} block still gets a fresh instance wrapping an empty list.
     */
    public static ItemOverrideList deduplicate(ItemOverrideList overrides) {
        if (overrides == null) {
            return null;
        }

        return overrides.getOverrides().isEmpty() ? ItemOverrideList.NONE : overrides;
    }

    public static void open() {
        TRANSFORMS.open();
    }

    public static void close() {
        TRANSFORMS.close();
    }
}
