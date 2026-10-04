package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** Middle click: on a player toggles friend, otherwise throws an ender pearl (silent swap). */
public class MiddleClick extends Module {
    public final BoolSetting friends = bool("Friends", true, "Middle click players to friend/unfriend.");
    public final BoolSetting pearl = bool("Pearl", true, "Middle click air to throw a pearl.");
    private boolean wasDown;

    public MiddleClick() { super("MiddleClick", "Middle click actions (friend / pearl).", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean down = mc.screen == null && GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && !wasDown) click();
        wasDown = down;
    }

    private void click() {
        if (friends.get() && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player p) {
            String name = p.getGameProfile().name();
            if (Prism.friends().isFriend(name)) { Prism.friends().remove(name); ChatUtil.info("Unfriended " + name); }
            else { Prism.friends().add(name); ChatUtil.good("Friended " + name); }
            return;
        }
        if (!pearl.get()) return;
        int slot = InvUtil.findHotbar(Items.ENDER_PEARL);
        if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        InvUtil.swap(slot, false);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
    }
}
