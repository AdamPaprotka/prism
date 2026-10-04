package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

public class ESP extends Module {
    public final ModeSetting mode = mode("Mode", "Both", "Box, outline glow, or both.", "Box", "Glow", "Both");
    public final BoolSetting players = bool("Players", true, "Players.");
    public final BoolSetting hostiles = bool("Hostiles", true, "Monsters.");
    public final BoolSetting passives = bool("Passives", false, "Animals.");
    public final BoolSetting items = bool("Items", false, "Dropped items.");
    public final BoolSetting crystals = bool("Crystals", true, "End crystals.");
    public final ColorSetting playerColor = color("PlayerColor", 0xFFFF5C7A, "Enemy players.");
    public final ColorSetting friendColor = color("FriendColor", 0xFF5CFFB0, "Friends.");
    public final ColorSetting hostileColor = color("HostileColor", 0xFFFFA94D, "Monsters.");
    public final ColorSetting passiveColor = color("PassiveColor", 0xFF8CE99A, "Animals.");
    public final ColorSetting itemColor = color("ItemColor", 0xFFE9ECEF, "Items.");
    public final ColorSetting crystalColor = color("CrystalColor", 0xFFB197FC, "Crystals.");
    public final NumberSetting fillAlpha = num("FillAlpha", 40, 0, 255, 1, "Box fill opacity.");

    public ESP() { super("ESP", "See entities through walls.", Category.RENDER); }

    private boolean matches(Entity e) {
        if (e == mc.player && !mc.options.getCameraType().isFirstPerson()) return false;
        if (e == mc.player) return false;
        if (e instanceof Player) return players.get();
        if (e instanceof Enemy) return hostiles.get();
        if (e instanceof ItemEntity) return items.get();
        if (e instanceof EndCrystal) return crystals.get();
        if (e instanceof LivingEntity) return passives.get();
        return false;
    }

    private int colorOf(Entity e) {
        if (e instanceof Player p) return Prism.friends().isFriend(p) ? friendColor.color() : playerColor.color();
        if (e instanceof Enemy) return hostileColor.color();
        if (e instanceof ItemEntity) return itemColor.color();
        if (e instanceof EndCrystal) return crystalColor.color();
        return passiveColor.color();
    }

    public boolean glows(Entity e) {
        // vanilla outlines are part of the game frame, so StreamProof falls back to (hidden) boxes
        return !mode.is("Box") && matches(e) && !dev.prismglass.streamproof.Overlay.active();
    }

    public int glowColor(Entity e, int original) { return glows(e) ? colorOf(e) & 0xFFFFFF : original; }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (mode.is("Glow") && !dev.prismglass.streamproof.Overlay.active()) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!matches(e)) continue;
            int c = colorOf(e);
            Render3D.box(Render3D.lerpBox(e, delta), ColorUtil.withAlpha(c, fillAlpha.getInt()), c, true);
        }
    }

    @Override public String getInfo() { return mode.get(); }
}
