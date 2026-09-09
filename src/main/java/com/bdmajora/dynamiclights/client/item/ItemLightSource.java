package com.bdmajora.dynamiclights.client.item;

import com.bdmajora.dynamiclights.DynamicLights;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Optional;

// how brightly one item glows when held, worn or dropped
// declared in data rather than code - assets/impetus/dynamiclights/item/*.json - so a resource pack
// can add light sources for modded items without touching the mod
public abstract class ItemLightSource {
    private final ResourceLocation id;
    private final Item item;
    private final boolean waterSensitive;

    protected ItemLightSource(ResourceLocation id, Item item, boolean waterSensitive) {
        this.id = id;
        this.item = item;
        this.waterSensitive = waterSensitive;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public Item item() {
        return this.item;
    }

    public boolean waterSensitive() {
        return this.waterSensitive;
    }

    // The luminance of this stack, or zero if it is water-sensitive and currently submerged.
    public int getLuminance(ItemStack stack, boolean submergedInWater) {
        if (this.waterSensitive() && DynamicLights.options().waterSensitiveCheck && submergedInWater) {
            return 0;
        }
        return this.getLuminance(stack);
    }

    public abstract int getLuminance(ItemStack stack);

    @Override
    public String toString() {
        return "ItemLightSource{id=" + this.id + ", item=" + this.item
                + ", water_sensitive=" + this.waterSensitive + '}';
    }

    // parses one light source definition
    // luminance is either a number, the literal "block" (use the block this item places), or a block
    // id (mimic that block's light value)
    public static Optional<ItemLightSource> fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("item") || !json.has("luminance")) {
            DynamicLights.LOGGER.warn("Item light source \"{}\" is missing a required field", id);
            return Optional.empty();
        }

        Item item;
        try {
            item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(json.get("item").getAsString()));
        } catch (Exception e) {
            DynamicLights.LOGGER.warn("Could not parse the item id for \"{}\"", id);
            return Optional.empty();
        }

        // Not an error: a pack may declare sources for items whose mod is not installed.
        if (item == null || item == Items.AIR) {
            return Optional.empty();
        }

        boolean waterSensitive = json.has("water_sensitive") && json.get("water_sensitive").getAsBoolean();
        JsonPrimitive luminance = json.getAsJsonPrimitive("luminance");

        if (luminance.isNumber()) {
            return Optional.of(new StaticItemLightSource(id, item, luminance.getAsInt(), waterSensitive));
        }

        if (!luminance.isString()) {
            DynamicLights.LOGGER.warn("Item light source \"{}\" has a luminance that is neither a number "
                    + "nor a string", id);
            return Optional.empty();
        }

        String value = luminance.getAsString();

        if (value.equals("block")) {
            if (item instanceof ItemBlock) {
                Block block = ((ItemBlock) item).getBlock();
                return Optional.of(new BlockItemLightSource(id, item, block.getDefaultState(), waterSensitive));
            }
            DynamicLights.LOGGER.warn("Item light source \"{}\" uses \"block\" luminance but \"{}\" does not "
                    + "place a block", id, item);
            return Optional.empty();
        }

        try {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(value));
            if (block != null && block != Blocks.AIR) {
                return Optional.of(new BlockItemLightSource(id, item, block.getDefaultState(), waterSensitive));
            }
        } catch (Exception e) {
            DynamicLights.LOGGER.warn("Item light source \"{}\" names an invalid block \"{}\"", id, value);
        }

        return Optional.empty();
    }

    // A fixed luminance, regardless of the stack.
    public static class StaticItemLightSource extends ItemLightSource {
        private final int luminance;

        public StaticItemLightSource(ResourceLocation id, Item item, int luminance, boolean waterSensitive) {
            super(id, item, waterSensitive);
            this.luminance = luminance;
        }

        @Override
        public int getLuminance(ItemStack stack) {
            return this.luminance;
        }
    }

    // Mirrors a block's light value, so a redstone torch item is as bright as a placed one.
    public static class BlockItemLightSource extends ItemLightSource {
        private final IBlockState mimic;

        public BlockItemLightSource(ResourceLocation id, Item item, IBlockState block, boolean waterSensitive) {
            super(id, item, waterSensitive);
            this.mimic = block;
        }

        @Override
        public int getLuminance(ItemStack stack) {
            return getLuminance(stack, this.mimic);
        }

        // the light value of state, with any BlockStateTag on the stack applied first
        // that tag is how a stack can carry a non-default block state - a lit versus unlit redstone
        // torch, for instance - so ignoring it would report the wrong brightness for those stacks
        public static int getLuminance(ItemStack stack, IBlockState state) {
            NBTTagCompound nbt = stack.getTagCompound();

            if (nbt != null && nbt.hasKey("BlockStateTag", 10)) {
                NBTTagCompound blockStateTag = nbt.getCompoundTag("BlockStateTag");

                for (String key : blockStateTag.getKeySet()) {
                    for (IProperty<?> property : state.getPropertyKeys()) {
                        if (property.getName().equals(key)) {
                            state = withValue(state, property, blockStateTag.getString(key));
                            break;
                        }
                    }
                }
            }

            return state.getLightValue();
        }

        private static <T extends Comparable<T>> IBlockState withValue(IBlockState state,
                                                                      IProperty<T> property, String value) {
            for (T allowed : property.getAllowedValues()) {
                if (allowed.toString().equalsIgnoreCase(value)) {
                    return state.withProperty(property, allowed);
                }
            }
            return state;
        }
    }
}
