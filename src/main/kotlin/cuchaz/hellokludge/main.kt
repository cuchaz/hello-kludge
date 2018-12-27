package cuchaz.hellokludge

import cuchaz.kludge.tools.*
import cuchaz.kludge.vulkan.*
import cuchaz.kludge.window.*
import java.nio.file.Paths


fun main(args: Array<String>) {
	AutoCloser().use { autoCloser ->

		// init the window manager
		Windows.init()
		Windows.autoClose(autoCloser)

		// check for vulkan support from the window manager
		if (!Windows.isVulkanSupported) {
			throw Error("No Vulkan support from window manager")
		}

		// listen to problems from the window manager
		Windows.errors.setOut(System.err)

		// make the main vulkan instance with the extensions we need
		val vulkan = Vulkan(
			extensionNames = Windows.requiredVulkanExtensions + setOf(Vulkan.DebugExtension),
			layerNames = setOf(Vulkan.StandardValidationLayer)
		).autoClose(autoCloser)

		// listen to problems from vulkan
		vulkan.debugMessenger(
			severities = IntFlags.of(DebugMessenger.Severity.Error, DebugMessenger.Severity.Warning)
		) { severity, type, msg ->
			println("VULKAN: ${severity.toFlagsString()} ${type.toFlagsString()} $msg")
		}.autoClose(autoCloser)

		vulkan.debugWarn("Don't forget about the thing!")

		// make a window
		val win = Window(
			size = Size(640, 480),
			title = "Kludge Demo"
		).autoClose(autoCloser)
		win.centerOn(Monitors.primary)
		win.visible = true

		// make a surface for the window
		val surface = vulkan.surface(win).autoClose(autoCloser)

		// pick a physical device: prefer discrete GPU
		val physicalDevice = vulkan.physicalDevices
			.asSequence()
			.sortedBy { if (it.properties.type == PhysicalDevice.Type.DiscreteGpu) 0 else 1 }
			.first()

		// create the device and the queues
		val graphicsFamily = physicalDevice.findQueueFamily(IntFlags.of(PhysicalDevice.QueueFamily.Flags.Graphics))
		val surfaceFamily = physicalDevice.findQueueFamily(surface)
		val device = physicalDevice.device(
			queuePriorities = mapOf(
				graphicsFamily to listOf(1.0f),
				surfaceFamily to listOf(1.0f)
			),
			extensionNames = setOf(PhysicalDevice.SwapchainExtension)
		).autoClose(autoCloser)
		val graphicsQueue = device.queues[graphicsFamily]!![0]
		val surfaceQueue = device.queues[surfaceFamily]!![0]

		// build the swapchain
		val swapchain = physicalDevice.swapchainSupport(surface).run {
			swapchain(
				device,
				surfaceFormat = find(Format.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR)
					?: surfaceFormats.first().also { println("using fallback surface format: $it") },
				presentMode = find(PresentMode.Mailbox)
					?: find(PresentMode.FifoRelaxed)
					?: find(PresentMode.Fifo)
					?: presentModes.first().also { println("using fallback present mode: $it") }
			)
		}.autoClose(autoCloser)

		// make the graphics pipeline
		// TODO: support multiple render passes
		val colorAttachment =
			Attachment(
				format = swapchain.surfaceFormat.format,
				loadOp = LoadOp.Clear,
				storeOp = StoreOp.Store,
				finalLayout = ImageLayout.PresentSrc
			)
			.Ref(0, ImageLayout.ColorAttachmentOptimal)
		val subpass =
			Subpass(
				pipelineBindPoint = Subpass.PipelineBindPoint.Graphics,
				colorAttachments = listOf(colorAttachment)
			)
			.Ref(0)
		val graphicsPipeline = device.graphicsPipeline(
			stages = listOf(
				device.shaderModule(Paths.get("build/shaders/shader.vert.spv"))
					.autoClose(autoCloser)
					.Stage("main", ShaderStage.Vertex),
				device.shaderModule(Paths.get("build/shaders/shader.frag.spv"))
					.autoClose(autoCloser)
					.Stage("main", ShaderStage.Fragment)
			),
			inputAssembly = InputAssembly(InputAssembly.Topology.TriangleList),
			viewports = listOf(swapchain.viewport),
			scissors = listOf(swapchain.rect),
			attachments = listOf(
				colorAttachment to ColorBlendState.Attachment(
					color = ColorBlendState.Attachment.Part(
						src = BlendFactor.One,
						dst = BlendFactor.Zero,
						op = BlendOp.Add
					),
					alpha = ColorBlendState.Attachment.Part(
						src = BlendFactor.One,
						dst = BlendFactor.Zero,
						op = BlendOp.Add
					)
				)
			),
			subpasses = listOf(subpass),
			subpassDependencies = listOf(
				SubpassDependency(
					src = Subpass.External.dependency(
						stage = IntFlags.of(PipelineStage.ColorAttachmentOutput)
					),
					dst = subpass.dependency(
						stage = IntFlags.of(PipelineStage.ColorAttachmentOutput),
						access = IntFlags.of(AccessFlags.ColorAttachmentRead, AccessFlags.ColorAttachmentWrite)
					)
				)
			)
		).autoClose(autoCloser)

		// make one framebuffer for each swapchain image
		val framebuffers = swapchain.images
			.map { image ->
				device.framebuffer(
					graphicsPipeline,
					imageViews = listOf(
						image.view(Image.ViewType.TwoD, swapchain.surfaceFormat.format).autoClose(autoCloser)
					),
					extent = swapchain.extent
				).autoClose(autoCloser)
			}

		// make a graphics command buffer for each framebuffer
		val commandPool = device.commandPool(graphicsFamily).autoClose(autoCloser)
		val commandBuffers = framebuffers.map { framebuffer ->
			commandPool.buffer().apply {

				// fill the command buffer with a single render pass that draws our triangle
				begin(IntFlags.of(CommandBuffer.UsageFlags.SimultaneousUse))
				beginRenderPass(
					graphicsPipeline,
					framebuffer,
					swapchain.rect,
					ClearValue.Color.Float(0.0f, 0.0f, 0.0f)
				)
				bind(graphicsPipeline)
				draw(vertices = 3)
				endRenderPass()
				end()
			}
		}

		// make semaphores for command buffer synchronization
		val imageAvailable = device.semaphore().autoClose(autoCloser)
		val renderFinished = device.semaphore().autoClose(autoCloser)

		// main loop
		while (!win.shouldClose()) {

			Windows.pollEvents()

			// finally!! render something!! =D =D =D
			val imageIndex = swapchain.acquireNextImage(imageAvailable)
			graphicsQueue.submit(
				imageAvailable,
				IntFlags.of(PipelineStage.ColorAttachmentOutput),
				commandBuffers[imageIndex],
				renderFinished
			)
			surfaceQueue.present(
				renderFinished,
				swapchain,
				imageIndex
			)
			surfaceQueue.waitForIdle()

			// TODO: measure FPS?
		}

		// wait for the device to finish before cleaning up
		device.waitForIdle()

	} // end of scope here cleans up all autoClose() resources
}
