package dev.poweredfreshness.net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.luaj.compiler.LuaCompiler;

public final class LuaBridgeTest {
    public static void main(String[] args) throws Exception {
        Path path = args.length == 0
                ? Path.of("mod/PoweredFreshness/42/media/lua/shared/PoweredFreshnessBridge.lua")
                : Path.of(args[0]);
        if (LuaCompiler.loadstring(Files.readString(path), path.getFileName().toString(),
                new KahluaTableImpl(new HashMap<>())) == null) throw new AssertionError("Lua compilation returned null");
        System.out.println("LuaBridgeTest: PASS (game Kahlua compiler)");
    }
}
