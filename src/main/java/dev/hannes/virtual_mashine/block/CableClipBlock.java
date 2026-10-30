package dev.hannes.virtual_mashine.block;

import com.mojang.serialization.MapCodec;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A cable clip on any face of a block, like a cable tie on the wall. Right-click it with a cable
 * whose first end is plugged in and the cable hangs from it on its way to the second device;
 * right-click without one: the cables hanging from it, each can be taken off again.
 */
public class CableClipBlock extends Block {
    /** Points away from the face the clip is fastened to. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public CableClipBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return MapCodec.unit(this);
    }

    /** Where the cable runs through the clip: in its loop, just off the wall. */
    public static Vec3 hookPoint(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return Vec3.atCenterOf(pos).add(facing.getStepX() * -0.375, facing.getStepY() * -0.375, facing.getStepZ() * -0.375);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case UP -> Block.box(5, 0, 5, 11, 4, 11);
            case DOWN -> Block.box(5, 12, 5, 11, 16, 11);
            case NORTH -> Block.box(5, 5, 12, 11, 11, 16);
            case SOUTH -> Block.box(5, 5, 0, 11, 11, 4);
            case WEST -> Block.box(12, 5, 5, 16, 11, 11);
            case EAST -> Block.box(0, 5, 5, 4, 11, 11);
        };
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof CableItem)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            VmManager manager = VmManager.get();
            if (manager != null && !manager.network().hookCable(serverPlayer, pos, hookPoint(pos, state))) {
                manager.network().openClipView(serverPlayer, pos, "");
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        VmManager manager = VmManager.get();
        if (player instanceof ServerPlayer serverPlayer && manager != null) {
            manager.network().openClipView(serverPlayer, pos, "");
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        VmManager manager = VmManager.get();
        if (manager != null) {
            manager.network().clipRemoved(level, pos);
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        return Block.canSupportCenter(level, pos.relative(facing.getOpposite()), facing);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getClickedFace());
        if (canSurvive(state, context.getLevel(), context.getClickedPos())) {
            return state;
        }
        for (Direction direction : context.getNearestLookingDirections()) {
            state = defaultBlockState().setValue(FACING, direction.getOpposite());
            if (canSurvive(state, context.getLevel(), context.getClickedPos())) {
                return state;
            }
        }
        return null;
    }

    /** Falls off (and drops) when the block it is fastened to goes away. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return direction == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
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
