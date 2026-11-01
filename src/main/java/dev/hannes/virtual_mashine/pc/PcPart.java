package dev.hannes.virtual_mashine.pc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One real-world part from the data files; the fields depend on the category. */
public record PcPart(PartCategory category, String id, String brand, String name, JsonObject data) {
    public String str(String key) {
        JsonElement e = data.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    public int num(String key) {
        JsonElement e = data.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsInt();
    }

    public double dbl(String key) {
        JsonElement e = data.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsDouble();
    }

    public boolean bool(String key) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    public List<String> strs(String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = data.get(key);
        if (e instanceof JsonArray array) {
            array.forEach(item -> out.add(item.getAsString()));
        } else if (e != null && e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** A number inside a nested object, e.g. connectors.pcie8; 0 when missing. */
    public int sub(String object, String key) {
        JsonElement e = data.get(object);
        if (e instanceof JsonObject o && o.get(key) instanceof JsonElement v && v.isJsonPrimitive()) {
            return v.getAsInt();
        }
        return 0;
    }

    public String fullName() {
        return brand + " " + name;
    }

    /** The lines shown in the tooltip while Shift is held. */
    public List<String> details() {
        List<String> l = new ArrayList<>();
        switch (category) {
            case CPU -> {
                l.add("Socket: " + str("socket"));
                l.add("Cores / Threads: " + num("cores") + " / " + num("threads"));
                l.add(String.format(Locale.ROOT, "Clock: %.1f - %.1f GHz", dbl("base_ghz"), dbl("boost_ghz")));
                l.add("TDP: " + num("tdp") + " W");
                l.add("Memory: " + String.join(" / ", strs("memory")));
                l.add("Integrated graphics: " + (bool("igpu") ? "yes" : "no"));
                l.add("Stock cooler included: " + (bool("box_cooler") ? "yes" : "no"));
            }
            case MAINBOARD -> {
                l.add("Socket: " + str("socket") + "  (" + str("chipset") + ")");
                l.add("Form factor: " + str("form"));
                l.add("Memory: " + str("memory") + ", " + num("ram_slots") + " slots");
                l.add("M.2 slots: " + num("m2") + ", SATA ports: " + num("sata"));
            }
            case RAM -> {
                l.add("Type: " + str("memory") + "-" + num("speed"));
                l.add("Size: " + num("size_gb") + " GB (" + num("modules") + " modules)");
            }
            case GPU -> {
                l.add("VRAM: " + num("vram_gb") + " GB");
                l.add("Power: " + num("power") + " W, PSU " + num("min_psu") + " W+");
                List<String> plugs = new ArrayList<>();
                if (sub("connectors", "12vhpwr") > 0) plugs.add(sub("connectors", "12vhpwr") + "x 16-pin");
                if (sub("connectors", "pcie8") > 0) plugs.add(sub("connectors", "pcie8") + "x 8-pin");
                if (sub("connectors", "pcie6") > 0) plugs.add(sub("connectors", "pcie6") + "x 6-pin");
                l.add("Power plugs: " + (plugs.isEmpty() ? "none (slot power)" : String.join(", ", plugs)));
                l.add("Length: " + num("length_mm") + " mm");
            }
            case PSU -> {
                l.add("Power: " + num("watts") + " W, " + str("rating"));
                l.add("Cables: " + num("pcie8") + "x PCIe 8-pin, " + num("12vhpwr") + "x 16-pin");
            }
            case COOLER -> {
                boolean aio = "aio".equals(str("type"));
                l.add(aio ? "Water cooling, " + num("radiator_mm") + " mm radiator" : "Air cooler, " + num("height_mm") + " mm high");
                l.add("Sockets: " + String.join(", ", strs("sockets")));
                l.add("Cooling capacity: " + num("max_tdp") + " W");
            }
            case STORAGE -> {
                String slot = str("slot");
                l.add("Interface: " + switch (slot) {
                    case "m2" -> "M.2 NVMe";
                    case "sata25" -> "SATA 2.5\"";
                    default -> "SATA 3.5\" (HDD)";
                });
                l.add("Capacity: " + (num("size_gb") >= 1000 ? num("size_gb") / 1000 + " TB" : num("size_gb") + " GB"));
                l.add("Read: " + num("read_mbs") + " MB/s");
            }
        }
        return l;
    }
}
