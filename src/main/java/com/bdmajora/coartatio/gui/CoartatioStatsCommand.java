package com.bdmajora.coartatio.gui;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.MemoryReport;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /coartatio} — prints what the memory subsystem has saved, in megabytes.
 *
 * <p>Registered client-side, so it works in single-player and on any server without the mod.
 *
 * <p>Two sections. The first is per-feature savings; each line says whether the number was
 * <b>measured</b> (texture pixel data and the class loader cache, where the released arrays were
 * summed before release) or <b>estimated</b> (a shared-object count times a per-object size). The
 * second is the live heap, which is what to compare across a launch with the mod disabled — that is
 * the only honest way to get a true before-and-after, and it is why the figures are printed together.
 */
public class CoartatioStatsCommand extends CommandBase {
    @Override
    public String getName() {
        return "coartatio";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/coartatio — report memory saved by Impetus' memory subsystem";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : MemoryReport.lines()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }

        for (String line : Coartatio.statistics()) {
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + line));
        }
    }
}
