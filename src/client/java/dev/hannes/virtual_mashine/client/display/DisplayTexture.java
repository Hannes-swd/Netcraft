package dev.hannes.virtual_mashine.client.display;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hannes.virtual_mashine.client.vnc.RfbClient;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A guest screen as a normal Minecraft texture, fed with the changed rows of its frame buffer
 * (straight into OpenGL, as 1.21.1 has no GPU device abstraction yet).
 */
public class DisplayTexture extends AbstractTexture {
    private int width;
    private int height;
    private boolean allocated;
    private boolean smooth = true;

    /** Render thread. */
    public void update(RfbClient client) {
        client.consumeFrame(this::upload);
    }

    private void upload(ByteBuffer pixels, int w, int h, int top, int bottom, boolean resized) {
        if (w <= 0 || h <= 0) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (!allocated || resized || w != width || h != height) {
            TextureUtil.prepareImage(NativeImage.InternalGlFormat.RGBA, getId(), 0, w, h);
            allocated = true;
            width = w;
            height = h;
            top = 0;
            bottom = h - 1;
            applyFilter();
        }
        int first = Math.max(0, top);
        int rows = Math.min(h - 1, bottom) - first + 1;
        if (rows <= 0) {
            return;
        }
        RenderSystem.bindTexture(getId());
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, first, w, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                MemoryUtil.memAddress(pixels) + (long) first * w * 4);
    }

    /** Smooth when scaled down (no lost pixels), sharp when scaled up. */
    public void setSmooth(boolean smooth) {
        if (this.smooth != smooth) {
            this.smooth = smooth;
            if (allocated) {
                applyFilter();
            }
        }
    }

    private void applyFilter() {
        RenderSystem.bindTexture(getId());
        int filter = smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // Filled from the guest's frame buffer, not from a resource.
    }

    public boolean ready() {
        return allocated;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    public void close() {
        releaseId();
        allocated = false;
    }
}
