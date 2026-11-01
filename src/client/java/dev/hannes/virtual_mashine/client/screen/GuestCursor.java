package dev.hannes.virtual_mashine.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The guest's mouse pointer. QEMU sends hardware cursors (e.g. GNOME's) only as a separate shape,
 * never inside the picture, so it is drawn on top of the guest screen at the mouse position.
 */
final class GuestCursor implements AutoCloseable {
    private final ResourceLocation id = Virtual_mashine.id("cursor/" + UUID.randomUUID());
    private RfbClient.@Nullable Cursor shape;
    private boolean registered;

    /** Takes over a new pointer shape from the guest. Render thread. */
    void update(RfbClient client) {
        client.consumeCursor(this::upload);
    }

    private void upload(RfbClient.Cursor cursor) {
        shape = cursor;
        if (cursor.width() <= 0 || cursor.height() <= 0) {
            return;
        }
        NativeImage image = new NativeImage(cursor.width(), cursor.height(), false);
        byte[] rgba = cursor.rgba();
        for (int y = 0; y < cursor.height(); y++) {
            for (int x = 0; x < cursor.width(); x++) {
                int i = (y * cursor.width() + x) * 4;
                int abgr = (rgba[i + 3] & 0xFF) << 24 | (rgba[i + 2] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i] & 0xFF);
                image.setPixelRGBA(x, y, abgr);
            }
        }
        // Registering under the same id releases the previous shape.
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        registered = true;
    }

    /**
     * Draws the pointer with its hotspot at the mouse position.
     *
     * @param scale GUI pixels per guest pixel
     */
    void draw(GuiGraphics graphics, double mouseX, double mouseY, double scale) {
        RfbClient.Cursor cursor = shape;
        if (!registered || cursor == null || cursor.width() <= 0 || cursor.height() <= 0) {
            return;
        }
        int w = Math.max(1, (int) Math.round(cursor.width() * scale));
        int h = Math.max(1, (int) Math.round(cursor.height() * scale));
        int x = (int) Math.round(mouseX - cursor.hotX() * scale);
        int y = (int) Math.round(mouseY - cursor.hotY() * scale);
        RenderSystem.enableBlend();
        graphics.blit(id, x, y, w, h, 0.0F, 0.0F, cursor.width(), cursor.height(), cursor.width(), cursor.height());
        RenderSystem.disableBlend();
    }

    @Override
    public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(id);
            registered = false;
        }
    }
}
