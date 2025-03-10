package de.michiruf.invsync.mixin;

import de.michiruf.invsync.InvSync;
import de.michiruf.invsync.event.PlayerDataService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    public abstract PlayerManager getPlayerManager();

    @Inject(method = "saveAll", at = @At("HEAD"))
    public void onSave(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        getPlayerManager().getPlayerList().forEach(player ->
                PlayerDataService.savePlayer(player, InvSync.instance.database, InvSync.instance.config));
    }
}
