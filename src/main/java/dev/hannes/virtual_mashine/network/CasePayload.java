package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.Virtual_mashine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: put the part from inventory slot {@code invSlot} into a case slot, or take one out. */
public record CasePayload(BlockPos pos, boolean install, String slot, int invSlot) implements CustomPacketPayload {
    public static final Type<CasePayload> TYPE = new Type<>(Virtual_mashine.id("case_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CasePayload> CODEC = StreamCodec.ofMember(CasePayload::write, CasePayload::read);

    private static CasePayload read(RegistryFriendlyByteBuf buf) {
        return new CasePayload(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(32), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(install);
        buf.writeUtf(slot, 32);
        buf.writeVarInt(invSlot);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
