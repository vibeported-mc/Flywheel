package dev.engine_room.flywheel.lib.util;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * A {@link PoseStack} that recycles {@link PoseStack.Pose} objects.
 *
 * <p>Minecraft 26.2's {@link PoseStack} does this itself: it holds its poses in a list that only
 * ever grows and tracks the top with an index, so pushing reuses the pose already sitting there and
 * popping just moves the index back. This subclass is kept so existing code continues to compile,
 * but it no longer needs to add anything.
 *
 * @deprecated Vanilla's {@link PoseStack} already recycles poses.
 */
@Deprecated
public class RecyclingPoseStack extends PoseStack {
}
