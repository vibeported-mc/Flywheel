// Minecraft 26.2 draws with a reversed depth buffer: the projection is built with near and far
// swapped under zero-to-one clip control, so device depth is 1 at the near plane and 0 at the far
// plane. These are the conversions for that mapping; the OIT passes write gl_FragDepth through
// them straight into the level's depth buffer, so getting this wrong poisons every later depth
// test in the frame - vanilla's included.
float linearize_depth(float d, float zNear, float zFar) {
    float z_n = 1.0 - 2.0 * d;
    return 2.0 * zNear * zFar / (zFar + zNear - z_n * (zFar - zNear));
}

float delinearize_depth(float linearDepth, float zNear, float zFar) {
    float z_n = (2.0 * zNear * zFar / linearDepth) - (zFar + zNear);
    return 0.5 * (1.0 - z_n / (zNear - zFar));
}
