package dev.hannes.netcraft_pc_builder.network;

import dev.hannes.netcraft_pc_builder.PcBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, from the build screen of a case: put the part from inventory slot {@code invSlot}
 * into a slot, take one out, switch the PC on or off, or open its screen.
 */
public record CasePayload(BlockPos pos, String action, String slot, int invSlot) implements CustomPacketPayload {
    public static final String INSTALL = "install";
    public static final String REMOVE = "remove";
    public static final String POWER_ON = "power_on";
    public static final String SHUTDOWN = "shutdown";
    public static final String SCREEN = "screen";

    public static final Type<CasePayload> TYPE = new Type<>(PcBuilder.id("case_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CasePayload> CODEC = StreamCodec.ofMember(CasePayload::write, CasePayload::read);

    public CasePayload(BlockPos pos, String action) {
        this(pos, action, "", -1);
    }

    private static CasePayload read(RegistryFriendlyByteBuf buf) {
        return new CasePayload(buf.readBlockPos(), buf.readUtf(16), buf.readUtf(32), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(action, 16);
        buf.writeUtf(slot, 32);
        buf.writeVarInt(invSlot);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
