package dev.prismglass.module.render;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Custom sky, fog, cloud and water colours, and fog distance. 26.1 reads all of these as environment attributes
 * through the camera's EnvironmentAttributeProbe, so AmbienceMixin swaps the values there (render thread only;
 * the integrated server reads the same attributes for itself). Client-side look only.
 */
public class Ambience extends Module {
    public final BoolSetting sky = bool("Sky", true, "Custom sky colour.");
    public final ColorSetting skyColor = color("SkyColor", 0xFF7A5CFF, "Sky colour.").visibleWhen(() -> sky.get());
    public final BoolSetting fog = bool("Fog", true, "Custom fog colour.");
    public final ColorSetting fogColor = color("FogColor", 0xFFB197FC, "Fog colour (the horizon).").visibleWhen(() -> fog.get());
    public final BoolSetting clouds = bool("Clouds", false, "Custom cloud colour.");
    public final ColorSetting cloudColor = color("CloudColor", 0xFFFFC8F0, "Cloud colour.").visibleWhen(() -> clouds.get());
    public final BoolSetting water = bool("Water", false, "Custom underwater fog colour.");
    public final ColorSetting waterColor = color("WaterColor", 0xFF1E90FF, "Underwater fog colour.").visibleWhen(() -> water.get());
    public final NumberSetting fogDistance = num("FogDistance", 1, 0.25, 10, 0.25, "Multiplies how far the fog starts and ends (10 = practically no fog).");

    public Ambience() { super("Ambience", "Custom sky, fog, cloud and water colours; fog distance.", Category.RENDER); }

    /** AmbienceMixin: the value to use for this attribute (the original when untouched). */
    @SuppressWarnings("unchecked")
    public <V> V value(EnvironmentAttribute<V> attr, V original) {
        // 26.3: colour attributes are Vector3fc (RGB) / Vector4fc (ARGB, clouds), no longer packed ints
        if (attr == EnvironmentAttributes.SKY_COLOR && sky.get()) return (V) ARGB.vector3fFromRGB24(skyColor.color());
        if (attr == EnvironmentAttributes.FOG_COLOR && fog.get()) return (V) ARGB.vector3fFromRGB24(fogColor.color());
        if (attr == EnvironmentAttributes.CLOUD_COLOR && clouds.get()) return (V) ARGB.vector4fFromARGB32(cloudColor.color());
        if (attr == EnvironmentAttributes.WATER_FOG_COLOR && water.get()) return (V) ARGB.vector3fFromRGB24(waterColor.color());
        float k = fogDistance.getFloat();
        if (k != 1 && original instanceof Float f && (attr == EnvironmentAttributes.FOG_START_DISTANCE || attr == EnvironmentAttributes.FOG_END_DISTANCE
            || attr == EnvironmentAttributes.SKY_FOG_END_DISTANCE || attr == EnvironmentAttributes.WATER_FOG_START_DISTANCE
            || attr == EnvironmentAttributes.WATER_FOG_END_DISTANCE)) return (V) Float.valueOf(f * k);
        return original;
    }
}
