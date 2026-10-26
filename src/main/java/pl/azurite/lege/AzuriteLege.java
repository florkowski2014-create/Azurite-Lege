package pl.azurite.lege;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AzuriteLege extends JavaPlugin implements Listener, TabExecutor {

    private static final String PERM = "azurite.lege.admin";

    private static final class Lege {
        final String id;
        final ItemStack item;
        final Location block;
        final Material[] mat = new Material[4];
        final int[] amt = new int[4];

        Lege(String id, ItemStack item, Location block) {
            this.id = id;
            this.item = item;
            this.block = block;
        }
    }

    private final Map<String, Lege> leges = new LinkedHashMap<>();
    private final Map<String, List<Entity>> displays = new HashMap<>();
    private File dataFile;
    private int step = 0;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("azurite-lege");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        for (Lege l : leges.values()) spawn(l);
        Bukkit.getScheduler().runTaskTimer(this, this::rotate, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (String id : new ArrayList<>(displays.keySet())) despawn(id);
    }

    // ------------------------------------------------------------ storage

    private void load() {
        leges.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("leges");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            World w = Bukkit.getWorld(c.getString("world", ""));
            ItemStack item = c.getItemStack("item");
            if (w == null || item == null) {
                getLogger().warning("Pomijam legendarkę '" + id + "' (brak świata lub itemu).");
                continue;
            }
            Lege l = new Lege(id, item, new Location(w, c.getInt("x"), c.getInt("y"), c.getInt("z")));
            for (int i = 0; i < 4; i++) {
                String name = c.getString("ing." + (i + 1) + ".item");
                if (name == null) continue;
                Material m = Material.matchMaterial(name);
                if (m == null) continue;
                l.mat[i] = m;
                l.amt[i] = c.getInt("ing." + (i + 1) + ".amount", 1);
            }
            leges.put(id, l);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Lege l : leges.values()) {
            String p = "leges." + l.id;
            y.set(p + ".item", l.item);
            y.set(p + ".world", l.block.getWorld().getName());
            y.set(p + ".x", l.block.getBlockX());
            y.set(p + ".y", l.block.getBlockY());
            y.set(p + ".z", l.block.getBlockZ());
            for (int i = 0; i < 4; i++) {
                if (l.mat[i] == null) continue;
                y.set(p + ".ing." + (i + 1) + ".item", l.mat[i].name());
                y.set(p + ".ing." + (i + 1) + ".amount", l.amt[i]);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nie udało się zapisać data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ hologram

    private static String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    private Component hologram(Lege l) {
        ItemMeta meta = l.item.getItemMeta();
        Component name = (meta != null && meta.hasDisplayName())
                ? meta.displayName()
                : Component.text(l.id, NamedTextColor.RED);
        Component out = name;
        for (int i = 0; i < 4; i++) {
            Component line = l.mat[i] == null
                    ? Component.text("???", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    : Component.text(l.amt[i] + "x " + pretty(l.mat[i]), NamedTextColor.YELLOW);
            out = out.append(Component.newline()).append(line);
        }
        return out;
    }

    private static Transformation tr(float angle) {
        return new Transformation(
                new Vector3f(),
                new AxisAngle4f(angle, 0f, 1f, 0f),
                new Vector3f(1.2f, 1.2f, 1.2f),
                new AxisAngle4f());
    }

    private void spawn(Lege l) {
        World w = l.block.getWorld();
        if (!w.isChunkLoaded(l.block.getBlockX() >> 4, l.block.getBlockZ() >> 4)) return;
        despawn(l.id);
        Location base = l.block.clone().add(0.5, 1.0, 0.5);
        List<Entity> list = new ArrayList<>();

        ItemDisplay item = w.spawn(base.clone().add(0, 0.9, 0), ItemDisplay.class, d -> {
            d.setItemStack(l.item);
            d.setPersistent(false);
            d.setTransformation(tr(0f));
        });
        TextDisplay text = w.spawn(base.clone().add(0, 1.7, 0), TextDisplay.class, d -> {
            d.text(hologram(l));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setPersistent(false);
        });
        list.add(item);
        list.add(text);
        displays.put(l.id, list);
    }

    private void despawn(String id) {
        List<Entity> list = displays.remove(id);
        if (list != null) list.forEach(Entity::remove);
    }

    private void rotate() {
        step++;
        float angle = (float) (Math.PI / 2 * (step % 4));
        for (List<Entity> list : displays.values()) {
            for (Entity e : list) {
                if (e instanceof ItemDisplay d && d.isValid()) {
                    d.setTransformation(tr(angle));
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(20);
                }
            }
        }
    }

    private Lege at(Location loc) {
        for (Lege l : leges.values()) {
            if (l.block.getWorld().equals(loc.getWorld())
                    && l.block.getBlockX() == loc.getBlockX()
                    && l.block.getBlockY() == loc.getBlockY()
                    && l.block.getBlockZ() == loc.getBlockZ()) return l;
        }
        return null;
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (Lege l : leges.values()) {
            if (l.block.getWorld().equals(c.getWorld())
                    && (l.block.getBlockX() >> 4) == c.getX()
                    && (l.block.getBlockZ() >> 4) == c.getZ()) {
                Bukkit.getScheduler().runTask(this, () -> spawn(l));
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (at(e.getBlock().getLocation()) != null && !e.getPlayer().hasPermission(PERM)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Lege l = at(b.getLocation());
        if (l == null) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        for (int i = 0; i < 4; i++) {
            if (l.mat[i] == null) {
                p.sendMessage(Component.text("Przepis na tę legendarkę nie jest jeszcze gotowy.", NamedTextColor.RED));
                return;
            }
        }

        Map<Material, Integer> need = new EnumMap<>(Material.class);
        for (int i = 0; i < 4; i++) need.merge(l.mat[i], l.amt[i], Integer::sum);

        PlayerInventory inv = p.getInventory();
        for (Map.Entry<Material, Integer> en : need.entrySet()) {
            if (!inv.containsAtLeast(new ItemStack(en.getKey()), en.getValue())) {
                p.sendMessage(Component.text("Brakuje: " + en.getValue() + "x " + pretty(en.getKey()), NamedTextColor.RED));
                return;
            }
        }

        for (Map.Entry<Material, Integer> en : need.entrySet()) {
            inv.removeItem(new ItemStack(en.getKey(), en.getValue()));
        }
        Map<Integer, ItemStack> left = inv.addItem(l.item.clone());
        left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));

        despawn(l.id);
        leges.remove(l.id);
        save();

        p.getWorld().strikeLightningEffect(b.getLocation());
        ItemMeta meta = l.item.getItemMeta();
        Component name = (meta != null && meta.hasDisplayName())
                ? meta.displayName()
                : Component.text(l.id, NamedTextColor.RED);
        Bukkit.broadcast(Component.text(p.getName() + " zdobył legendarkę ", NamedTextColor.GOLD)
                .append(name)
                .append(Component.text("!", NamedTextColor.GOLD)));
    }

    // ------------------------------------------------------------ commands

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private static String idFrom(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String raw = (meta != null && meta.hasDisplayName())
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : item.getType().name();
        String id = raw.toLowerCase().replace('ł', 'l')
                .replaceAll("[ąáä]", "a").replaceAll("[ćč]", "c").replaceAll("[ęé]", "e")
                .replaceAll("[ń]", "n").replaceAll("[óö]", "o").replaceAll("[ś]", "s")
                .replaceAll("[źż]", "z")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "lege" : id;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission(PERM)) {
            msg(s, "Brak uprawnień.", NamedTextColor.RED);
            return true;
        }
        if (a.length == 0) {
            msg(s, "/azurite-lege set [id] | ingredient <id> <slot 1-4> <Item|none> [ilość] | remove <id>", NamedTextColor.GRAY);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "set" -> {
                if (!(s instanceof Player p)) {
                    msg(s, "Tylko dla gracza.", NamedTextColor.RED);
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    msg(s, "Weź legendarkę do ręki.", NamedTextColor.RED);
                    return true;
                }
                Block target = p.getTargetBlockExact(6);
                if (target == null) {
                    msg(s, "Patrz na blok, na którym ma stać legendarka (max 6 kratek).", NamedTextColor.RED);
                    return true;
                }
                String id = a.length > 1 ? a[1].toLowerCase() : idFrom(hand);
                if (leges.containsKey(id)) {
                    msg(s, "Legendarka '" + id + "' już istnieje. Usuń ją: /azurite-lege remove " + id, NamedTextColor.RED);
                    return true;
                }
                if (at(target.getLocation()) != null) {
                    msg(s, "Na tym bloku już stoi inna legendarka.", NamedTextColor.RED);
                    return true;
                }
                ItemStack copy = hand.clone();
                copy.setAmount(1);
                Lege l = new Lege(id, copy, target.getLocation());
                leges.put(id, l);
                save();
                spawn(l);
                msg(s, "Ustawiono legendarkę '" + id + "'. Składniki: /azurite-lege ingredient " + id + " <slot> <Item> <ilość>", NamedTextColor.GREEN);
            }
            case "ingredient" -> {
                if (a.length < 4) {
                    msg(s, "/azurite-lege ingredient <id> <slot 1-4> <Item|none> [ilość]", NamedTextColor.RED);
                    return true;
                }
                Lege l = leges.get(a[1].toLowerCase());
                if (l == null) {
                    msg(s, "Nie ma takiej legendarki.", NamedTextColor.RED);
                    return true;
                }
                int slot;
                try {
                    slot = Integer.parseInt(a[2]);
                } catch (NumberFormatException ex) {
                    slot = 0;
                }
                if (slot < 1 || slot > 4) {
                    msg(s, "Slot musi być od 1 do 4.", NamedTextColor.RED);
                    return true;
                }
                if (a[3].equalsIgnoreCase("none")) {
                    l.mat[slot - 1] = null;
                    l.amt[slot - 1] = 0;
                } else {
                    Material m = Material.matchMaterial(a[3]);
                    if (m == null || !m.isItem() || m.isAir()) {
                        msg(s, "Nieznany item: " + a[3], NamedTextColor.RED);
                        return true;
                    }
                    int amount = 1;
                    if (a.length > 4) {
                        try {
                            amount = Integer.parseInt(a[4]);
                        } catch (NumberFormatException ex) {
                            amount = 0;
                        }
                    }
                    if (amount < 1 || amount > 64) {
                        msg(s, "Ilość musi być od 1 do 64.", NamedTextColor.RED);
                        return true;
                    }
                    l.mat[slot - 1] = m;
                    l.amt[slot - 1] = amount;
                }
                save();
                spawn(l);
                msg(s, "Zapisano slot " + slot + ".", NamedTextColor.GREEN);
            }
            case "remove" -> {
                if (a.length < 2) {
                    msg(s, "/azurite-lege remove <id>", NamedTextColor.RED);
                    return true;
                }
                String id = a[1].toLowerCase();
                if (leges.remove(id) == null) {
                    msg(s, "Nie ma takiej legendarki.", NamedTextColor.RED);
                    return true;
                }
                despawn(id);
                save();
                msg(s, "Usunięto '" + id + "'.", NamedTextColor.GREEN);
            }
            default -> msg(s, "Nieznana podkomenda.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission(PERM)) return List.of();
        String last = a[a.length - 1].toLowerCase();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("set", "ingredient", "remove"));
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("ingredient") || a[0].equalsIgnoreCase("remove"))) {
            out.addAll(leges.keySet());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("ingredient")) {
            out.addAll(List.of("1", "2", "3", "4"));
        } else if (a.length == 4 && a[0].equalsIgnoreCase("ingredient")) {
            out.add("none");
            Arrays.stream(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                    .map(m -> m.name().toLowerCase())
                    .filter(n -> n.startsWith(last))
                    .limit(50)
                    .forEach(out::add);
        } else if (a.length == 5 && a[0].equalsIgnoreCase("ingredient")) {
            out.addAll(List.of("1", "8", "16", "32", "64"));
        }
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).toList();
    }
  }
