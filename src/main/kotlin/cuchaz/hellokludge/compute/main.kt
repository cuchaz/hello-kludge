package cuchaz.hellokludge.compute

import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.autoCloser
import cuchaz.kludge.tools.toFlagsString
import cuchaz.kludge.vulkan.*
import cuchaz.kludge.window.Windows
import java.nio.file.Paths


fun main() = autoCloser {

	// make the main vulkan instance with the extensions we need
	val canDebug = Vulkan.DebugExtension in Vulkan.supportedExtensions
	val vulkan = Vulkan(
		extensionNames = (setOf(Vulkan.DebugExtension).takeIf { canDebug } ?: emptySet()),
		layerNames = setOf(Vulkan.StandardValidationLayer)
	).autoClose()

	// listen to problems from vulkan, if possible
	if (canDebug) {
		vulkan.debugMessenger(
			severities = IntFlags.of(DebugMessenger.Severity.Error, DebugMessenger.Severity.Warning)
		) { severity, type, msg ->
			println("VULKAN: ${severity.toFlagsString()} ${type.toFlagsString()} $msg")
		}.autoClose()
	}

	// pick a physical device: prefer discrete GPU
	val physicalDevice = vulkan.physicalDevices
		.asSequence()
		.sortedBy { if (it.properties.type == PhysicalDevice.Type.DiscreteGpu) 0 else 1 }
		.first()

	// create the device and the queues
	val computeFamily = physicalDevice.findQueueFamily(IntFlags.of(PhysicalDevice.QueueFamily.Flags.Compute))
	val device = physicalDevice.device(
		queuePriorities = mapOf(
			computeFamily to listOf(1.0f)
		)
	).autoClose()
	val computeQueue = device.queues[computeFamily]!![0]

	// get the memory type to use for computer shader inputs,outputs
	// (pick a memory type we can map directly into the host address space)
	val memoryType = physicalDevice.memoryTypes
		.firstOrNull {
			it.flags.hasAll(IntFlags.of(
				MemoryType.Flags.HostVisible,
				MemoryType.Flags.HostCoherent
			))
		}
		?: throw Error("failed to find suitable memory type")

	// set the shader inputs
	val inBuf = device
		.buffer(
			size = Int.SIZE_BYTES*16L,
			usage = IntFlags.of(Buffer.Usage.StorageBuffer)
		)
		.autoClose()
		.allocate(memoryType)
		.autoClose()
		.apply {
			memory.map { buf ->

				// write consecutive integers from 0 to 16
				for (i in 0 until 16) {
					buf.putInt(i)
				}
				buf.flip()
			}
		}

	// allocate space for the shader outputs
	val outBuf = device
		.buffer(
			size = Int.SIZE_BYTES*16L,
			usage = IntFlags.of(Buffer.Usage.StorageBuffer)
		)
		.autoClose()
		.allocate(memoryType)
		.autoClose()

	// build the descriptor set layout
	val inBufBinding = DescriptorSetLayout.Binding(
		binding = 0,
		type = DescriptorType.StorageBuffer,
		stages = IntFlags.of(ShaderStage.Compute)
	)
	val outBufBinding = DescriptorSetLayout.Binding(
		binding = 1,
		type = DescriptorType.StorageBuffer,
		stages = IntFlags.of(ShaderStage.Compute)
	)
	val descriptorSetLayout = device.descriptorSetLayout(listOf(
		inBufBinding, outBufBinding
	)).autoClose()

	// make the descriptor set
	val descriptorPool = device.descriptorPool(
		maxSets = 1,
		sizes = DescriptorType.Counts(
			DescriptorType.StorageBuffer to 2
		)
	).autoClose()
	val descriptorSet = descriptorPool
		.allocate(descriptorSetLayout)
		.apply {
			device.updateDescriptorSets(
				writes = listOf(
					address(inBufBinding).write(buffers = listOf(DescriptorSet.BufferInfo(inBuf.buffer))),
					address(outBufBinding).write(buffers = listOf(DescriptorSet.BufferInfo(outBuf.buffer)))
				)
			)
		}

	// make the compute pipeline
	val computePipeline = device.computePipeline(
		stage = device.shaderModule(Paths.get("build/shaders/compute/shader.comp.spv"))
			.autoClose()
			.stage("main", ShaderStage.Compute),
		descriptorSetLayouts = listOf(descriptorSetLayout)
	).autoClose()

	// run the shader in a command buffer
	val commandPool = device.commandPool(computeFamily).autoClose()
	computeQueue.submit(commandPool.buffer().apply {
		begin(IntFlags.of(CommandBuffer.Usage.OneTimeSubmit))

		bindPipeline(computePipeline)
		bindDescriptorSet(descriptorSet, computePipeline)
		dispatch(16)

		end()
	})

	// wait for the shader to finish
	computeQueue.waitForIdle()

	// read the result
	inBuf.memory.map { buf ->
		for (i in 0 until 16) {
			println("in[$i] = ${buf.int}")
		}
	}
	outBuf.memory.map { buf ->
		for (i in 0 until 16) {
			println("out[$i] = ${buf.int}")
		}
	}

	// wait for the device to finish before cleaning up
	device.waitForIdle()

} // end of scope here cleans up all autoClose() resources
