package dev.poweredfreshness;

import dev.poweredfreshness.net.FreshnessNetwork;
import dev.poweredfreshness.runtime.Hooks;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

@Exposer.LuaClass
public final class PoweredFreshness {
    private PoweredFreshness() { }
    public static void tick() { Hooks.tick(); }
    public static void onWorldStart() { Hooks.start(); }
    public static void onWorldSave() { Hooks.save(); }
    public static void onWorldStop() { Hooks.stop(); }
    public static void onSquareLoaded(IsoGridSquare square) { Hooks.squareLoaded(square); }
    public static void onObjectRemoving(IsoObject object) { Hooks.worldPermanentlyRemoving(object); }
    public static void onServerCommand(String command, KahluaTable args) { FreshnessNetwork.onServerCommand(command, args); }
    public static void onClientCommand(IsoPlayer player, String command, KahluaTable args) { FreshnessNetwork.onClientCommand(player, command, args); }
    public static boolean isEnabled() { return Hooks.isEnabled(); }
    public static long getReverseUpdateCount() { return Hooks.reverseUpdates(); }
}
