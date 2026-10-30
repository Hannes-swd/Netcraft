package dev.hannes.virtual_mashine.block;

import com.mojang.serialization.MapCodec;
import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.item.NetworkTesterItem;
import dev.hannes.virtual_mashine.net.DeviceGeometry;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A server rack: switches, routers, servers and modems go in from the top down (right-click with the
 * device), each keeps its height and its own hitbox. Right-click a device: its screen or its sockets,
 * as with the device on its own; break it: only that device comes out. Cables between devices in the
 * same rack, or in racks standing against each other, run inside and are not drawn.
 */
public class RackBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The four posts and the top plate (a laptop or anything else can stand on it); the back panel depends on the facing. */
    private static final VoxelShape POSTS = Shapes.or(Block.box(0, 15, 0, 16, 16, 16),
            Block.box(0, 0, 0, 1, 16, 1), Block.box(15, 0, 0, 16, 16, 1),
            Block.box(0, 0, 15, 1, 16, 16), Block.box(15, 0, 15, 16, 16, 16));

    public RackBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // The frame; the devices are drawn by the block entity renderer.
    }

    private static VoxelShape backPanel(Direction facing) {
        return switch (facing) {
            case SOUTH -> Block.box(1, 0, 0, 15, 16, 1);
            case WEST -> Block.box(15, 0, 1, 16, 16, 15);
            case EAST -> Block.box(0, 0, 1, 1, 16, 15);
            default -> Block.box(1, 0, 15, 15, 16, 16);
        };
    }

    /** The device as it stands on its own, narrowed like it is drawn and lifted to its place. */
    private static VoxelShape deviceShape(RackBlockEntity.Slot slot) {
        Block block = ModRegistry.DEVICE_BLOCKS.get(slot.type);
        AABB box = block.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds();
        double scale = DeviceGeometry.RACK_SCALE;
        return Shapes.box(0.5 + (box.minX - 0.5) * scale, slot.rackY(), 0.5 + (box.minZ - 0.5) * scale,
                0.5 + (box.maxX - 0.5) * scale, slot.top() / 16.0, 0.5 + (box.maxZ - 0.5) * scale);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = Shapes.or(POSTS, backPanel(state.getValue(FACING)));
        if (level.getBlockEntity(pos) instanceof RackBlockEntity rack) {
            for (RackBlockEntity.Slot slot : rack.slots()) {
                shape = Shapes.or(shape, deviceShape(slot));
            }
        }
        return shape;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RackBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModRegistry.RACK_BLOCK_ENTITY, RackBlockEntity::serverTick);
    }

    /** The device the player aims at, from where the ray hit the rack. */
    public static RackBlockEntity.@Nullable Slot slotHit(RackBlockEntity rack, BlockPos pos, BlockHitResult hit) {
        return rack.slotAt((hit.getLocation().y - pos.getY()) * 16.0);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof RackBlockEntity rack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof DeviceBlock device && device.deviceType().rackHeight() > 0) {
            if (player instanceof ServerPlayer serverPlayer) {
                mount(serverPlayer, rack, stack, device.deviceType(), level, pos);
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.getItem() instanceof BlockItem) {
            // Any other block (a laptop, a lamp ...) is placed as usual, e.g. on top of the rack.
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        boolean tester = stack.getItem() instanceof NetworkTesterItem;
        RackBlockEntity.Slot slot = slotHit(rack, pos, hit);
        if ((stack.getItem() instanceof CableItem || tester) && slot != null) {
            if (player instanceof ServerPlayer serverPlayer) {
                openPorts(serverPlayer, slot, pos, state, tester);
            }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    private static void mount(ServerPlayer player, RackBlockEntity rack, ItemStack stack, DeviceType type, Level level, BlockPos pos) {
        int bottom = rack.freeBottom(type.rackHeight());
        if (bottom < 0) {
            player.displayClientMessage(Component.translatable("virtual_mashine.rack.full"), true);
            return;
        }
        UUID fromItem = stack.get(ModRegistry.PC_ID);
        UUID id = fromItem == null || rack.slots().stream().anyMatch(slot -> slot.id.equals(fromItem)) ? UUID.randomUUID() : fromItem;
        rack.mount(id, type, bottom);
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6F, 1.4F);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof RackBlockEntity rack)) {
            return InteractionResult.SUCCESS;
        }
        RackBlockEntity.Slot slot = slotHit(rack, pos, hit);
        VmManager manager = VmManager.get();
        if (slot == null) {
            serverPlayer.displayClientMessage(Component.translatable("virtual_mashine.rack.hint"), true);
        } else if (manager != null && slot.type.isVm() && !player.isShiftKeyDown()) {
            manager.openFor(serverPlayer, slot.id, slot.type);
        } else {
            openPorts(serverPlayer, slot, pos, state, false);
        }
        return InteractionResult.SUCCESS;
    }

    private static void openPorts(ServerPlayer player, RackBlockEntity.Slot slot, BlockPos pos, BlockState state, boolean tester) {
        VmManager manager = VmManager.get();
        if (manager != null) {
            manager.network().openPortView(player, slot.id, slot.type, pos, state.getValue(FACING), slot.rackY(), tester);
        }
    }

    /**
     * Breaking a rack while aiming at one of its devices takes out only that device (called before
     * the block breaks; false keeps the rack).
     */
    public static boolean beforeBreak(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof RackBlockEntity rack) || rack.slots().isEmpty()) {
            return true;
        }
        if (player.pick(player.blockInteractionRange(), 1.0F, false) instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)) {
            RackBlockEntity.Slot slot = slotHit(rack, pos, hit);
            if (slot != null) {
                rack.unmount(slot, !player.isCreative());
                level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.6F, 1.2F);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        VmManager manager = VmManager.get();
        if (level instanceof ServerLevel && manager != null && !oldState.is(state.getBlock())) {
            manager.network().racksChanged();
        }
    }

    /** Broken as a whole (aimed at the frame): every device comes out with it. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof RackBlockEntity rack) {
            rack.unmountAll(true);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        VmManager manager = VmManager.get();
        if (level instanceof ServerLevel && manager != null && !state.is(newState.getBlock())) {
            manager.network().racksChanged();
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
