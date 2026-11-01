package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.display.DisplayTexture;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Texture mirroring the guest screen; only rows that changed are uploaded. */
final class VmScreenTexture implements AutoCloseable {
    private final ResourceLocation id = Virtual_mashine.id("screen/" + UUID.randomUUID());
    private final DisplayTexture texture = new DisplayTexture();
    private boolean registered;

    /** Copies new guest pixels to the GPU. Must run on the render thread. */
    void update(RfbClient client) {
        texture.update(client);
        if (texture.ready() && !registered) {
            Minecraft.getInstance().getTextureManager().register(id, texture);
            registered = true;
        }
    }

    boolean ready() {
        return registered && texture.ready();
    }

    int width() {
        return texture.width();
    }

    int height() {
        return texture.height();
    }

    /** Draws the whole screen into the rectangle; smooth when scaled down, sharp when scaled up. */
    void draw(GuiGraphics graphics, int x, int y, int w, int h, boolean magnified) {
        texture.setSmooth(!magnified);
        graphics.blit(id, x, y, w, h, 0.0F, 0.0F, texture.width(), texture.height(), texture.width(), texture.height());
    }

    @Override
    public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(id);
            registered = false;
        } else {
            texture.close();
        }
    }
}
