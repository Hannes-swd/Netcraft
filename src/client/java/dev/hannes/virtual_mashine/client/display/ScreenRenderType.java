package dev.hannes.virtual_mashine.client.display;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Function;

/**
 * A guest picture in the world: plain position + texture, no lightmap and no text shader.
 * Client mods (e.g. NoRisk Client) replace the text shaders, so the picture does not ride on them.
 */
public final class ScreenRenderType extends RenderType {
    private static final Function<ResourceLocation, RenderType> SCREEN = Util.memoize(ScreenRenderType::new);

    private ScreenRenderType(ResourceLocation texture) {
        this(List.of(
                new TextureStateShard(texture, false, false),
                POSITION_TEX_SHADER,
                NO_TRANSPARENCY,
                LEQUAL_DEPTH_TEST,
                CULL,
                NO_LIGHTMAP,
                NO_OVERLAY,
                COLOR_DEPTH_WRITE));
    }

    private ScreenRenderType(List<RenderStateShard> states) {
        super("virtual_mashine_screen", DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256, false, false,
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }

    public static RenderType of(ResourceLocation texture) {
        return SCREEN.apply(texture);
    }
}
