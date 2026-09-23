package dev.engine_room.flywheel.backend.compute.vk;

import org.lwjgl.vulkan.VK10;

/** Turns a VkResult into an exception that says which call failed. */
final class VkChecks {
	private VkChecks() {
	}

	static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException("vulkan: failed to " + what + " (VkResult " + result + ")");
		}
	}
}
