package dev.baritonestudio.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к тексту и стороне таблички в экране редактирования (запись и повтор надписей). */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("messages")
    String[] baritonestudio$getMessages();

    @Accessor("front")
    boolean baritonestudio$isFront();

    @Accessor("blockEntity")
    SignBlockEntity baritonestudio$getBlockEntity();
}
