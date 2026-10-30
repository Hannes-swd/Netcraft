package dev.hannes.virtual_mashine.vm;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Cisco's virtual IOS images: IOSv (router) and IOSvL2 (layer-3 switch), as Cisco Modeling Labs ships
 * them (e.g. {@code vios-adventerprisek9-m.spa.159-3.m8.qcow2}, {@code vios_l2-adventerprisek9-m.ssa.high_iron_20200929.qcow2}).
 * They are not free to pass around, so the mod never downloads them: they are put into the ISO folder.
 *
 * <p>Both expect the hardware they get in CML: a virtio disk, e1000 network cards and the console on
 * the first serial port. There is no picture on the screen; they are used through the console cable,
 * like the real thing.
 */
public enum CiscoImage {
    /** IOSv: GigabitEthernet0/0 to 0/15. */
    ROUTER(512, "IOSv"),
    /** IOSvL2: the ports come in groups of four, GigabitEthernet0/0-3, 1/0-3, ... */
    SWITCH(768, "IOSvL2");

    public final int minRamMb;
    public final String product;

    CiscoImage(int minRamMb, String product) {
        this.minRamMb = minRamMb;
        this.product = product;
    }

    /** The kind of Cisco image a file is, judged by its name; null for anything else. */
    public static @Nullable CiscoImage of(@Nullable String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.startsWith("vios_l2") || name.startsWith("vios-l2") || name.startsWith("viosl2")
                || name.contains("iosvl2") || name.contains("iosv_l2") || name.contains("iosv-l2")) {
            return SWITCH;
        }
        if (name.startsWith("vios") || name.contains("iosv")) {
            return ROUTER;
        }
        return null;
    }

    /** Short interface name of the {@code nic}-th network card, as IOS shows it ("Gi0/0"). */
    public String interfaceName(int nic) {
        return this == SWITCH ? "Gi" + (nic / 4) + "/" + (nic % 4) : "Gi0/" + nic;
    }

    /** The newest image of this kind among {@code files} (newer versions sort later), or null. */
    public @Nullable String newest(List<String> files) {
        String best = null;
        for (String file : files) {
            if (of(file) == this && (best == null || file.compareToIgnoreCase(best) > 0)) {
                best = file;
            }
        }
        return best;
    }
}
