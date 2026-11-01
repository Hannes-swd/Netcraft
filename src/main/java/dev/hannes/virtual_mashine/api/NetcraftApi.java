package dev.hannes.virtual_mashine.api;

import dev.hannes.virtual_mashine.vm.VmConfig;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hooks for add-on mods such as Netcraft: PC Builder. Netcraft runs fine without any of them.
 *
 * <p>Add-on device blocks extend {@code DeviceBlock} and give their block entity type to the protected
 * constructor of {@code DeviceBlockEntity}; their machines then run, take cables and drive monitors like
 * Netcraft's own devices.
 */
public final class NetcraftApi {
    /** Asked right before a machine starts (not when it resumes from a saved state). */
    @FunctionalInterface
    public interface StartCheck {
        /**
         * May change the machine's hardware in {@code config} (CPUs, RAM, disk size, screens).
         *
         * @return null to let it start, or a translation key for the message shown in its screen
         */
        @Nullable String check(UUID machine, VmConfig config);
    }

    private static final List<StartCheck> START_CHECKS = new CopyOnWriteArrayList<>();

    private NetcraftApi() {
    }

    public static void registerStartCheck(StartCheck check) {
        START_CHECKS.add(check);
    }

    /** Runs every registered check; the first refusal wins. */
    public static @Nullable String checkStart(UUID machine, VmConfig config) {
        for (StartCheck check : START_CHECKS) {
            String blocked = check.check(machine, config);
            if (blocked != null) {
                return blocked;
            }
        }
        return null;
    }
}
