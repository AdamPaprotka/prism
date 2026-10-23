package dev.prismglass.manager;

import net.minecraft.world.phys.Vec3;

/**
 * A module that wants to walk somewhere. MovementHooks asks every enabled module implementing this, each tick
 * while building input, and steers toward the first point it gets with real WASD presses relative to the yaw our
 * movement uses (so it is plain legit input to Grim's prediction). Manual steering always wins, except plain W.
 */
public interface WalkGoal {
    /** Where to walk this tick, or null for no opinion. */
    Vec3 walkPoint();

    /** Sprint while walking forward (only where vanilla allows it). */
    default boolean walkSprint() { return true; }

    /** Jump when walking into a 1-block step. */
    default boolean walkJump() { return true; }

    /** Stand still when this close (horizontally) to the point. */
    default double walkStop() { return 0.3; }

    /** Refuse steps that lead off a drop, into lava, fire, cactus or the void. */
    default boolean walkAvoidHazards() { return true; }

    /** Called on ticks where every direction toward the point was unsafe or walled off, so we stood still. */
    default void walkBlocked() {}
}
