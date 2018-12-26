package cuchaz.hellokludge

import cuchaz.kludge.tools.AutoCloser
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.autoClose
import cuchaz.kludge.vulkan.*
import cuchaz.kludge.window.*
import java.nio.file.Paths


fun main(args: Array<String>) {

	// TODO: make varargs overloads for IntFlags.of() args

	// listen to GLFW error messages
	Windows.init()
	Windows.errors.setOut(System.err)

	// check for vulkan support
	if (!Windows.isVulkanSupported) {
		throw Error("No Vulkan support from GLFW")
	}

	AutoCloser().use { autoCloser ->

		val vulkan = Vulkan(
			extensionNames = Windows.requiredVulkanExtensions + setOf(Vulkan.DebugExtension),
			layerNames = setOf(Vulkan.StandardValidationLayer)
		).autoClose(autoCloser)

		// listen to debug messages
		vulkan.debugMessager(
			desiredSeverities = IntFlags.of(
				DebugMessager.Severity.Error,
				DebugMessager.Severity.Warning,
				DebugMessager.Severity.Verbose
			)
		) { severityFlags, typeFlags, msg ->
			println("VULKAN: $msg")
		}.autoClose(autoCloser)

		vulkan.debugInfo("Debug message!")

		// TEMP: print device info
		for (device in vulkan.physicalDevices) {
			println("device: $device")
			println("extensions: ${device.extensionNames}")
			device.queueFamilies.forEach { println("\t$it") }
		}

		// make a window
		val win = Window(
			size = Size(640, 480),
			title = "Kludge Demo"
		).autoClose(autoCloser)
		win.centerOn(Monitors.primary)
		win.visible = true

		// make a surface for the window
		val surface = vulkan.surface(win).autoClose(autoCloser)

		// connect to a device
		val physicalDevice = vulkan.physicalDevices
			.sortedBy { if (it.properties.type == PhysicalDevice.Type.DiscreteGpu) 0 else 1 }
			.first()
		val graphicsFamily = physicalDevice.findQueueFamily(IntFlags.of(PhysicalDevice.QueueFamily.Flags.Graphics))
		val surfaceFamily = physicalDevice.findQueueFamily(surface)
		val device = physicalDevice.device(
			queuePriorities = mapOf(
				graphicsFamily to listOf(1.0f),
				surfaceFamily to listOf(1.0f)
			),
			extensionNames = setOf(PhysicalDevice.SwapchainExtension)
		).autoClose(autoCloser)
		println("have a device!: $device")

		// get device queues
		val graphicsQueue = device.queues[graphicsFamily]!![0]
		val surfaceQueue = device.queues[surfaceFamily]!![0]

		// build the swapchain
		// TODO: cleanup this API?
		val swapchain = device.physicalDevice.swapchainSupport(surface).run {
			swapchain(
				device,
				surfaceFormat = pickSurfaceFormat(Format.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR),
				presentMode = pickPresentMode(
					SwapchainSupport.PresentMode.Mailbox,
					SwapchainSupport.PresentMode.FifoRelaxed,
					SwapchainSupport.PresentMode.Fifo
				)
			)
		}.autoClose(autoCloser)
		println("have a swapchain: ${swapchain.surfaceFormat} ${swapchain.presentMode}")

		// get some shaders
		val vertShader = device.shaderModule(Paths.get("build/shaders/shader.vert.spv")).autoClose(autoCloser)
		val fragShader = device.shaderModule(Paths.get("build/shaders/shader.frag.spv")).autoClose(autoCloser)

		// make the graphics pipeline
		val rect = Rect2D(
			offset = Offset2D(0, 0),
			extent = swapchain.extent
		)
		val graphicsPipeline = device.graphicsPipeline(
			stages = listOf(
				vertShader.Stage("main", IntFlags.of(ShaderStage.Vertex)),
				fragShader.Stage("main", IntFlags.of(ShaderStage.Fragment))
			),
			vertexInput = VertexInput(),
			inputAssembly = InputAssembly(InputAssembly.Topology.TriangleList, false),
			viewports = listOf(Viewport(
				0.0f,
				0.0f,
				swapchain.extent.width.toFloat(),
				swapchain.extent.height.toFloat()
			)),
			scissors = listOf(rect),
			attachments = listOf(
				AttachmentDescription( // TODO: clear color problem might be here?
					format = swapchain.surfaceFormat.format,
					loadOp = AttachmentDescription.LoadOp.Clear,
					storeOp = AttachmentDescription.StoreOp.Store,
					finalLayout = ImageLayout.PresentSrc
				)
			),
			colorBlend = ColorBlendState(listOf(null)), // TODO: alpha blending?
			subpasses = listOf(
				Subpass( // TODO: clear color problem might be here?
					pipelineBindPoint = Subpass.PipelineBindPoint.Graphics,
					colorAttachments = listOf(
						AttachmentReference(0, ImageLayout.ColorAttachmentOptimal)
					) // TODO: make references easy
				)
			),
			subpassDependencies = listOf(
				SubpassDependency(
					srcSubpass = SubpassDependency.External,
					dstSubpass = 0,
					srcStageMask = IntFlags.of(PipelineStage.ColorAttachmentOutput),
					dstStageMask = IntFlags.of(PipelineStage.ColorAttachmentOutput),
					srcAccessMask = IntFlags(0),
					dstAccessMask = IntFlags.of(AccessFlags.ColorAttachmentRead, AccessFlags.ColorAttachmentWrite)
				)
			)
		).autoClose(autoCloser)

		// make one framebuffer for each swapchain image
		val framebuffers = swapchain.images.map { image ->
			val imageView = image.view(Image.ViewType.TwoD, swapchain.surfaceFormat.format)
				.autoClose(autoCloser)
			return@map device.framebuffer(
				graphicsPipeline,
				listOf(imageView),
				width = swapchain.extent.width,
				height = swapchain.extent.height,
				layers = 1
			).autoClose(autoCloser)
		}

		val commandPool = device.commandPool(graphicsFamily).autoClose(autoCloser)

		// make a graphics command buffer for each framebuffer
		val commandBuffers = framebuffers.mapIndexed { index, framebuffer ->
			val v = index*0.5f + 0.5f
			commandPool.buffer().apply {
				begin(IntFlags.of(CommandBuffer.UsageFlags.SimultaneousUse))
				beginRenderPass(
					graphicsPipeline,
					framebuffer,
					rect,
					ClearValue.Color.Float(v, v, v)
				)
				// TEMP
				//bind(graphicsPipeline)
				//draw(3, 1)
				endRenderPass()
				end()
			}
		}

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

			// TEMP
			Thread.sleep(100)

			// TODO: measure FPS?
		}

		// wait for the device to finish before cleaning up
		device.waitForIdle()
	}

	// cleanup
	Windows.close()
}
