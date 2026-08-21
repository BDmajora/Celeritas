package com.bdmajora.fulgor.gui;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /fulgor} — prints what the lighting subsystem has been asked to do and how much of it it
 * actually had to do.
 *
 * <p>Registered client-side, so it works in single-player and on any server without the mod. The
 * counters are process-wide rather than per-world: in single-player the client world and the
 * integrated server's world both contribute, which is the total the user's frame time is paying for.
 *
 * <p>The number to watch is the collapsed share. Vanilla, Phosphor and Hesperus would all have
 * evaluated every scheduled position; whatever fraction is collapsed here is lighting work that simply
 * never happened.
 */
public class FulgorStatsCommand extends CommandBase {
    @Override
    public String getName() {
        return "fulgor";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/fulgor — report the work done by Impetus' lighting subsystem";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : Fulgor.statistics()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }
    }
}
