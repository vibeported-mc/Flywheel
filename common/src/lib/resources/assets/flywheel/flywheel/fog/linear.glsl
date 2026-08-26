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

vec4 linearFog(vec4 color, float fogValue, vec4 fogColor) {
    return vec4(mix(color.rgb, fogColor.rgb, fogValue * fogColor.a), color.a);
}

vec4 flw_fogFilter(vec4 color) {
    return linearFog(color, totalFogValue(), flw_fogColor);
}
