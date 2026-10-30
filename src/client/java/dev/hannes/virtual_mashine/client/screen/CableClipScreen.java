package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Menu of a cable clip: every cable hanging from it with both ends, each with a button to take it off. */
public class CableClipScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int YELLOW = 0xFFFFD866;
    private static final int ROW = 26;
    private static final int TOP = 44;

    private NetPayloads.ClipView view;
    private String message = "";
    private long messageAt;
    private int left;
    private int panelW;

    public CableClipScreen(NetPayloads.ClipView view) {
        super(Component.translatable("block.virtual_mashine.cable_clip"));
        this.view = view;
    }

    public BlockPos clipPos() {
        return view.pos();
    }

    public void update(NetPayloads.ClipView newView) {
        this.view = newView;
        if (!newView.message().isEmpty()) {
            message = newView.message();
            messageAt = System.currentTimeMillis();
        }
        rebuildWidgets();
    }

    private int visibleRows() {
        return Math.max(1, Math.min(view.cables().size(), (height - TOP - 40) / ROW));
    }

    @Override
    protected void init() {
        panelW = Math.min(360, width - 20);
        left = (width - panelW) / 2;
        for (int i = 0; i < Math.min(view.cables().size(), visibleRows()); i++) {
            NetPayloads.ClipCable cable = view.cables().get(i);
            addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.clip.unhook"), b -> unhook(cable))
                    .bounds(left + panelW - 80, TOP + i * ROW + 1, 80, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private void unhook(NetPayloads.ClipCable cable) {
        if (ClientPlayNetworking.canSend(NetPayloads.ClipAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.ClipAction(view.pos(), cable.cable()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(font, Component.translatable("virtual_mashine.clip.heading", view.cables().size()), width / 2, 14, WHITE);
        graphics.drawCenteredString(font, Component.translatable("virtual_mashine.clip.hint"), width / 2, 27, GRAY);
        if (view.cables().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("virtual_mashine.clip.empty"), width / 2, TOP + 6, GRAY);
        }
        int textW = panelW - 80 - 14;
        int rows = Math.min(view.cables().size(), visibleRows());
        for (int i = 0; i < rows; i++) {
            NetPayloads.ClipCable cable = view.cables().get(i);
            int y = TOP + i * ROW;
            graphics.fill(left, y + 2, left + 6, y + 20, 0xFF000000 | cable.color());
            String in = Component.translatable("virtual_mashine.clip.in", cable.from()).getString();
            String out = Component.translatable("virtual_mashine.clip.out", cable.to()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(in, textW), left + 10, y + 2, WHITE);
            graphics.drawString(font, font.plainSubstrByWidth(out, textW), left + 10, y + 12, WHITE);
        }
        if (view.cables().size() > rows) {
            graphics.drawCenteredString(font, Component.translatable("virtual_mashine.clip.more", view.cables().size() - rows),
                    width / 2, TOP + rows * ROW + 2, GRAY);
        }
        if (!message.isEmpty() && System.currentTimeMillis() - messageAt < 5000) {
            graphics.drawCenteredString(font, Component.translatable(message), width / 2, height - 42, YELLOW);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
