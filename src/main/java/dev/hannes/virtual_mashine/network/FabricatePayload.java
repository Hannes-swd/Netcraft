package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: make this part in the part fabricator at {@code pos}. */
public record FabricatePayload(BlockPos pos, String partId) implements CustomPacketPayload {
    public static final Type<FabricatePayload> TYPE = new Type<>(Virtual_mashine.id("fabricate"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FabricatePayload> CODEC = StreamCodec.ofMember(FabricatePayload::write, FabricatePayload::read);

    private static FabricatePayload read(RegistryFriendlyByteBuf buf) {
        return new FabricatePayload(buf.readBlockPos(), buf.readUtf(64));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(partId, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
