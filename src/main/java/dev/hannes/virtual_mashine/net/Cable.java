package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A patch cable between two ports. Plain fields so it can be stored as JSON. */
public final class Cable {
    public UUID id;
    public String type;
    public int color;
    public End a;
    public End b;
    /** Cable clips the cable hangs from on its way from a to b, in order; null in old files. */
    public List<Hook> hooks;

    /** A cable clip on a wall: its block and the point the cable runs through. */
    public static final class Hook {
        public int x;
        public int y;
        public int z;
        public double px;
        public double py;
        public double pz;

        public static Hook of(BlockPos pos, Vec3 point) {
            Hook hook = new Hook();
            hook.x = pos.getX();
            hook.y = pos.getY();
            hook.z = pos.getZ();
            hook.px = point.x;
            hook.py = point.y;
            hook.pz = point.z;
            return hook;
        }

        public boolean at(BlockPos pos) {
            return x == pos.getX() && y == pos.getY() && z == pos.getZ();
        }

        public Vec3 point() {
            return new Vec3(px, py, pz);
        }
    }

    public static final class End {
        public UUID device;
        public int port;
        public String deviceType;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public String facing;

        public static End of(PortRef ref, DeviceType deviceType, String dimension, BlockPos pos, Direction facing) {
            End end = new End();
            end.device = ref.device();
            end.port = ref.port();
            end.deviceType = deviceType.id;
            end.dimension = dimension;
            end.x = pos.getX();
            end.y = pos.getY();
            end.z = pos.getZ();
            end.facing = facing.getSerializedName();
            return end;
        }

        public PortRef ref() {
            return new PortRef(device, port);
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public DeviceType type() {
            return DeviceType.byId(deviceType);
        }

        public Direction direction() {
            Direction parsed = Direction.byName(facing);
            return parsed == null || parsed.getAxis().isVertical() ? Direction.NORTH : parsed;
        }
    }

    public CableType cableType() {
        return CableType.byId(type);
    }

    public boolean touches(UUID device) {
        return a.device.equals(device) || b.device.equals(device);
    }

    public boolean uses(PortRef port) {
        return a.ref().equals(port) || b.ref().equals(port);
    }

    public End endAt(PortRef port) {
        return a.ref().equals(port) ? a : b;
    }

    public End otherEnd(PortRef port) {
        return a.ref().equals(port) ? b : a;
    }

    public boolean hangsOn(BlockPos clip) {
        return hooks != null && hooks.stream().anyMatch(hook -> hook.at(clip));
    }

    /** Socket a, every clip in order, socket b. */
    public List<Vec3> path() {
        List<Vec3> points = new ArrayList<>();
        points.add(DeviceGeometry.socket(a));
        if (hooks != null) {
            for (Hook hook : hooks) {
                points.add(hook.point());
            }
        }
        points.add(DeviceGeometry.socket(b));
        return points;
    }

    /** Length along the clips in metres (straight between two points). */
    public double length() {
        return length(path());
    }

    public static double length(List<Vec3> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i - 1).distanceTo(points.get(i));
        }
        return total;
    }
}
