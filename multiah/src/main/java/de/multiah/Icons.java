package de.multiah;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Liefert zu einer Item-ID (z.B. minecraft:cobblestone) einen Stack für die Anzeige im GUI. */
public final class Icons {
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private Icons() {}

    public static ItemStack of(String id) {
        ItemStack cached = CACHE.get(id);
        if (cached != null) return cached;
        ItemStack result = ItemStack.EMPTY;
        for (Item it : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(it).toString().equals(id)) {
                result = it.getDefaultInstance();
                break;
            }
        }
        CACHE.put(id, result);
        return result;
    }
}
