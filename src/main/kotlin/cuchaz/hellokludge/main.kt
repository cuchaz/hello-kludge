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

		val debug = vulkan.debugMessager(
			desiredSeverities = IntFlags.of(
				DebugMessager.Severity.Error,
				DebugMessager.Severity.Warning,
				DebugMessager.Severity.Verbose
			)
		) { severityFlags, typeFlags, msg ->
			println("VULKAN: $msg")
		}.autoClose(autoCloser)

		vulkan.debugInfo("Debug message!")

		// print device info
		for (device in vulkan.physicalDevices) {
			println("device: $device")
			println("extensions: ${device.extensionNames}")
			device.queueFamilies.forEach { println("\t$it") }
		}

		val win = Window(
			size = Size(640, 480),
			title = "Kludge Demo"
		).autoClose(autoCloser)

		win.centerOn(Monitors.primary)
		win.visible = true

		// make a surface
		val surface = vulkan.surface(win).autoClose(autoCloser)

		// connect to a device
		val physicalDevice = vulkan.physicalDevices[0]
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

		val graphicsQueue = device.queues[graphicsFamily]!![0]
		val surfaceQueue = device.queues[surfaceFamily]!![0]
		println("have queues:\n\t$graphicsQueue\n\t$surfaceQueue")

		// build the swapchain
		// TODO: cleanup this API?
		val swapchain = device.physicalDevice.swapchainSupport(surface).run {

			// TEMP
			println(capabilities)
			println("surface format: $surfaceFormats")
			println("present mode: $presentModes")

			return@run swapchain(
				device,
				surfaceFormat = pickSurfaceFormat(Format.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR),
				presentMode = pickPresentMode(
					SwapchainSupport.PresentMode.Mailbox,
					SwapchainSupport.PresentMode.FifoRelaxed,
					SwapchainSupport.PresentMode.Fifo
				)
			)
		}.autoClose(autoCloser)

		// TEMP
		println("swapchain!")
		for (image in swapchain.images) {
			println("\timage: $image")
			image.view(Image.ViewType.TwoD, swapchain.surfaceFormat.format).use { view ->
				println("\t\tview: $view")
			}
		}

		// get some shaders
		val vertShader = device.shaderModule(Paths.get("build/shaders/shader.vert.spv")).autoClose(autoCloser)
		val fragShader = device.shaderModule(Paths.get("build/shaders/shader.frag.spv")).autoClose(autoCloser)
		println("vertex shader: $vertShader")
		println("fragment shader: $fragShader")

		// make the graphics pipeline
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
			scissors = listOf(Rect2D(
				offset = Offset2D(0, 0),
				extent = swapchain.extent
			)),
			attachments = listOf(
				AttachmentDescription(
					format = swapchain.surfaceFormat.format,
					loadOp = AttachmentDescription.LoadOp.Clear,
					storeOp = AttachmentDescription.StoreOp.Store,
					finalLayout = ImageLayout.PresentSrc
				)
			),
			colorBlend = ColorBlendState(listOf(null)), // TODO: alpha blending?
			subpasses = listOf(
				Subpass(
					pipelineBindPoint = Subpass.PipelineBindPoint.Graphics,
					colorAttachments = listOf(AttachmentReference(0, ImageLayout.ColorAttachmentOptimal)) // TODO: make references easy
				)
			)
		).autoClose(autoCloser)

		// main loop
		while (!win.shouldClose()) {

			// TODO: render something

			Windows.pollEvents()
		}
	}

	// cleanup
	Windows.close()
}
