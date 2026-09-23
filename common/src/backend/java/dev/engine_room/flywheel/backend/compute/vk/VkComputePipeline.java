package dev.engine_room.flywheel.backend.compute.vk;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/**
 * A compute pipeline built by hand, because Blaze3D cannot describe one.
 *
 * <p>Three things stand in the way of reusing its machinery, and all three are cheaper to sidestep
 * than to patch. {@code RenderPipeline} demands a vertex and a fragment shader and a primitive
 * topology. {@code VulkanRenderPipeline.compile} hardcodes two stages and calls
 * {@code vkCreateGraphicsPipelines}. And {@code VulkanBindGroupLayout} hardcodes
 * {@code stageFlags(VERTEX | FRAGMENT)} with a three-case descriptor-type switch that has no
 * storage buffer in it.
 *
 * <p>What is borrowed instead is the pattern: push descriptors, so there is no pool to allocate
 * from or recycle - which is also why Blaze3D itself contains no {@code VkDescriptorPool} anywhere.
 */
public final class VkComputePipeline implements ComputePipeline {
	/** Every binding is a storage buffer; that is all a cull pass needs. */
	static final int MAX_BINDINGS = ComputePipeline.MAX_BINDINGS;

	private final VulkanDevice device;
	private final String label;
	private final long shaderModule;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final int localSizeX;
	private boolean closed;

	private VkComputePipeline(VulkanDevice device, String label, long shaderModule,
			long descriptorSetLayout, long pipelineLayout, long pipeline, int localSizeX) {
		this.device = device;
		this.label = label;
		this.shaderModule = shaderModule;
		this.descriptorSetLayout = descriptorSetLayout;
		this.pipelineLayout = pipelineLayout;
		this.pipeline = pipeline;
		this.localSizeX = localSizeX;
	}

	static VkComputePipeline compile(VulkanDevice device, Description description, int localSizeX) {
		VkDevice vkDevice = device.vkDevice();
		ByteBuffer spirv = VkSpirv.compile(description.label(), prelude() + description.source(),
				description.defines());

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
					.pCode(spirv);
			long[] modulePtr = new long[1];
			VkChecks.check(VK10.vkCreateShaderModule(vkDevice, moduleInfo, null, modulePtr),
					"create shader module for " + description.label());
			long shaderModule = modulePtr[0];

			// One set, flagged for push descriptors so nothing has to be allocated per frame. The
			// low bindings are storage buffers and the high ones combined image samplers, because a
			// descriptor set layout fixes each binding's type when the pipeline is built -- they
			// cannot be decided at bind time, so the split is a convention the shaders share.
			//
			// Declaring bindings a shader never uses is allowed and costs nothing; what is not
			// allowed is pushing a descriptor whose type disagrees with the layout.
			VkDescriptorSetLayoutBinding.Buffer bindings =
					VkDescriptorSetLayoutBinding.calloc(MAX_BINDINGS, stack);
			for (int i = 0; i < MAX_BINDINGS; i++) {
				bindings.get(i)
						.binding(i)
						.descriptorType(i < ComputePipeline.FIRST_IMAGE_BINDING
								? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
								: VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
						.descriptorCount(1)
						.stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
			}

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
					.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
					.pBindings(bindings);
			long[] setLayoutPtr = new long[1];
			VkChecks.check(VK10.vkCreateDescriptorSetLayout(vkDevice, layoutInfo, null, setLayoutPtr),
					"create descriptor set layout for " + description.label());
			long descriptorSetLayout = setLayoutPtr[0];

			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
					.pSetLayouts(stack.longs(descriptorSetLayout));
			long[] pipelineLayoutPtr = new long[1];
			VkChecks.check(VK10.vkCreatePipelineLayout(vkDevice, pipelineLayoutInfo, null, pipelineLayoutPtr),
					"create pipeline layout for " + description.label());
			long pipelineLayout = pipelineLayoutPtr[0];

			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
					.stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
					.module(shaderModule)
					.pName(stack.UTF8("main"));

			VkComputePipelineCreateInfo.Buffer pipelineInfo =
					VkComputePipelineCreateInfo.calloc(1, stack)
							.sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
							.stage(stage)
							.layout(pipelineLayout);
			long[] pipelinePtr = new long[1];
			VkChecks.check(VK10.vkCreateComputePipelines(vkDevice, VK10.VK_NULL_HANDLE,
							pipelineInfo, null, pipelinePtr),
					"create compute pipeline for " + description.label());

			return new VkComputePipeline(device, description.label(), shaderModule,
					descriptorSetLayout, pipelineLayout, pipelinePtr[0], localSizeX);
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	/**
	 * The version line, and the descriptor set that OpenGL does not have.
	 *
	 * <p>Omitting {@code set} in GLSL means set 0, which is the single set this pipeline layout
	 * declares, so the same shader source compiles for both backends unchanged.
	 */
	private static String prelude() {
		return """
				#version 460
				#define FLW_VK 1
				#define FLW_SET(n)
				#line 1
				""";
	}

	long pipeline() {
		return pipeline;
	}

	long pipelineLayout() {
		return pipelineLayout;
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public int localSizeX() {
		return localSizeX;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;

		VkDevice vkDevice = device.vkDevice();
		VK10.vkDestroyPipeline(vkDevice, pipeline, null);
		VK10.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
		VK10.vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
		VK10.vkDestroyShaderModule(vkDevice, shaderModule, null);
	}
}
