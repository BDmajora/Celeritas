package com.bdmajora.impetus.impl.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.engine.impl.gui.ImpetusVideoOptionsController;
import com.bdmajora.impetus.engine.impl.gui.frame.tab.Tab;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.options.CommonOptionPages;
import com.bdmajora.impetus.engine.impl.render.ShaderModBridge;
import org.lwjgl.input.Mouse;
import com.bdmajora.impetus.ImpetusVintage;

import java.io.IOException;
import java.util.*;

public class ImpetusVideoOptionsScreen extends GuiScreen {
    private final GuiScreen prevScreen;
    private final ImpetusVideoOptionsController controller;

    private int lastMouseX, lastMouseY;

    public ImpetusVideoOptionsScreen(GuiScreen prevScreen) {
        super();
        this.prevScreen = prevScreen;
        this.controller = new ImpetusVideoOptionsController(() -> this.mc.displayGuiScreen(this.prevScreen), Arrays.asList(
                ImpetusGameOptionPages.general(),
                ImpetusGameOptionPages.quality(),
                CommonOptionPages.performance(ImpetusVintage.options()),
                ImpetusGameOptionPages.advanced()
        ), new VintageDrawContext()) {
            @Override
            protected void createExtraTabs(Map<String, List<Tab<?>>> tabs) {
                if(ShaderModBridge.isShaderModPresent()) {
                    tabs.computeIfAbsent(ImpetusVintage.MODID, $ -> new ArrayList<>()).add(Tab.createBuilder()
                            .setTitle(TextComponent.translatable("options.iris.shaderPackSelection"))
                            .setId(OptionIdentifier.create(ImpetusVintage.MODID, "shader_packs"))
                            .setOnSelectFunction(() -> {
                                if(ShaderModBridge.openShaderScreen(this) instanceof GuiScreen screen) {
                                    Minecraft.getMinecraft().displayGuiScreen(screen);
                                }
                                return false;
                            })
                            .build());
                }
            }

            @Override
            protected void applyFlagSideEffects(Set<OptionFlag> flags) {
                super.applyFlagSideEffects(flags);

                Minecraft client = Minecraft.getMinecraft();

                if (client.world != null) {
                    if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD)) {
                        client.renderGlobal.loadRenderers();
                    } else if (flags.contains(OptionFlag.REQUIRES_RENDERER_UPDATE)) {
                        client.renderGlobal.setDisplayListEntitiesDirty();
                    }
                }

                if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
                    client.getTextureMapBlocks().setMipmapLevels(mc.gameSettings.mipmapLevels);
                    client.refreshResources();
                }
            }
        };
        resetDrag();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        this.controller.getFrame().mouseClicked(VintageInteractionContext.INSTANCE, mouseX, mouseY, mouseButton);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int mouseButton) {
        this.controller.getFrame().mouseReleased(VintageInteractionContext.INSTANCE, mouseX, mouseY, mouseButton);
        resetDrag();
    }

    private void resetDrag() {
        lastMouseX = -1;
        lastMouseY = -1;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);

        if (lastMouseY != -1 && lastMouseX != -1) {
            int dx = mouseX - lastMouseX;
            int dy = mouseY - lastMouseY;

            this.controller.getFrame().mouseDragged(VintageInteractionContext.INSTANCE, mouseX, mouseY, clickedMouseButton, dx, dy);
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            double scrollDelta = dWheel > 0 ? 1 : -1;
            this.controller.getFrame().mouseScrolled(VintageInteractionContext.INSTANCE, mouseX, mouseY, 0, scrollDelta);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.controller.render(new VintageDrawContext(), mouseX, mouseY, partialTicks);
    }

    @Override
    public void initGui() {
        this.controller.init(this.width, this.height);
    }
}