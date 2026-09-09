package com.bdmajora.coartatio.gui;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.MemoryReport;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /coartatio — prints what the memory subsystem has saved, in megabytes
// Registered client-side, so it works in single-player and on any server, including servers without the mod
// The output has two sections. The first is per-feature savings, and each line says whether its number was
// MEASURED (texture pixel data and the class loader cache, where the released arrays were summed before being
// dropped) or ESTIMATED (a shared-object count times a per-object size)
// The second is the live heap. That is the figure to compare against a launch with the mod disabled, which is
// the only honest before-and-after available, and the reason both sections are printed together rather than the
// estimates being presented on their own
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
