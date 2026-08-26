float sphericalDistance(vec3 relativePos) {
    return length(relativePos);
}

float cylindricalDistance(vec3 relativePos) {
    float distXZ = length(relativePos.xz);
    float distY = abs(relativePos.y);
    return max(distXZ, distY);
}
