import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.Method;

public class CheckMethods {
    public static void main(String[] args) {
        System.out.println("Entity methods:");
        for(Method m : Entity.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("save") || m.getName().toLowerCase().contains("read") || m.getName().toLowerCase().contains("additional")) {
                System.out.println(m.toString());
            }
        }
        System.out.println("CompoundTag methods:");
        for(Method m : CompoundTag.class.getDeclaredMethods()) {
            if (m.getName().contains("getInt")) {
                System.out.println(m.toString());
            }
        }
    }
}
