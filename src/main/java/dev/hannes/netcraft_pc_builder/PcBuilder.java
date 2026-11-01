package dev.hannes.netcraft_pc_builder;

import dev.hannes.netcraft_pc_builder.block.PartFabricatorBlock;
import dev.hannes.netcraft_pc_builder.network.CasePayload;
import dev.hannes.netcraft_pc_builder.network.FabricatePayload;
import dev.hannes.netcraft_pc_builder.pc.BuiltPcs;
import dev.hannes.netcraft_pc_builder.pc.CaseActions;
import dev.hannes.netcraft_pc_builder.pc.PartCosts;
import dev.hannes.virtual_mashine.api.NetcraftApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Netcraft: PC Builder. Needs Netcraft; its cases are Netcraft computers whose hardware are the parts inside. */
public class PcBuilder implements ModInitializer {
    public static final String MOD_ID = "netcraft_pc_builder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PcBuilderRegistry.register();

        PayloadTypeRegistry.playC2S().register(CasePayload.TYPE, CasePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CasePayload.TYPE, (payload, context) -> CaseActions.handle(context.player(), payload));
        PayloadTypeRegistry.playC2S().register(FabricatePayload.TYPE, FabricatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FabricatePayload.TYPE, (payload, context) -> {
            var player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) <= 64
                    && player.level().getBlockState(payload.pos()).getBlock() instanceof PartFabricatorBlock) {
                PartCosts.fabricate(player, payload.partId());
            }
        });

        // A machine that belongs to a case starts only when its parts work, with their hardware.
        NetcraftApi.registerStartCheck(BuiltPcs::check);
    }
}
