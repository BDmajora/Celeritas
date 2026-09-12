package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

// Appends the owning mod's name to item tooltips like mezz's ModNameTooltip via ItemTooltipEvent, which also covers JEI/HEI tooltips; EventPriority#LOW puts the line last
@Mod.EventBusSubscriber(Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class ModNameTooltipHandler {
    // Blue italic — the conventional styling for this line.
    private static final String FORMAT = TextFormatting.BLUE.toString() + TextFormatting.ITALIC;

    private ModNameTooltipHandler() {
    }

    // Appends the owning mod's name as the last tooltip line
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!Extras.options().extra.modNameTooltip) {
            return;
        }

        String modName = resolveModName(event.getItemStack());
        if (modName == null || modName.isEmpty()) {
            return;
        }

        List<String> tooltip = event.getToolTip();
        if (!alreadyPresent(tooltip, modName)) {
            tooltip.add(FORMAT + modName);
        }
    }

    // Owning mod via the registry name's namespace; null for empty stacks and unknown namespaces
    @Nullable
    private static String resolveModName(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        Item item = stack.getItem();
        // Honours items that reassign their creator (tools spawned by another mod), falling back to the registry-name namespace
        String modId = item.getCreatorModId(stack);
        if (modId == null) {
            return null;
        }

        ModContainer container = Loader.instance().getIndexedModList().get(modId);
        return container != null ? container.getName() : null;
    }

    // Whether the last line already is the mod name; JEI appends it through the same event, and this stops the two stacking
    private static boolean alreadyPresent(List<String> tooltip, String modName) {
        if (tooltip.size() <= 1) {
            return false;
        }

        String last = TextFormatting.getTextWithoutFormattingCodes(tooltip.get(tooltip.size() - 1));
        return modName.equals(last);
    }
}
