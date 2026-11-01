package dev.hannes.virtual_mashine.pc;

import dev.hannes.virtual_mashine.vm.HostInfo;
import dev.hannes.virtual_mashine.vm.VmConfig;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the built PCs of the world have inside, by machine id, kept up to date by their cases. A machine
 * that belongs to a case starts only when its build works, and with the hardware of its parts.
 */
public final class BuiltPcs {
    private record Spec(@Nullable String blocked, int threads, int ramGb, int storageGb, boolean dedicatedGpu) {
    }

    private static final Map<UUID, Spec> SPECS = new ConcurrentHashMap<>();

    private BuiltPcs() {
    }

    public static void update(UUID id, PcBuild build) {
        List<String> missing = build.missing();
        List<String> problems = build.problems();
        String blocked = !missing.isEmpty() ? "missing " + String.join(", ", missing)
                : !problems.isEmpty() ? problems.get(0) : null;
        SPECS.put(id, new Spec(blocked, build.threads(), build.ramGb(), build.storageGb(), build.get("gpu") != null));
    }

    public static boolean isBuilt(UUID id) {
        return SPECS.containsKey(id);
    }

    /**
     * Called by the machine right before it starts. Null = go (the config now has the parts' hardware,
     * limited to what this computer can give); otherwise why it cannot start.
     */
    public static @Nullable String prepare(UUID id, VmConfig config) {
        Spec spec = SPECS.get(id);
        if (spec == null) {
            return null; // A ready-made PC: its own settings.
        }
        if (spec.blocked() != null) {
            return spec.blocked();
        }
        config.cpus = Math.max(1, Math.min(spec.threads(), HostInfo.cpuCount()));
        int ram = Math.min(spec.ramGb() * 1024, HostInfo.maxRamMb());
        config.ramMb = Math.max(VmConfig.MIN_RAM_MB, ram - ram % 256);
        int disk = Math.min(VmConfig.MAX_DISK_GB, spec.storageGb());
        if (disk > config.diskGb) {
            config.diskGb = disk; // Disks only grow; shrinking would cut the guest's file system.
        }
        // A graphics card drives up to four monitors, the CPU's integrated graphics one.
        config.multiScreen = spec.dedicatedGpu();
        return null;
    }
}
