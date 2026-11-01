package dev.hannes.virtual_mashine.block;

import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.pc.CaseSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/**
 * A PC case: a computer like the PC block (cables, HDMI monitors, USB, sound), but its hardware are
 * the parts built in. Right-click: the build screen; sneak + right-click or a cable: its sockets.
 */
public class CaseBlock extends DeviceBlock {
    /** Set by the client: opens the build screen for the case at this position. */
    public static Consumer<BlockPos> openScreen = pos -> {
    };

    private final CaseSize size;
    private final VoxelShape shapeNorthSouth;
    private final VoxelShape shapeEastWest;

    public CaseBlock(CaseSize size, BlockBehaviour.Properties properties) {
        super(DeviceType.PC, box(size, false), properties);
        this.size = size;
        this.shapeNorthSouth = box(size, false);
        this.shapeEastWest = box(size, true);
    }

    private static VoxelShape box(CaseSize size, boolean eastWest) {
        int[] b = switch (size) {
            case SMALL -> new int[]{4, 11, 2};
            case MEDIUM -> new int[]{3, 15, 1};
            case LARGE -> new int[]{2, 16, 0};
        };
        return eastWest ? Block.box(b[2], 0, b[0], 16 - b[2], b[1], 16 - b[0])
                : Block.box(b[0], 0, b[2], 16 - b[0], b[1], 16 - b[2]);
    }

    public CaseSize size() {
        return size;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? shapeEastWest : shapeNorthSouth;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return super.useWithoutItem(state, level, pos, player, hit); // The sockets.
        }
        if (level.isClientSide()) {
            openScreen.accept(pos);
        }
        return InteractionResult.SUCCESS;
    }
}
