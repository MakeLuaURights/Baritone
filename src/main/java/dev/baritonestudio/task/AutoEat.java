package dev.baritonestudio.task;

import dev.baritonestudio.config.ModConfig;
import java.util.Set;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Ест еду, когда голод опускается ниже порога, и возвращает прежний слот. */
public final class AutoEat {
    private static final Set<Item> NEVER = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE, Items.TROPICAL_FISH);

    private boolean eating;
    private int prevSlot, ticks, cooldown;

    private static boolean isFood(ItemStack s) {
        return !s.isEmpty() && s.contains(DataComponentTypes.FOOD) && !NEVER.contains(s.getItem());
    }

    /** @return true, пока идёт приём пищи (задачу на этот тик нужно пропустить) */
    public boolean tick(Actor a) {
        ClientPlayerEntity p = a.player();
        if (!ModConfig.get().autoEat || p.isCreative() || p.isSpectator()) {
            eating = false;
            return false;
        }
        PlayerInventory inv = p.getInventory();
        if (!eating) {
            if (cooldown > 0) {
                cooldown--;
                return false;
            }
            if (p.getHungerManager().getFoodLevel() > 14 || !p.canConsume(false)) return false;
            if (!a.hasItem(AutoEat::isFood)) {
                cooldown = 200;
                return false;
            }
            prevSlot = inv.getSelectedSlot();
            a.selectItem(AutoEat::isFood);
            eating = true;
            ticks = 0;
        }
        ticks++;
        a.use(true);
        if ((!p.isUsingItem() && ticks > 4) || ticks > 90) {
            eating = false;
            cooldown = 10;
            inv.setSelectedSlot(prevSlot);
            return false;
        }
        return true;
    }
}
