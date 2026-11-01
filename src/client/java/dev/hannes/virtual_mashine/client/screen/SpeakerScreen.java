package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.net.NetworkManager;
import dev.hannes.virtual_mashine.network.NetPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The volume knob of a speaker: how loud it plays and with that how far it carries. */
public class SpeakerScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF55FF55;
    private static final int[] STEPS = new int[21];

    static {
        for (int i = 0; i < STEPS.length; i++) {
            STEPS[i] = i * 5;
        }
    }

    private NetPayloads.SpeakerView view;
    private int volume;
    private int channel;

    public SpeakerScreen(NetPayloads.SpeakerView view) {
        super(Component.translatable("block.virtual_mashine.speaker"));
        this.view = view;
        this.volume = view.volume();
        this.channel = view.channel();
    }

    public BlockPos speakerPos() {
        return view.pos();
    }

    public void update(NetPayloads.SpeakerView newView) {
        this.view = newView;
    }

    @Override
    protected void init() {
        int w = Math.min(240, width - 20);
        int x = (width - w) / 2;
        int y = height / 2 - 10;
        addRenderableWidget(new ValueSlider(x, y, w, 20, STEPS, volume,
                value -> Component.translatable("virtual_mashine.speaker.volume", value), this::setVolume));
        int third = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.speaker.quiet"), b -> preset(15))
                .bounds(x, y + 26, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.speaker.room"), b -> preset(50))
                .bounds(x + third + 4, y + 26, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.speaker.announce"), b -> preset(100))
                .bounds(x + 2 * (third + 4), y + 26, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("virtual_mashine.speaker.channel." + channel), b -> {
            channel = (channel + 1) % 3;
            send();
            rebuildWidgets();
        }).bounds(x, y + 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private void preset(int value) {
        setVolume(value);
        rebuildWidgets();
    }

    private void setVolume(int value) {
        volume = value;
        send();
    }

    private void send() {
        if (ClientPlayNetworking.canSend(NetPayloads.SpeakerAction.TYPE)) {
            ClientPlayNetworking.send(new NetPayloads.SpeakerAction(view.speaker(), view.pos(), volume, channel));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        int y = height / 2 - 10;
        graphics.drawCenteredString(font, title, width / 2, y - 52, WHITE);
        Component source = view.source().isEmpty()
                ? Component.translatable("virtual_mashine.speaker.no_source")
                : Component.translatable(view.playing() ? "virtual_mashine.speaker.source" : "virtual_mashine.speaker.source_off", view.source());
        graphics.drawCenteredString(font, source, width / 2, y - 36, view.playing() ? GREEN : GRAY);
        Component reach = volume <= 0 ? Component.translatable("virtual_mashine.speaker.muted")
                : Component.translatable("virtual_mashine.speaker.range", (int) Math.round(NetworkManager.speakerRange(volume)));
        graphics.drawCenteredString(font, reach, width / 2, y - 20, GRAY);
        graphics.drawCenteredString(font, Component.translatable("virtual_mashine.speaker.hint"), width / 2, y + 80, GRAY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
