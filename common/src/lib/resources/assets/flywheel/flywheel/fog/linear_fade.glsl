float linearFogValue(float distance, float fogStart, float fogEnd) {
    if (distance <= fogStart) {
        return 0.0;
    }

    return distance < fogEnd ? smoothstep(fogStart, fogEnd, distance) : 1.0;
}

// Minecraft 26.2 applies an environmental fog measured spherically and a render distance fog
// measured cylindrically, taking whichever is stronger.
float totalFogValue() {
    return max(linearFogValue(flw_distance, flw_fogEnvironmentalRange.x, flw_fogEnvironmentalRange.y),
               linearFogValue(flw_cylindricalDistance, flw_fogRenderDistanceRange.x, flw_fogRenderDistanceRange.y));
}

vec4 linearFogFade(vec4 color, float fogValue) {
    return fogValue >= 1.0 ? vec4(0.0) : color * (1.0 - fogValue);
}

vec4 flw_fogFilter(vec4 color) {
    return linearFogFade(color, totalFogValue());
}
