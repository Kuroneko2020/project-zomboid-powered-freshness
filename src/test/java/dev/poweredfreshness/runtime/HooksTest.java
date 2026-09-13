package dev.poweredfreshness.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;

/** Uses real game objects; only their resource-loading constructors are bypassed. */
public final class HooksTest {
    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        Class<?> hooks;
        try { hooks = Class.forName("dev.poweredfreshness.runtime.Hooks"); }
        catch (ClassNotFoundException ex) { throw new AssertionError("Age/container runtime is not implemented", ex); }
        Food food = allocate(Food.class);
        food.setAge(9);
        hooks.getMethod("writeAge", Food.class, float.class).invoke(null, food, 9.25f);
        require(food.getAge() == 9.25f, "Inactive hook must preserve the vanilla age write");
        ItemContainer container = new ItemContainer();
        hooks.getMethod("assignContainer", InventoryItem.class, ItemContainer.class).invoke(null, food, container);
        require(food.getContainer() == container, "Inactive hook must preserve the original container field write");
        hooks.getMethod("assignContainer", InventoryItem.class, ItemContainer.class).invoke(null, food, (Object) null);
        require(food.getContainer() == null, "Removing an item must actually clear its container");
        hooks.getMethod("applyAuthoritative", Food.class, float.class, float.class).invoke(null, food, 4f, 120f);
        require(food.getAge() == 4 && food.getLastAged() == 120, "Age snapshot must apply both age and clock anchor");
        hooks.getMethod("applyAuthoritative", Food.class, float.class, float.class).invoke(null, food, Float.NaN, 121f);
        require(food.getAge() == 4 && food.getLastAged() == 120, "Invalid snapshot must not poison real game fields");
        Hooks.enable();
        Field ready = hooks.getDeclaredField("ready"); ready.setAccessible(true); ready.setBoolean(null, true);
        hooks.getMethod("restoreContainer", InventoryItem.class, ItemContainer.class).invoke(null, food, container);
        require(food.getContainer() == container && food.getAge() == 4 && food.getLastAged() == 120,
                "Deserialization must attach the saved container without consuming its historical age interval");
        ready.setBoolean(null, false);
        System.out.println("HooksTest: 6 passed (real Food and ItemContainer objects)");
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        return type.cast(allocate.invoke(field.get(null), type));
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
