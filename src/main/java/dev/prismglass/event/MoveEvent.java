package dev.prismglass.event;

import net.minecraft.world.phys.Vec3;

/** Fired from ClientPlayerEntity#move for self-movement. Modules may rewrite the motion vector. */
public class MoveEvent {
    public double x, y, z;

    public MoveEvent(Vec3 v) { x = v.x; y = v.y; z = v.z; }

    public Vec3 toVec() { return new Vec3(x, y, z); }

    public void setHorizontal(double vx, double vz) { x = vx; z = vz; }
}
