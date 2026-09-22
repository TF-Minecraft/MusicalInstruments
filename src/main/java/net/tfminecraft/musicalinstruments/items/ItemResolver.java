package net.tfminecraft.musicalinstruments.items;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

// ====================================
// Resolves a config item path into an ItemStack.
//
// Supported formats:
//   v.<MATERIAL>                                   vanilla item
//   m.<TYPE>.<ID>                                  MMOItems item
//   ia.<namespace:id>                              ItemsAdder item
//   nx.<id>                                        Nexo item
//   modeled(type=..;name=..;model=..)              vanilla item with display name / custom model data
//
// MMOItems, ItemsAdder and Nexo are reached through reflection, so none of them
// is a compile-time or runtime requirement: paths that need a missing plugin
// simply fail to resolve and the instrument is skipped.
// ====================================
public class ItemResolver {

    private final Logger logger;

    public ItemResolver(Logger logger) {
        this.logger = logger;
    }

    // Returns null when the path is malformed or the backing plugin/item is missing.
    public ItemStack resolve(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String trimmed = path.trim();
        String type = trimmed.split("\\.", 2)[0];

        if (trimmed.toLowerCase().startsWith("modeled(")) {
            return resolveModeled(trimmed);
        }

        if (type.equalsIgnoreCase("v")) {
            return resolveVanilla(trimmed);
        }

        if (type.equalsIgnoreCase("m")) {
            return resolveMMOItems(trimmed);
        }

        if (type.equalsIgnoreCase("ia")) {
            return resolveItemsAdder(trimmed);
        }

        if (type.equalsIgnoreCase("nx")) {
            return resolveNexo(trimmed);
        }

        logger.warning("Unknown item path prefix in '" + trimmed + "'. Expected v., m., ia., nx. or modeled(...).");
        return null;
    }

    // v.iron_ingot
    private ItemStack resolveVanilla(String path) {
        String[] parts = path.split("\\.", 2);
        if (parts.length < 2) {
            logger.warning("Malformed vanilla item path '" + path + "'. Expected v.<material>.");
            return null;
        }

        Material material = Material.matchMaterial(parts[1].toUpperCase());
        if (material == null) {
            logger.warning("Unknown material '" + parts[1] + "' in item path '" + path + "'.");
            return null;
        }

        return new ItemStack(material, 1);
    }

    // modeled(type=paper;name=&6Flute;model=1001)
    private ItemStack resolveModeled(String path) {
        int open = path.indexOf('(');
        int close = path.lastIndexOf(')');
        if (open < 0 || close < open) {
            logger.warning("Malformed modeled item path '" + path + "'. Expected modeled(type=..;name=..;model=..).");
            return null;
        }

        Map<String, String> attributes = new HashMap<>();
        for (String part : path.substring(open + 1, close).split(";")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2) {
                attributes.put(keyValue[0].trim().toLowerCase(), keyValue[1].trim());
            }
        }

