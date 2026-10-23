package dev.prismglass.scan;

/** One reason to believe a plugin is there. {@code weight} is 0-100: how sure this one fact alone makes us. */
public record Evidence(Method method, String detail, int weight) {}
