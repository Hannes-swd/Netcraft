package dev.hannes.virtual_mashine.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.block.RackBlock;
import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.CableItem;
import dev.hannes.virtual_mashine.network.NetPayloads;
import dev.hannes.virtual_mashine.vm.CiscoImage;
import dev.hannes.virtual_mashine.vm.QemuLocator;
import dev.hannes.virtual_mashine.vm.SerialLine;
import dev.hannes.virtual_mashine.vm.VirtualMachine;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cabling of one world: which port is plugged into which, the devices that live inside the mod
 * (switch, modem), and the half-plugged cables players are carrying around.
 * All public methods are called on the server thread.
 */
public final class NetworkManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double REACH = 8.0;
    private static final long ACTIVITY_NANOS = 400_000_000L;

    /** A device block that ticked recently; {@code rack} = height of its bottom in a rack (blocks), null = on its own. */
    record Placed(DeviceType type, String dimension, BlockPos pos, Direction facing, @Nullable Double rack) {
    }

    /** One end of a cable is plugged in, the player walks to the second device (hanging it on clips on the way). */
    private record Pending(PortRef port, DeviceType deviceType, String dimension, BlockPos pos, Direction facing, @Nullable Double rack,
                           CableType cable, int color, List<Cable.Hook> hooks) {
        List<Vec3> path() {
            List<Vec3> points = new ArrayList<>();
            points.add(DeviceGeometry.socket(deviceType, pos, facing, port.port(), rack));
            for (Cable.Hook hook : hooks) {
                points.add(hook.point());
            }
            return points;
        }
    }

    private final MinecraftServer server;
    private final VmManager vms;
    private final Path file;
    private final Path root;
    private final NetworkFabric fabric;
    private final WlanManager wlan;
    private final MediaHub media;
    private final List<Cable> cables = new CopyOnWriteArrayList<>();
    private final Map<UUID, Placed> devices = new ConcurrentHashMap<>();
    private final Map<UUID, Process> modems = new ConcurrentHashMap<>();
    /** When each modem was last started, so a failing one is not restarted every second. */
    private final Map<UUID, Long> modemStarted = new ConcurrentHashMap<>();
    /** Firmware of every I/O module that was loaded since the world started. */
    private final Map<UUID, IoHost> ioHosts = new ConcurrentHashMap<>();
    /** Hidden uplinks of VMs in "direct" mode, keyed by the VM. */
    private final Map<UUID, Process> directUplinks = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, String> syncedDimension = new HashMap<>();
    private final Set<UUID> autoStarted = ConcurrentHashMap.newKeySet();
    /** Phones found in a player's inventory at the last check. */
    private final Set<UUID> carriedPhones = new java.util.HashSet<>();
    /** Phone -> the player carrying it. */
    private final Map<UUID, UUID> phoneCarriers = new ConcurrentHashMap<>();
    /** Microphones and cameras switched off at their block. */
    private final Set<UUID> usbDisabled = ConcurrentHashMap.newKeySet();
    /** What each microphone picks up (kept by the block); missing = voices only. */
    private final Map<UUID, MicMode> micModes = new ConcurrentHashMap<>();
    /** HDMI switches set to show one picture on all outputs (kept by the block). */
    private final Set<UUID> hdmiMirrored = ConcurrentHashMap.newKeySet();
    /** Volume knob of each speaker in percent (kept by the block). */
    private final Map<UUID, Integer> speakerVolumes = new ConcurrentHashMap<>();
    /** Which side of a stereo signal each speaker plays: {@link #CHANNEL_MONO}, {@link #CHANNEL_LEFT}, {@link #CHANNEL_RIGHT}. */
    private final Map<UUID, Integer> speakerChannels = new ConcurrentHashMap<>();
    private volatile VideoRouting video = VideoRouting.EMPTY;
    private int ticks;

    public NetworkManager(MinecraftServer server, VmManager vms, Path root) throws IOException {
        this.server = server;
        this.vms = vms;
        this.root = root;
        this.file = root.resolve("cables.json");
        this.fabric = new NetworkFabric();
        this.wlan = new WlanManager(server, this, fabric, root);
        this.media = new MediaHub(server, this, vms);
        CompletableFuture.runAsync(UplinkFilter::refreshHostAddresses);
        load();
    }

    public NetworkFabric fabric() {
        return fabric;
    }

    public WlanManager wlan() {
        return wlan;
    }

    public MediaHub media() {
        return media;
    }

    @Nullable Placed placed(UUID id) {
        return devices.get(id);
    }

    List<UUID> devicesOfType(DeviceType type) {
        List<UUID> found = new ArrayList<>();
        for (Map.Entry<UUID, Placed> entry : devices.entrySet()) {
            if (entry.getValue().type == type) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    /** Laptops and phones that are somewhere in the world right now. */
    List<UUID> wirelessClients() {
        List<UUID> found = new ArrayList<>();
        for (Map.Entry<UUID, Placed> entry : devices.entrySet()) {
            if (entry.getValue().type.wirelessOnly()) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    String nameOf(UUID id, DeviceType type) {
        return deviceName(id, type);
    }

    // ---------------------------------------------------------------- devices

    /** Called about once a second by every loaded device block. */
    public void deviceTick(UUID id, DeviceType type, ServerLevel level, BlockPos pos, Direction facing) {
        deviceTick(id, type, level, pos, facing, null);
    }

    /** @param rack height of the device's bottom in its rack (blocks), null = a block of its own */
    public void deviceTick(UUID id, DeviceType type, ServerLevel level, BlockPos pos, Direction facing, @Nullable Double rack) {
        devices.put(id, new Placed(type, dimensionOf(level), pos.immutable(), facing, rack));
        switch (type.kind) {
            case SWITCH -> {
                if (!fabric.hasSwitch(id)) {
                    fabric.addSwitch(id, type.ports, type == DeviceType.HUB);
                    refreshLinks();
                }
            }
            case MODEM -> ensureModem(id);
            case IO -> ioModule(id);
            case AP -> {
                if (!fabric.hasSwitch(id)) {
                    // Port 0 is the LAN socket, ports 1..n are the wireless clients.
                    fabric.addSwitch(id, 1 + DeviceType.AP_CLIENT_SLOTS);
                    wlan.accessPoint(id);
                    refreshLinks();
                }
            }
            case VM -> {
                if (autoStarted.add(id) && vms.shouldAutoStart(id, type)) {
                    vms.autoStart(id, type);
                }
            }
            case USB, USB_HUB, HDMI_SWITCH, SPEAKER -> {
                // Microphones and cameras are handled by the MediaHub, hubs only pass things on.
            }
        }
    }

    /** LED bits for the block: low 32 bits = link per port, high 32 bits = traffic per port. */
    public long ledState(UUID id, DeviceType type) {
        long now = System.nanoTime();
        long link = 0;
        long activity = 0;
        for (int i = 0; i < Math.min(32, type.totalPorts()); i++) {
            PortRef port = new PortRef(id, i);
            Cable cable = cableAt(port);
            if (cable == null || type.isUsbPort(i) || type.isHdmiPort(i) || type.isAudioPort(i)) {
                continue; // USB, HDMI and audio sockets have no LEDs.
            }
            boolean up = type.isConsolePort(i) ? consoleLink(port, cable) : fabric.hasLink(port);
            if (up) {
                link |= 1L << i;
                NetworkFabric.PortStats stats = fabric.stats(port);
                if (stats.lastActivity != 0 && now - stats.lastActivity < ACTIVITY_NANOS) {
                    activity |= 1L << i;
                }
            }
        }
        return link | (activity << 32);
    }

    public boolean isPowered(UUID id, DeviceType type) {
        return switch (type.kind) {
            case SWITCH, AP, IO -> devices.containsKey(id);
            case MODEM -> {
                Process modem = modems.get(id);
                yield modem != null && modem.isAlive();
            }
            // A monitor also lights up while a running computer feeds it over HDMI.
            case VM -> vms.isPowered(id) || type == DeviceType.DISPLAY && hdmiSignal(id);
            case USB -> {
                UUID host = usbHost(id);
                yield devices.containsKey(id) && usbEnabled(id) && host != null && vms.runningMachine(host) != null;
            }
            case USB_HUB -> {
                UUID host = usbHost(id);
                yield devices.containsKey(id) && host != null && vms.runningMachine(host) != null;
            }
            case HDMI_SWITCH -> {
                UUID source = hdmiSource(id, type, 0);
                yield devices.containsKey(id) && source != null && vms.runningMachine(source) != null;
            }
            case SPEAKER -> {
                UUID source = audioSource(id);
                yield devices.containsKey(id) && source != null && vms.runningMachine(source) != null;
            }
        };
    }

    // ---------------------------------------------------------------- speakers

    /**
     * The machine whose sound a speaker plays: what arrives at its audio input (a computer's line out,
     * a monitor's audio out, another speaker's pass-through), otherwise what arrives over HDMI.
     */
    public @Nullable UUID audioSource(UUID speaker) {
        return audioSource(speaker, DeviceType.SPEAKER, 0);
    }

    private @Nullable UUID audioSource(UUID device, DeviceType type, int depth) {
        if (depth > 8) {
            return null; // Speakers chained in a circle.
        }
        if (type == DeviceType.DISPLAY) {
            VideoRouting.Feed feed = video.feed(device);
            return feed != null ? feed.source() : device; // A monitor without HDMI plays its own page's sound.
        }
        if (type.isVm()) {
            return device;
        }
        if (type != DeviceType.SPEAKER) {
            return null;
        }
        PortRef input = new PortRef(device, type.audioPort(0));
        Cable cable = cableAt(input);
        if (cable != null && cable.cableType().isAudio()) {
            Cable.End other = cable.otherEnd(input);
            if (other.type().isAudioOutput(other.port)) {
                UUID source = audioSource(other.device, other.type(), depth + 1);
                if (source != null) {
                    return source;
                }
            }
        }
        // A speaker fed through its pass-through also hears what its upstream speaker gets over HDMI.
        return hdmiSource(device, type, 0);
    }

    public int speakerVolume(UUID speaker) {
        return speakerVolumes.getOrDefault(speaker, SPEAKER_DEFAULT_VOLUME);
    }

    /** The volume knob of a speaker block (kept by the block). */
    public void setSpeakerVolume(UUID speaker, int percent) {
        speakerVolumes.put(speaker, Math.max(0, Math.min(100, percent)));
    }

    public static final int SPEAKER_DEFAULT_VOLUME = 50;
    public static final int CHANNEL_MONO = 0;
    public static final int CHANNEL_LEFT = 1;
    public static final int CHANNEL_RIGHT = 2;

    public int speakerChannel(UUID speaker) {
        return speakerChannels.getOrDefault(speaker, CHANNEL_MONO);
    }

    public void setSpeakerChannel(UUID speaker, int channel) {
        speakerChannels.put(speaker, channel < 0 || channel > CHANNEL_RIGHT ? CHANNEL_MONO : channel);
    }

    /** How far a speaker carries at this volume, in blocks: 4 when nearly off, 96 at full volume. */
    public static double speakerRange(int percent) {
        return percent <= 0 ? 0 : 4 + 92 * percent / 100.0;
    }

    public void openSpeakerView(ServerPlayer player, UUID speaker, BlockPos pos) {
        UUID source = audioSource(speaker);
        String name = source == null ? "" : vms.nameOf(source, devices.containsKey(source) ? devices.get(source).type : DeviceType.PC);
        boolean playing = source != null && vms.runningMachine(source) != null;
        ServerPlayNetworking.send(player, new NetPayloads.SpeakerView(speaker, pos.immutable(), speakerVolume(speaker), speakerChannel(speaker), name, playing));
    }

    public void handleSpeakerAction(ServerPlayer player, NetPayloads.SpeakerAction action) {
        Placed placed = devices.get(action.speaker());
        if (placed == null || placed.type != DeviceType.SPEAKER || !placed.pos.equals(action.pos())
                || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > REACH * REACH) {
            return;
        }
        setSpeakerVolume(action.speaker(), action.volume());
        setSpeakerChannel(action.speaker(), action.channel());
        if (player.level().getBlockEntity(action.pos()) instanceof dev.hannes.virtual_mashine.block.DeviceBlockEntity entity) {
            entity.setSpeakerVolume(speakerVolume(action.speaker()));
            entity.setSpeakerChannel(speakerChannel(action.speaker()));
        }
    }

    // ---------------------------------------------------------------- USB devices

    /** The computer a microphone, camera or USB hub is plugged into (also through hubs), or null. */
    public @Nullable UUID usbHost(UUID device) {
        PortRef plug = new PortRef(device, 0); // A USB device's plug and a hub's host port are both port 0.
        for (int depth = 0; depth < 6; depth++) {
            Cable cable = cableAt(plug);
            if (cable == null || !cable.cableType().isUsb()) {
                return null;
            }
            Cable.End other = cable.otherEnd(plug);
            DeviceType type = other.type();
            if (type == DeviceType.USB_HUB && !type.isUsbUpstream(other.port)) {
                plug = new PortRef(other.device, 0); // Up through the hub towards its computer.
                continue;
            }
            return type.isVm() ? other.device : null;
        }
        return null; // Hubs plugged into each other in a circle.
    }

    /** The computer whose picture arrives at this monitor, HDMI switch or computer, or null. */
    private @Nullable UUID hdmiSource(UUID device, DeviceType type, int depth) {
        if (type.hasVideoOutputs()) {
            return device;
        }
        if (depth > 4 || type.hdmiPorts() == 0) {
            return null;
        }
        PortRef input = new PortRef(device, type.hdmiPort(0));
        Cable cable = cableAt(input);
        if (cable == null || !cable.cableType().isHdmi()) {
            return null;
        }
        Cable.End other = cable.otherEnd(input);
        return other.type().isHdmiOutput(other.port) ? hdmiSource(other.device, other.type(), depth + 1) : null;
    }

    // ---------------------------------------------------------------- HDMI

    /** A running computer's picture arrives at this monitor. */
    public boolean hdmiSignal(UUID monitor) {
        VideoRouting.Feed feed = video.feed(monitor);
        return feed != null && vms.runningMachine(feed.source()) != null;
    }

    /** Which monitor shows which screen of which computer. Rebuilt whenever cables change and every second. */
    public VideoRouting video() {
        return video;
    }

    /** The switch on an HDMI switch: one picture on all outputs (true) or a screen of its own each. */
    public void setHdmiMirrored(UUID hdmiSwitch, boolean mirrored) {
        boolean changed = mirrored ? hdmiMirrored.add(hdmiSwitch) : hdmiMirrored.remove(hdmiSwitch);
        if (changed) {
            rerouteVideo();
        }
    }

    /** Follows the HDMI cables again and tells every running computer which screens have a monitor. */
    public void rerouteVideo() {
        VideoRouting routing = VideoRouting.compute(cables, hdmiMirrored::contains, vms::displayBlocks);
        video = routing;
        for (VirtualMachine vm : vms.runningMachines()) {
            if (vm.deviceType().hasVideoOutputs()) {
                vm.setScreens(routing.screens(vm.id()));
            }
        }
    }

    /** The player stands within {@code range} blocks of a monitor that shows this screen. */
    public boolean seesScreen(ServerPlayer player, UUID source, int head, double range) {
        for (UUID monitor : video.monitorsOf(source, head)) {
            if (isNear(monitor, player, range)) {
                return true;
            }
        }
        return false;
    }

    public boolean usbEnabled(UUID device) {
        return !usbDisabled.contains(device);
    }

    /** The switch on a microphone or camera block (kept by the block). */
    public void setUsbEnabled(UUID device, boolean enabled) {
        if (enabled) {
            usbDisabled.remove(device);
        } else {
            usbDisabled.add(device);
        }
    }

    public MicMode micMode(UUID mic) {
        return micModes.getOrDefault(mic, MicMode.VOICE);
    }

    /** The mode switch on a microphone block (kept by the block). */
    public void setMicMode(UUID mic, MicMode mode) {
        micModes.put(mic, mode);
    }

    /** The player carrying this phone right now, or null. */
    public @Nullable UUID phoneCarrier(UUID phone) {
        return phoneCarriers.get(phone);
    }

    /** The block is gone: its cables fall off and drop as items. */
    public void deviceRemoved(UUID id, ServerLevel level, BlockPos pos) {
        boolean changed = false;
        for (Cable cable : List.copyOf(cables)) {
            if (cable.touches(id)) {
                removeCable(cable);
                Block.popResource(level, pos, cableItem(cable.cableType(), cable.color));
                changed = true;
            }
        }
        pending.entrySet().removeIf(entry -> entry.getValue().port.device().equals(id));
        wlan.deviceRemoved(id);
        devices.remove(id);
        hdmiMirrored.remove(id);
        micModes.remove(id);
        speakerVolumes.remove(id);
        speakerChannels.remove(id);
        IoHost io = ioHosts.remove(id);
        if (io != null) {
            fabric.detachHost(new PortRef(id, 0));
            saveIo(id, io.io()); // The id travels with the dropped block: placed again, it keeps its settings.
        }
        fabric.removeSwitch(id);
        stopModem(id);
        if (changed) {
            save();
            broadcastCables();
            rerouteVideo();
        }
        refreshLinks();
    }

    /** The player stands within {@code range} blocks of this placed device (same dimension). */
    public boolean isNear(UUID id, ServerPlayer player, double range) {
        Placed placed = devices.get(id);
        return placed != null && placed.dimension.equals(dimensionOf(player.level()))
                && player.distanceToSqr(Vec3.atCenterOf(placed.pos)) <= range * range;
    }

    // ---------------------------------------------------------------- I/O modules

    /** The module's channels and firmware; created and plugged into the fabric on first use. */
    public IoModule ioModule(UUID id) {
        return ioHosts.computeIfAbsent(id, key -> {
            IoModule module = new IoModule(DeviceMac.of(key, 0), loadIo(key));
            IoHost host = new IoHost(module, "io-" + key.toString().substring(0, 4));
            fabric.attachHost(new PortRef(key, 0), host);
            return host;
        }).io();
    }

    private IoModule.Settings loadIo(UUID id) {
        Path path = root.resolve(id.toString()).resolve("io.json");
        if (Files.isRegularFile(path)) {
            try {
                IoModule.Settings settings = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), IoModule.Settings.class);
                if (settings != null) {
                    return settings;
                }
            } catch (IOException | RuntimeException e) {
                Virtual_mashine.LOGGER.warn("Could not read I/O module settings {}", path, e);
            }
        }
        return new IoModule.Settings();
    }

    private void saveIo(UUID id, IoModule module) {
        try {
            Path dir = root.resolve(id.toString());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("io.json"), GSON.toJson(module.settings()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not save I/O module {}", id, e);
        }
    }

    public void openIoView(ServerPlayer player, UUID id, BlockPos pos, Direction facing, String message) {
        devices.put(id, new Placed(DeviceType.IO_MODULE, dimensionOf(player.level()), pos.immutable(), facing, null));
        IoModule io = ioModule(id);
        IoHost host = ioHosts.get(id);
        int[] levels = new int[IoModule.CHANNELS];
        for (int ch = 0; ch < IoModule.CHANNELS; ch++) {
            levels[ch] = io.level(ch);
        }
        String address = io.ip() == 0 ? "" : IoModule.formatIp(io.ip()) + "/" + Integer.bitCount(io.mask())
                + (io.gateway() != 0 ? " · Gateway " + IoModule.formatIp(io.gateway()) : "");
        ServerPlayNetworking.send(player, new NetPayloads.IoView(id, pos, deviceName(id, DeviceType.IO_MODULE), io.name(), io.mac(),
                address, fabric.hasLink(new PortRef(id, 0)), io.dhcp(), IoModule.formatIp(io.staticIp()),
                IoModule.formatIp(io.staticMask()), IoModule.formatIp(io.staticGateway()), io.outputMask(), levels,
                host == null ? 0 : host.connectionCount(), message));
    }

    public void handleIoAction(ServerPlayer player, NetPayloads.IoAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || placed.type != DeviceType.IO_MODULE || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(placed.pos)) > REACH * REACH) {
            return;
        }
        IoModule io = ioModule(action.device());
        int ch = action.channel();
        String message = "";
        switch (action.action()) {
            case NetPayloads.IoAction.MODE -> {
                if (ch >= 0 && ch < IoModule.CHANNELS) {
                    io.setMode(ch, action.value() == 1);
                }
            }
            case NetPayloads.IoAction.LEVEL -> {
                if (ch >= 0 && ch < IoModule.CHANNELS) {
                    io.setOutput(ch, action.value());
                }
            }
            case NetPayloads.IoAction.NETWORK -> {
                String name = action.name().strip();
                io.setName(name.length() > 32 ? name.substring(0, 32) : name);
                int ip = IoModule.parseIp(action.ip());
                int mask = IoModule.parseIp(action.mask());
                int gateway = action.gateway().isBlank() ? 0 : IoModule.parseIp(action.gateway());
                boolean validMask = mask != 0 && Integer.bitCount(mask) == Integer.numberOfLeadingZeros(~mask);
                if (!action.dhcp() && (ip == 0 || !validMask || (!action.gateway().isBlank() && gateway == 0))) {
                    message = "virtual_mashine.io.msg.bad_address";
                } else {
                    if (action.dhcp() != io.dhcp() || ip != io.staticIp() || mask != io.staticMask() || gateway != io.staticGateway()) {
                        io.configureNetwork(action.dhcp(), action.dhcp() ? io.staticIp() : ip,
                                action.dhcp() ? io.staticMask() : mask, action.dhcp() ? io.staticGateway() : gateway);
                    }
                    message = "virtual_mashine.io.msg.saved";
                }
            }
            default -> {
            }
        }
        openIoView(player, action.device(), placed.pos, placed.facing, message);
    }

    // ---------------------------------------------------------------- phones

    /** A player uses this phone: it is where they stand. */
    public void phoneCarried(UUID id, ServerPlayer player) {
        devices.put(id, new Placed(DeviceType.PHONE, dimensionOf(player.level()),
                BlockPos.containing(player.getEyePosition()), player.getDirection(), null));
        carriedPhones.add(id);
        phoneCarriers.put(id, player.getUUID());
    }

    /**
     * Phones move with the player carrying them. A phone that is in nobody's inventory (dropped,
     * in a chest, its player offline) is out of every WLAN's reach.
     */
    private void trackPhones() {
        Set<UUID> seen = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                UUID id = stack.is(ModRegistry.PHONE) ? stack.get(ModRegistry.PC_ID) : null;
                if (id != null && seen.add(id)) {
                    phoneCarried(id, player);
                }
            }
        }
        for (UUID id : carriedPhones) {
            if (!seen.contains(id)) {
                devices.remove(id);
                phoneCarriers.remove(id);
            }
        }
        carriedPhones.retainAll(seen);
    }

    // ---------------------------------------------------------------- VM integration

    public NetworkFabric.UdpBinding[] openVmPorts(UUID id, int count) throws IOException {
        return fabric.openUdpPorts(id, count);
    }

    /** A VM's network cards are up (or gone): every link LED and carrier state may change. */
    public void vmNetworkChanged(UUID id, boolean running) {
        if (!running) {
            fabric.closeUdpPorts(id);
        }
        vms.runOnServer(this::refreshLinks);
    }

    /** Pushes "cable plugged/unplugged" to the network cards and serial ports of all running VMs. */
    public void refreshLinks() {
        for (VirtualMachine vm : vms.runningMachines()) {
            int ports = vm.cablePorts();
            for (int port = 0; port < ports; port++) {
                vm.updateLink(port, fabric.hasLink(new PortRef(vm.id(), port)));
            }
            SerialLine serial = vm.serial();
            if (serial != null) {
                SerialLine wanted = consolePartner(vm.id(), vm.deviceType());
                if (serial.peer() != wanted) {
                    serial.connectTo(wanted);
                }
            }
        }
    }

    /** The serial port at the other end of this machine's console cable, if that machine runs. */
    private @Nullable SerialLine consolePartner(UUID id, DeviceType type) {
        Cable cable = cableAt(new PortRef(id, type.ports));
        if (cable == null) {
            return null;
        }
        VirtualMachine other = vms.runningMachine(cable.otherEnd(new PortRef(id, type.ports)).device);
        return other == null ? null : other.serial();
    }

    /** A USB device works while the computer it hangs on (directly or through hubs) runs. */
    private boolean usbLink(PortRef port, Cable cable) {
        Cable.End end = cable.endAt(port);
        Cable.End top = end.type().isUsbUpstream(end.port) ? cable.otherEnd(port) : end;
        UUID host = top.type().isVm() ? top.device : usbHost(top.device);
        return host != null && vms.runningMachine(host) != null;
    }

    /** An HDMI cable carries a picture while the computer at its source end runs. */
    private boolean hdmiLink(PortRef port, Cable cable) {
        Cable.End end = cable.endAt(port);
        Cable.End output = end.type().isHdmiOutput(end.port) ? end : cable.otherEnd(port);
        UUID source = hdmiSource(output.device, output.type(), 0);
        return source != null && vms.runningMachine(source) != null;
    }

    /** An audio cable carries sound while the machine at its source end runs. */
    private boolean audioLink(PortRef port, Cable cable) {
        Cable.End end = cable.endAt(port);
        Cable.End output = end.type().isAudioOutput(end.port) ? end : cable.otherEnd(port);
        UUID source = output.type() == DeviceType.SPEAKER ? audioSource(output.device) : audioSource(output.device, output.type(), 0);
        return source != null && vms.runningMachine(source) != null;
    }

    private boolean consoleLink(PortRef port, Cable cable) {
        Cable.End other = cable.otherEnd(port);
        return vms.runningMachine(port.device()) != null && vms.runningMachine(other.device) != null;
    }

    /** Connects a new or loaded cable in the fabric (network cables only; consoles are linked in refreshLinks). */
    private void wire(Cable cable) {
        if (cable.cableType().isNetwork()) {
            fabric.connect(cable.a.ref(), cable.b.ref(), cable.cableType().speedMbit(cable.length()));
        }
    }

    // ---------------------------------------------------------------- port panel

    public void openPortView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, Direction facing) {
        openPortView(player, id, type, pos, facing, false);
    }

    /** @param tester opened with the network tester: the panel also shows speeds, MACs and traffic */
    public void openPortView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, Direction facing, boolean tester) {
        openPortView(player, id, type, pos, facing, null, tester);
    }

    /** @param rack height of the device's bottom in its rack (blocks), null = a block of its own */
    public void openPortView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, Direction facing, @Nullable Double rack,
                             boolean tester) {
        devices.put(id, new Placed(type, dimensionOf(player.level()), pos.immutable(), facing, rack));
        NetPayloads.PortView view = portView(player, id, type, pos, true, "");
        ServerPlayNetworking.send(player, new NetPayloads.PortView(view.device(), view.pos(), view.deviceType(), view.deviceName(),
                view.powered(), true, view.ports(), view.heldCable(), view.pending(), view.message(), tester));
    }

    // ---------------------------------------------------------------- WLAN panels

    public void openWlanView(ServerPlayer player, UUID laptop, String message) {
        Placed placed = devices.get(laptop);
        if (placed == null || !placed.dimension.equals(dimensionOf(player.level()))) {
            return;
        }
        List<NetPayloads.WlanNetwork> networks = new ArrayList<>();
        for (WlanManager.Network seen : wlan.scan(laptop)) {
            networks.add(new NetPayloads.WlanNetwork(seen.ssid(), seen.dbm(), seen.secured(), seen.connected()));
        }
        String connected = wlan.connectedSsid(laptop);
        String remembered = wlan.rememberedSsid(laptop);
        ServerPlayNetworking.send(player, new NetPayloads.WlanView(laptop, placed.pos, deviceName(laptop, placed.type), networks,
                connected == null ? "" : connected, remembered == null ? "" : remembered, wlan.linkSpeed(laptop),
                isPowered(laptop, placed.type), message));
    }

    public void handleWlanAction(ServerPlayer player, NetPayloads.WlanAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || !placed.type.wirelessOnly() || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(placed.pos)) > REACH * REACH * 4) {
            return;
        }
        String message = switch (action.action()) {
            case NetPayloads.WlanAction.CONNECT -> wlan.connect(action.device(), action.ssid(), action.password());
            case NetPayloads.WlanAction.FORGET -> {
                wlan.forget(action.device());
                yield "virtual_mashine.wlan.msg.forgotten";
            }
            default -> "";
        };
        openWlanView(player, action.device(), message);
    }

    public void openApView(ServerPlayer player, UUID ap, BlockPos pos, Direction facing, String message) {
        devices.put(ap, new Placed(DeviceType.ACCESS_POINT, dimensionOf(player.level()), pos.immutable(), facing, null));
        WlanManager.ApConfig config = wlan.accessPoint(ap);
        ServerPlayNetworking.send(player, new NetPayloads.ApView(ap, pos, deviceName(ap, DeviceType.ACCESS_POINT), config.ssid,
                config.password, wlan.clientsOf(ap), message));
    }

    public void handleApAction(ServerPlayer player, NetPayloads.ApAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || placed.type != DeviceType.ACCESS_POINT || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(placed.pos)) > REACH * REACH) {
            return;
        }
        String message = action.save() ? wlan.configure(action.device(), action.ssid(), action.password()) : "";
        openApView(player, action.device(), placed.pos, placed.facing, message);
    }

    /** The "Anschlüsse" button of a computer's control panel. */
    public void openPortViewOf(ServerPlayer player, UUID id) {
        Placed placed = devices.get(id);
        if (placed != null && placed.type.wirelessOnly()) {
            openWlanView(player, id, "");
            return;
        }
        if (placed != null && placed.dimension.equals(dimensionOf(player.level()))
                && player.distanceToSqr(Vec3.atCenterOf(placed.pos)) <= REACH * REACH * 4) {
            ServerPlayNetworking.send(player, portView(player, id, placed.type, placed.pos, true, ""));
        }
    }

    public void handlePortAction(ServerPlayer player, NetPayloads.PortAction action) {
        Placed placed = devices.get(action.device());
        if (placed == null || !placed.pos.equals(action.pos()) || !placed.dimension.equals(dimensionOf(player.level()))
                || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > REACH * REACH) {
            return;
        }
        if (action.port() < 0 || action.port() >= placed.type.totalPorts()) {
            if (action.action().equals(NetPayloads.PortAction.REFRESH)) {
                ServerPlayNetworking.send(player, portView(player, action.device(), placed.type, placed.pos, false, ""));
            }
            return;
        }
        PortRef port = new PortRef(action.device(), action.port());
        String message = switch (action.action()) {
            case NetPayloads.PortAction.PLUG -> plug(player, port, placed);
            case NetPayloads.PortAction.UNPLUG -> unplug(player, port);
            default -> "";
        };
        ServerPlayNetworking.send(player, portView(player, action.device(), placed.type, placed.pos, false, message));
    }

    private String plug(ServerPlayer player, PortRef port, Placed placed) {
        ItemStack held = heldCable(player);
        if (held.isEmpty()) {
            return "virtual_mashine.ports.msg.no_cable";
        }
        if (cableAt(port) != null) {
            return "virtual_mashine.ports.msg.occupied";
        }
        CableItem item = (CableItem) held.getItem();
        if (placed.type.isWirelessPort(port.port())) {
            return "virtual_mashine.ports.msg.wireless";
        }
        boolean consolePort = placed.type.isConsolePort(port.port());
        boolean usbPort = placed.type.isUsbPort(port.port());
        boolean hdmiPort = placed.type.isHdmiPort(port.port());
        boolean audioPort = placed.type.isAudioPort(port.port());
        if (item.cableType.isConsole() != consolePort || item.cableType.isUsb() != usbPort || item.cableType.isHdmi() != hdmiPort
                || item.cableType.isAudio() != audioPort) {
            return audioPort ? "virtual_mashine.ports.msg.needs_audio_cable" : usbPort ? "virtual_mashine.ports.msg.needs_usb_cable"
                    : hdmiPort ? "virtual_mashine.ports.msg.needs_hdmi_cable"
                    : consolePort ? "virtual_mashine.ports.msg.needs_console_cable" : "virtual_mashine.ports.msg.needs_network_cable";
        }
        Pending start = pending.get(player.getUUID());
        if (start == null) {
            pending.put(player.getUUID(), new Pending(port, placed.type, placed.dimension, placed.pos, placed.facing, placed.rack,
                    item.cableType, CableItem.color(held), new ArrayList<>()));
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.first_end";
        }
        if (start.port.equals(port)) {
            return "";
        }
        if (!start.dimension.equals(placed.dimension)) {
            return "virtual_mashine.ports.msg.other_dimension";
        }
        if (start.port.device().equals(port.device()) && (start.cable.isUsb() || start.cable.isHdmi() || start.cable.isAudio())) {
            return "virtual_mashine.ports.msg.same_device";
        }
        if (start.cable.isUsb() && start.deviceType.isUsbUpstream(start.port.port()) == placed.type.isUsbUpstream(port.port())) {
            return "virtual_mashine.ports.msg.usb_pair"; // One end in a device or a hub's host port, the other in a computer or hub.
        }
        if (start.cable.isHdmi() && start.deviceType.isHdmiOutput(start.port.port()) == placed.type.isHdmiOutput(port.port())) {
            return "virtual_mashine.ports.msg.hdmi_pair"; // One end in a picture output, the other in a monitor or switch input.
        }
        if (start.cable.isAudio() && start.deviceType.isAudioOutput(start.port.port()) == placed.type.isAudioOutput(port.port())) {
            return "virtual_mashine.ports.msg.audio_pair"; // One end in a line out, the other in a speaker input.
        }
        Cable cable = new Cable();
        cable.id = UUID.randomUUID();
        cable.type = start.cable.id;
        cable.color = start.color;
        cable.a = Cable.End.of(start.port, start.deviceType, start.dimension, start.pos, start.facing, start.rack);
        cable.b = Cable.End.of(port, placed.type, placed.dimension, placed.pos, placed.facing, placed.rack);
        cable.hooks = start.hooks.isEmpty() ? null : new ArrayList<>(start.hooks);
        if (cable.length() > start.cable.maxLength) {
            return "virtual_mashine.ports.msg.too_long";
        }
        if (cableAt(start.port) != null) {
            pending.remove(player.getUUID());
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.occupied";
        }
        if (!player.isCreative()) {
            held.shrink(1);
        }
        pending.remove(player.getUUID());
        cables.add(cable);
        wire(cable);
        save();
        broadcastCables();
        sendHeldCable(player);
        refreshLinks();
        if (cable.cableType().isHdmi()) {
            rerouteVideo();
        }
        return "virtual_mashine.ports.msg.connected";
    }

    private String unplug(ServerPlayer player, PortRef port) {
        Pending start = pending.get(player.getUUID());
        if (start != null && start.port.equals(port)) {
            pending.remove(player.getUUID());
            sendHeldCable(player);
            return "virtual_mashine.ports.msg.cancelled";
        }
        Cable cable = cableAt(port);
        if (cable == null) {
            return "";
        }
        removeCable(cable);
        ItemStack stack = cableItem(cable.cableType(), cable.color);
        if (!player.isCreative() && !player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        save();
        broadcastCables();
        refreshLinks();
        if (cable.cableType().isHdmi()) {
            rerouteVideo();
        }
        return "virtual_mashine.ports.msg.unplugged";
    }

    /** Right-click into the air with a cable while one end is plugged in: pull it out again. */
    public boolean cancelPending(ServerPlayer player) {
        if (pending.remove(player.getUUID()) != null) {
            sendHeldCable(player);
            player.displayClientMessage(Component.translatable("virtual_mashine.ports.msg.cancelled"), true);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- cable clips

    /**
     * Right-click on a clip with a cable whose first end is plugged in: the cable now hangs from it.
     * @return false if the player carries no half-plugged cable (the clip menu opens instead)
     */
    public boolean hookCable(ServerPlayer player, BlockPos clip, Vec3 point) {
        Pending start = pending.get(player.getUUID());
        if (start == null || heldCable(player).isEmpty()) {
            return false;
        }
        String message;
        if (!start.dimension.equals(dimensionOf(player.level()))) {
            message = "virtual_mashine.ports.msg.other_dimension";
        } else if (start.hooks.stream().anyMatch(hook -> hook.at(clip))) {
            message = "virtual_mashine.clip.msg.already";
        } else {
            List<Vec3> path = start.path();
            path.add(point);
            if (Cable.length(path) > start.cable.maxLength) {
                message = "virtual_mashine.ports.msg.too_long";
            } else {
                start.hooks.add(Cable.Hook.of(clip.immutable(), point));
                sendHeldCable(player);
                message = "virtual_mashine.clip.msg.hooked";
            }
        }
        player.displayClientMessage(Component.translatable(message), true);
        return true;
    }

    public void openClipView(ServerPlayer player, BlockPos clip, String message) {
        String dimension = dimensionOf(player.level());
        List<NetPayloads.ClipCable> list = new ArrayList<>();
        for (Cable cable : cables) {
            if (cable.a.dimension.equals(dimension) && cable.hangsOn(clip)) {
                list.add(new NetPayloads.ClipCable(cable.id, endLabel(cable.a), endLabel(cable.b), cable.type, cable.color));
            }
        }
        ServerPlayNetworking.send(player, new NetPayloads.ClipView(clip.immutable(), list, message));
    }

    private String endLabel(Cable.End end) {
        return deviceName(end.device, end.type()) + " · " + portLabel(end.device, end.type(), end.port);
    }

    /** "Aushängen" in the clip menu: the cable leaves the clip and runs straight on, still plugged in. */
    public void handleClipAction(ServerPlayer player, NetPayloads.ClipAction action) {
        if (player.distanceToSqr(Vec3.atCenterOf(action.pos())) > REACH * REACH) {
            return;
        }
        String dimension = dimensionOf(player.level());
        String message = "";
        for (Cable cable : cables) {
            if (cable.id.equals(action.cable()) && cable.a.dimension.equals(dimension) && cable.hangsOn(action.pos())) {
                unhook(cable, action.pos());
                save();
                broadcastCables();
                message = "virtual_mashine.clip.msg.unhooked";
            }
        }
        openClipView(player, action.pos(), message);
    }

    /** The clip block is gone: every cable on it (and every cable being laid through it) runs straight on. */
    public void clipRemoved(ServerLevel level, BlockPos clip) {
        String dimension = dimensionOf(level);
        boolean changed = false;
        for (Cable cable : cables) {
            if (cable.a.dimension.equals(dimension) && cable.hangsOn(clip)) {
                unhook(cable, clip);
                changed = true;
            }
        }
        if (changed) {
            save();
            broadcastCables();
        }
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Pending start = entry.getValue();
            if (start.dimension.equals(dimension) && start.hooks.removeIf(hook -> hook.at(clip))) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    sendHeldCable(player);
                }
            }
        }
    }

    /** Takes the cable off the clip; a shorter cable can be faster, so the link is set up again if needed. */
    private void unhook(Cable cable, BlockPos clip) {
        int speedBefore = cable.cableType().speedMbit(cable.length());
        cable.hooks.removeIf(hook -> hook.at(clip));
        if (cable.hooks.isEmpty()) {
            cable.hooks = null;
        }
        if (cable.cableType().isNetwork() && cable.cableType().speedMbit(cable.length()) != speedBefore) {
            fabric.disconnect(cable.a.ref());
            wire(cable);
            refreshLinks();
        }
    }

    private NetPayloads.PortView portView(ServerPlayer player, UUID id, DeviceType type, BlockPos pos, boolean open, String message) {
        long now = System.nanoTime();
        Pending start = pending.get(player.getUUID());
        List<NetPayloads.PortInfo> ports = new ArrayList<>(type.totalPorts());
        for (int i = 0; i < type.totalPorts(); i++) {
            PortRef port = new PortRef(id, i);
            boolean console = type.isConsolePort(i);
            boolean usb = type.isUsbPort(i);
            boolean hdmi = type.isHdmiPort(i) || type.isAudioPort(i);
            String name = portLabel(id, type, i);
            String mac = type.isVm() && !console && !usb && !hdmi ? DeviceMac.of(id, i) : "";
            Cable cable = cableAt(port);
            NetworkFabric.PortStats stats = fabric.stats(port);
            boolean activity = now - stats.lastActivity < ACTIVITY_NANOS && stats.lastActivity != 0;
            long rx = stats.rxBytes.get();
            long tx = stats.txBytes.get();
            if (type.isWirelessPort(i)) {
                String ssid = wlan.connectedSsid(id);
                boolean link = ssid != null && fabric.hasLink(port);
                ports.add(new NetPayloads.PortInfo(name, link ? NetPayloads.PortInfo.CONNECTED : NetPayloads.PortInfo.FREE, link,
                        activity && link, ssid == null ? "" : "WLAN " + ssid, "", 0, 0, false, mac, link ? wlan.linkSpeed(id) : 0, rx, tx));
                continue;
            }
            if (cable != null) {
                Cable.End other = cable.otherEnd(port);
                String peer = deviceName(other.device, other.type()) + " · " + portLabel(other.device, other.type(), other.port);
                boolean link = console ? consoleLink(port, cable) : usb ? usbLink(port, cable)
                        : type.isAudioPort(i) ? audioLink(port, cable) : hdmi ? hdmiLink(port, cable) : fabric.hasLink(port);
                int speed = link ? cable.cableType().speedMbit(cable.length()) : 0;
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.CONNECTED, link, activity && link, peer, cable.type,
                        cable.color, (int) Math.round(cable.length()), console, mac, speed, rx, tx));
            } else if (start != null && start.port.equals(port)) {
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.HALF, false, false, "",
                        start.cable.id, start.color, 0, console, mac, 0, rx, tx));
            } else {
                ports.add(new NetPayloads.PortInfo(name, NetPayloads.PortInfo.FREE, false, false, "", "", 0, 0, console, mac, 0, rx, tx));
            }
        }
        ItemStack held = heldCable(player);
        String heldType = held.isEmpty() ? "" : ((CableItem) held.getItem()).cableType.id;
        String pendingText = start == null ? "" : deviceName(start.port.device(), start.deviceType) + " · " + portLabel(start.port.device(), start.deviceType, start.port.port());
        return new NetPayloads.PortView(id, pos, type.id, deviceName(id, type), isPowered(id, type), open, ports, heldType, pendingText,
                message, false);
    }

    /**
     * Port name: the interface name on Cisco IOS ("Gi0/1"), with the role OpenWrt gave it after the
     * automatic set-up ("eth1 WAN"), otherwise as printed on the housing.
     */
    private String portLabel(UUID id, DeviceType type, int port) {
        String name = type.portName(port);
        if (!type.isVm() || type.isConsolePort(port) || type.isUsbPort(port) || type.isHdmiPort(port) || type.isAudioPort(port)) {
            return name;
        }
        CiscoImage cisco = vms.ciscoOf(id);
        if (cisco != null) {
            return cisco.interfaceName(port);
        }
        if (vms.provisionOf(id).equals("router")) {
            return name + (port == 1 ? " WAN" : " LAN");
        }
        return name;
    }

    private String deviceName(UUID id, DeviceType type) {
        if (type.isVm()) {
            return vms.nameOf(id, type);
        }
        String kind = switch (type) {
            case SWITCH -> "Switch";
            case HUB -> "Hub";
            case MODEM -> "Modem";
            case MICROPHONE -> "Mikrofon";
            case CAMERA -> "Kamera";
            case ACCESS_POINT -> "Access Point";
            case HDMI_SWITCH -> "HDMI-Switch";
            case USB_HUB -> "USB-Hub";
            case SPEAKER -> "Lautsprecher";
            case IO_MODULE -> {
                IoHost io = ioHosts.get(id);
                if (io != null && !io.io().name().isEmpty()) {
                    yield io.io().name();
                }
                yield "I/O-Modul";
            }
            default -> type.id;
        };
        return kind + " " + id.toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- held cable & sync

    public void tick() {
        media.tick();
        if (++ticks % 20 != 0) {
            return;
        }
        if (ticks % 1200 == 0) {
            CompletableFuture.runAsync(UplinkFilter::refreshHostAddresses);
        }
        trackPhones();
        for (Map.Entry<UUID, IoHost> entry : ioHosts.entrySet()) {
            if (entry.getValue().io().takeDirty()) {
                saveIo(entry.getKey(), entry.getValue().io());
            }
        }
        wlan.tick();
        // Cheap safety net: carrier states only change on the guest when they differ.
        refreshLinks();
        // Also picks up monitors that changed their size and computers that just started.
        rerouteVideo();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dimension = dimensionOf(player.level());
            if (!dimension.equals(syncedDimension.get(player.getUUID()))) {
                syncedDimension.put(player.getUUID(), dimension);
                sendCables(player);
                sendHeldCable(player);
            }
            Pending start = pending.get(player.getUUID());
            if (start != null && heldCable(player).isEmpty()) {
                // Put the cable away: it is pulled out of the first port again.
                pending.remove(player.getUUID());
                sendHeldCable(player);
            }
        }
    }

    public void onPlayerLeft(UUID player) {
        pending.remove(player);
        syncedDimension.remove(player);
        phoneCarriers.values().removeIf(player::equals);
        media.onPlayerLeft(player);
    }

    private void sendHeldCable(ServerPlayer player) {
        Pending start = pending.get(player.getUUID());
        if (start == null) {
            ServerPlayNetworking.send(player, NetPayloads.HeldCable.NONE);
            return;
        }
        ServerPlayNetworking.send(player, new NetPayloads.HeldCable(true, start.path(), start.color));
    }

    private void broadcastCables() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendCables(player);
        }
    }

    /**
     * A patch cable inside a cabinet: straight out of both sockets, then a smooth bend between them
     * (the four control points of a cubic Bezier curve).
     */
    private static List<Vec3> patchCurve(Cable cable) {
        Vec3 a = DeviceGeometry.socket(cable.a);
        Vec3 b = DeviceGeometry.socket(cable.b);
        Direction faceA = DeviceGeometry.socketFace(cable.a.type(), cable.a.direction(), cable.a.port);
        Direction faceB = DeviceGeometry.socketFace(cable.b.type(), cable.b.direction(), cable.b.port);
        double reach = Math.min(0.6, 0.12 + a.distanceTo(b) * 0.3);
        return List.of(a, a.add(Vec3.atLowerCornerOf(faceA.getNormal()).scale(reach)),
                b.add(Vec3.atLowerCornerOf(faceB.getNormal()).scale(reach)), b);
    }

    /** A rack was put up or taken down: cables between racks may now run inside a cabinet, or out in the open. */
    public void racksChanged() {
        broadcastCables();
    }

    /** Both ends sit in racks that are the same or touch through other racks (one cabinet). */
    private static boolean insideCabinet(ServerLevel level, Cable cable) {
        if (cable.a.rack == null || cable.b.rack == null) {
            return false;
        }
        BlockPos target = cable.b.pos();
        BlockPos start = cable.a.pos();
        if (start.equals(target)) {
            return true;
        }
        Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 512) {
            BlockPos pos = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (seen.contains(next) || !level.isLoaded(next) || !(level.getBlockState(next).getBlock() instanceof RackBlock)) {
                    continue;
                }
                if (next.equals(target)) {
                    return true;
                }
                seen.add(next);
                queue.add(next);
            }
        }
        return false;
    }

    private void sendCables(ServerPlayer player) {
        String dimension = dimensionOf(player.level());
        List<NetPayloads.CableVisual> visuals = new ArrayList<>();
        for (Cable cable : cables) {
            if (!cable.a.dimension.equals(dimension)) {
                continue;
            }
            if (cable.hooks == null && insideCabinet(player.serverLevel(), cable)) {
                visuals.add(new NetPayloads.CableVisual(patchCurve(cable), cable.color, true));
            } else {
                visuals.add(new NetPayloads.CableVisual(cable.path(), cable.color, false));
            }
        }
        ServerPlayNetworking.send(player, new NetPayloads.CableSync(visuals));
    }

    // ---------------------------------------------------------------- modem

    /** The internet uplink: QEMU's user-mode NAT on its own, handing out addresses like a home router. */
    private void ensureModem(UUID id) {
        Process running = modems.get(id);
        if (running != null && running.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = modemStarted.get(id);
        if (last != null && now - last < 30_000) {
            return;
        }
        modemStarted.put(id, now);
        if (running != null) {
            stopModem(id);
        }
        Path qemu = QemuLocator.findSystemEmulator();
        if (qemu == null) {
            return;
        }
        try {
            Path dir = root.resolve(id.toString());
            Files.createDirectories(dir);
            modems.put(id, startUplink(qemu, id, dir.resolve("modem.log")));
            refreshLinks();
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not start the internet modem {}", id, e);
            fabric.closeUdpPorts(id);
        }
    }

    /**
     * Starts QEMU's user-mode NAT on its own and plugs it into port 0 of {@code id} in the fabric.
     * Everything a VM sends towards it passes the {@link UplinkFilter}.
     */
    private Process startUplink(Path qemu, UUID id, Path log) throws IOException {
        NetworkFabric.UdpBinding binding = fabric.openUdpPorts(id, 1)[0];
        fabric.markUplink(new PortRef(id, 0));
        List<String> command = List.of(qemu.toString(), "-M", "none", "-nodefaults", "-display", "none",
                "-monitor", "none", "-serial", "none",
                "-netdev", "dgram,id=lan,local.type=inet,local.host=127.0.0.1,local.port=" + binding.qemuPort()
                        + ",remote.type=inet,remote.host=127.0.0.1,remote.port=" + binding.modPort(),
                "-netdev", "user,id=wan,net=192.168.178.0/24,host=192.168.178.1,dhcpstart=192.168.178.20,dns=192.168.178.2",
                "-netdev", "hubport,id=h1,hubid=0,netdev=lan",
                "-netdev", "hubport,id=h2,hubid=0,netdev=wan");
        Process process = new ProcessBuilder(command).directory(log.getParent().toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        VmManager.trackProcess(process);
        return process;
    }

    /**
     * "Direct" network mode: the VM's only card gets a private uplink of its own, wired invisibly,
     * so it is online without cables but behind the same filter as every modem.
     */
    public NetworkFabric.UdpBinding openDirectUplink(UUID vm, Path dir) throws IOException {
        closeDirectUplink(vm);
        Path qemu = QemuLocator.findSystemEmulator();
        if (qemu == null) {
            throw new IOException("QEMU not found");
        }
        UUID uplink = directUplinkId(vm);
        NetworkFabric.UdpBinding card = fabric.openUdpPorts(vm, 1)[0];
        try {
            directUplinks.put(vm, startUplink(qemu, uplink, dir.resolve("uplink.log")));
        } catch (IOException e) {
            fabric.closeUdpPorts(vm);
            fabric.closeUdpPorts(uplink);
            throw e;
        }
        fabric.connect(new PortRef(vm, 0), new PortRef(uplink, 0), 0);
        return card;
    }

    public void closeDirectUplink(UUID vm) {
        Process process = directUplinks.remove(vm);
        if (process != null) {
            process.destroyForcibly();
            VmManager.untrackProcess(process);
        }
        UUID uplink = directUplinkId(vm);
        if (fabric.peer(new PortRef(uplink, 0)) != null) {
            fabric.disconnect(new PortRef(uplink, 0));
        }
        fabric.closeUdpPorts(uplink);
    }

    private static UUID directUplinkId(UUID vm) {
        return UUID.nameUUIDFromBytes(("direct-uplink:" + vm).getBytes(StandardCharsets.UTF_8));
    }

    private void stopModem(UUID id) {
        modemStarted.remove(id);
        Process process = modems.remove(id);
        if (process != null) {
            process.destroyForcibly();
            VmManager.untrackProcess(process);
        }
        fabric.closeUdpPorts(id);
    }

    public void close() {
        media.close();
        for (UUID id : List.copyOf(modems.keySet())) {
            stopModem(id);
        }
        for (UUID vm : List.copyOf(directUplinks.keySet())) {
            closeDirectUplink(vm);
        }
        for (Map.Entry<UUID, IoHost> entry : ioHosts.entrySet()) {
            saveIo(entry.getKey(), entry.getValue().io());
        }
        save();
        fabric.close();
    }

    // ---------------------------------------------------------------- storage

    private @Nullable Cable cableAt(PortRef port) {
        for (Cable cable : cables) {
            if (cable.uses(port)) {
                return cable;
            }
        }
        return null;
    }

    private void removeCable(Cable cable) {
        cables.remove(cable);
        fabric.disconnect(cable.a.ref());
    }

    private static ItemStack heldCable(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof CableItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack cableItem(CableType type, int color) {
        ItemStack stack = new ItemStack(ModRegistry.CABLES.get(type));
        if (color != type.defaultColor) {
            stack.set(ModRegistry.CABLE_COLOR, color);
        }
        return stack;
    }

    static String dimensionOf(net.minecraft.world.level.Level level) {
        return level.dimension().location().toString();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                List<Cable> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Cable>>() { }.getType());
                if (loaded != null) {
                    for (Cable cable : loaded) {
                        if (cable != null && cable.a != null && cable.b != null && cable.id != null) {
                            cables.add(cable);
                            wire(cable);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new ArrayList<>(cables)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Virtual_mashine.LOGGER.warn("Could not save {}", file, e);
        }
    }
}