        Material material = Material.matchMaterial(attributes.getOrDefault("type", "DIRT").toUpperCase());
        if (material == null) {
            logger.warning("Invalid material type in modeled item '" + path + "'.");
            return null;
        }

        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (attributes.containsKey("name")) {
                meta.setDisplayName(attributes.get("name").replace('&', '§'));
            }
            if (attributes.containsKey("model")) {
                try {
                    meta.setCustomModelData(Integer.parseInt(attributes.get("model")));
                } catch (NumberFormatException e) {
                    logger.warning("Invalid model data in modeled item '" + path + "'.");
                }
            }
            item.setItemMeta(meta);
        }

        return item;
    }

    // m.material.salt  ->  MMOItems.plugin.getItems().getMMOItem(type, id).newBuilder().build()
    private ItemStack resolveMMOItems(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 3) {
            logger.warning("Malformed MMOItems path '" + path + "'. Expected m.<type>.<id>.");
            return null;
        }

        if (!isPluginEnabled("MMOItems")) {
            logger.warning("Item '" + path + "' requires MMOItems, which is not installed.");
            return null;
        }

        try {
            Object mmoPlugin = Class.forName("net.Indyuce.mmoitems.MMOItems").getField("plugin").get(null);

            Object typeManager = mmoPlugin.getClass().getMethod("getTypes").invoke(mmoPlugin);
            Object itemType = invokeByName(typeManager, "get", 1, parts[1].toUpperCase());
            if (itemType == null) {
                logger.warning("Unknown MMOItems type '" + parts[1] + "' in '" + path + "'.");
                return null;
            }

            Object itemManager = mmoPlugin.getClass().getMethod("getItems").invoke(mmoPlugin);
            Object mmoItem = invokeByName(itemManager, "getMMOItem", 2, itemType, parts[2].toUpperCase());
            if (mmoItem == null) {
                logger.warning("Unknown MMOItems item '" + path + "'.");
                return null;
            }

            Object builder = invokeByName(mmoItem, "newBuilder", 0);
            Object built = builder == null ? null : invokeByName(builder, "build", 0);
            if (built instanceof ItemStack stack) {
                return stack;
            }

            logger.warning("MMOItems returned no item for '" + path + "'.");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Failed to read MMOItems item '" + path + "': " + e);
            return null;
        }
    }

    // ia.tfmc:written_letter  ->  CustomStack.getInstance("tfmc:written_letter").getItemStack()
    private ItemStack resolveItemsAdder(String path) {
        String[] parts = path.split("\\.", 2);
        if (parts.length < 2) {
            logger.warning("Malformed ItemsAdder path '" + path + "'. Expected ia.<namespace:id>.");
            return null;
        }

        if (!isPluginEnabled("ItemsAdder")) {
            logger.warning("Item '" + path + "' requires ItemsAdder, which is not installed.");
            return null;
        }

        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = customStack.getMethod("getInstance", String.class).invoke(null, parts[1]);
            if (stack == null) {
                logger.warning("Unknown ItemsAdder item '" + path + "'.");
                return null;
            }

            Object item = invokeByName(stack, "getItemStack", 0);
            if (item instanceof ItemStack itemStack) {
                itemStack.setAmount(1);
                return itemStack;
            }

            logger.warning("ItemsAdder returned no item for '" + path + "'.");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Failed to read ItemsAdder item '" + path + "': " + e);
            return null;
        }
    }

    // nx.accordion  ->  NexoItems.itemFromId("accordion").build()
    private ItemStack resolveNexo(String path) {
        String[] parts = path.split("\\.", 2);
        if (parts.length < 2) {
            logger.warning("Malformed Nexo path '" + path + "'. Expected nx.<id>.");
            return null;
        }

        if (!isPluginEnabled("Nexo")) {
            logger.warning("Item '" + path + "' requires Nexo, which is not installed.");
            return null;
        }

        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object builder = nexoItems.getMethod("itemFromId", String.class).invoke(null, parts[1]);
            if (builder == null) {
                logger.warning("Unknown Nexo item '" + path + "'.");
                return null;
            }

            Object item = invokeByName(builder, "build", 0);
            if (item instanceof ItemStack itemStack) {
                itemStack.setAmount(1);
                return itemStack;
            }

            logger.warning("Nexo returned no item for '" + path + "'.");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Failed to read Nexo item '" + path + "': " + e);
            return null;
        }
    }

    // Looks a method up by name and parameter count instead of exact signature,
    // so the plugin keeps working across MMOItems/ItemsAdder/Nexo API type changes.
    private Object invokeByName(Object target, String name, int paramCount, Object... args)
            throws ReflectiveOperationException {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (Method method : current.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
        }
        throw new NoSuchMethodException(name + "/" + paramCount + " on " + target.getClass().getName());
    }

    private boolean isPluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }
}
