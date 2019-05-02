package cuchaz.hellokludge.imgui

import cuchaz.kludge.imgui.Commands.BeginFlags
import cuchaz.kludge.imgui.Imgui
import cuchaz.kludge.imgui.context
import cuchaz.kludge.tools.*
import cuchaz.kludge.vulkan.*
import cuchaz.kludge.window.Monitors
import cuchaz.kludge.window.Size
import cuchaz.kludge.window.Window
import cuchaz.kludge.window.Windows
import java.nio.file.Paths


fun main() = autoCloser {

	// init the window manager
	Windows.init()
	Windows.autoClose()

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
	).autoClose()

	// listen to problems from vulkan
	vulkan.debugMessenger(
		severities = IntFlags.of(DebugMessenger.Severity.Error, DebugMessenger.Severity.Warning)
	) { severity, type, msg ->
		println("VULKAN: ${severity.toFlagsString()} ${type.toFlagsString()} $msg")
	}.autoClose()

	// make a window
	val win = Window(
		size = Size(640, 480),
		title = "Kludge Demo"
	).autoClose()
	win.centerOn(Monitors.primary)
	win.visible = true

	// make a surface for the window
	val surface = vulkan.surface(win).autoClose()

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
	).autoClose()
	val graphicsQueue = device.queues[graphicsFamily]!![0]
	val surfaceQueue = device.queues[surfaceFamily]!![0]

	// init ImGUI
	Imgui.load().autoClose()
	println("ImGUI loaded, version ${Imgui.version}")
	Imgui.context().autoClose()

	class Renderer(oldSwapchain: Swapchain? = null) : AutoCloseable {

		private val closer = AutoCloser()
		private fun <R:AutoCloseable> R.autoClose() = also { closer.add(this@autoClose) }
		override fun close() = closer.close()


		// build the swapchain
		val swapchain = physicalDevice.swapchainSupport(surface).run {
			swapchain(
				device,
				surfaceFormat = find(Image.Format.B8G8R8A8_UNORM, Image.ColorSpace.SRGB_NONLINEAR)
					?: surfaceFormats.first().also { println("using fallback surface format: $it") },
				presentMode = find(PresentMode.Mailbox)
					?: find(PresentMode.FifoRelaxed)
					?: find(PresentMode.Fifo)
					?: presentModes.first().also { println("using fallback present mode: $it") },
				oldSwapchain = oldSwapchain
			)
		}.autoClose()

		// make the render pass
		val colorAttachment =
			Attachment(
				format = swapchain.surfaceFormat.format,
				loadOp = LoadOp.Clear,
				storeOp = StoreOp.Store,
				finalLayout = Image.Layout.PresentSrc
			)
		val subpass =
			Subpass(
				pipelineBindPoint = PipelineBindPoint.Graphics,
				colorAttachments = listOf(
					colorAttachment to Image.Layout.ColorAttachmentOptimal
				)
			)
		val renderPass = device
			.renderPass(
				attachments = listOf(colorAttachment),
				subpasses = listOf(subpass),
				subpassDependencies = listOf(
					SubpassDependency(
						src = Subpass.External.dependency(
							stage = IntFlags.of(PipelineStage.ColorAttachmentOutput)
						),
						dst = subpass.dependency(
							stage = IntFlags.of(PipelineStage.ColorAttachmentOutput),
							access = IntFlags.of(Access.ColorAttachmentRead, Access.ColorAttachmentWrite)
						)
					)
				)
			).autoClose()

		// make one framebuffer for each swapchain image in the render pass
		val framebuffers = swapchain.images
			.map { image ->
				device.framebuffer(
					renderPass,
					imageViews = listOf(
						image.view().autoClose()
					),
					extent = swapchain.extent
				).autoClose()
			}

		// make the graphics pipeline
		val graphicsPipeline = device.graphicsPipeline(
			renderPass,
			stages = listOf(
				device.shaderModule(Paths.get("build/shaders/helloworld/shader.vert.spv"))
					.autoClose()
					.stage("main", ShaderStage.Vertex),
				device.shaderModule(Paths.get("build/shaders/helloworld/shader.frag.spv"))
					.autoClose()
					.stage("main", ShaderStage.Fragment)
			),
			inputAssembly = InputAssembly(InputAssembly.Topology.TriangleList),
			rasterizationState = RasterizationState(
				cullMode = IntFlags.of(CullMode.Back),
				frontFace = FrontFace.Counterclockwise
			),
			viewports = listOf(swapchain.viewport),
			scissors = listOf(swapchain.rect),
			colorAttachmentBlends = mapOf(
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
			)
		).autoClose()

		// make the descriptor pool
		// 1000 of each is probably enough, right?
		val descriptorPool = device.descriptorPool(
			maxSets = 1000,
			sizes = DescriptorType.Counts(
				DescriptorType.values().map { type -> type to 1000 }
			)
		).autoClose()

		// make a graphics command buffer for each framebuffer
		val commandPool = device
			.commandPool(
				graphicsFamily,
				flags = IntFlags.of(CommandPool.Create.ResetCommandBuffer)
			)
			.autoClose()
		val commandBuffers = framebuffers.map { commandPool.buffer() }

		// make semaphores for command buffer synchronization
		val imageAvailable = device.semaphore().autoClose()
		val renderFinished = device.semaphore().autoClose()

		// TODO: is this the right place for this?
		init {
			Imgui.init(win, graphicsQueue, descriptorPool, renderPass)
			Imgui.initFonts()
		}

		fun render() {

			// get the next frame info
			val imageIndex = swapchain.acquireNextImage(imageAvailable)
			val framebuffer = framebuffers[imageIndex]
			val commandBuffer = commandBuffers[imageIndex]

			// record the command buffer every frame
			commandBuffer.apply {

				begin(IntFlags.of(CommandBuffer.Usage.OneTimeSubmit))
				beginRenderPass(
					renderPass,
					framebuffer,
					swapchain.rect,
					mapOf(colorAttachment to ClearValue.Color.Float(0.0f, 0.0f, 0.0f))
				)

				// draw our triangle
				bindPipeline(graphicsPipeline)
				draw(vertices = 3)

				// draw the GUI
				Imgui.draw(this)

				endRenderPass()
				end()
			}

			// render the frame
			graphicsQueue.submit(
				commandBuffers[imageIndex],
				waitFor = listOf(Queue.WaitInfo(imageAvailable, IntFlags.of(PipelineStage.ColorAttachmentOutput))),
				signalTo = listOf(renderFinished)
			)
			surfaceQueue.present(
				swapchain,
				imageIndex,
				waitFor = renderFinished
			)
			surfaceQueue.waitForIdle()
		}
	}
	var renderer = Renderer().autoClose()

	// GUI state
	val winOpen = Ref.of(true)
	val check = Ref.of(false)
	var counter = 0
	val pickColors = listOf("Red", "Green", "Blue")
	var pickedColor = 0

	// main loop
	while (!win.shouldClose) {

		Windows.pollEvents()

		// define the gui for this frame
		Imgui.frame {

			setNextWindowPos(20f, 20f)
			begin("Gotta go fast", flags=IntFlags.of(BeginFlags.NoSavedSettings, BeginFlags.NoResize))
			text("display size: ${Imgui.io.displaySize.width} x ${Imgui.io.displaySize.height}")
			text("frame time: ${String.format("%.3f", 1000f*Imgui.io.deltaTime)} ms")
			text("FPS: ${String.format("%.3f", Imgui.io.frameRate)}")
			end()

			if (winOpen.value) {
				setNextWindowPos(20f, 120f)
				begin("Dear ImGUI", winOpen, IntFlags.of(BeginFlags.NoSavedSettings))

				text("This is a GUI!")

				if (checkbox("Yes?", check)) {
					println("checkbox: ${check.value}")
				}
				sameLine()
				text("Check is ${if (check.value) "checked" else "not checked"}")

				if (button("Increment!", width=200f, height=60f)) {
					counter++
				}
				sameLine()
				text("clicked $counter times")

				spacing()

				text("What's your favorite color?")
				if (beginCombo("##color1", pickColors[pickedColor])) {
					pickColors.forEachIndexed { i, label ->
						if (selectable(label, i == pickedColor)) {
							pickedColor = i
						}
					}
					endCombo()
				}

				spacing()

				text("Wait, what's that color again?")
				if (listBoxHeader("##color2", pickColors.size, pickColors.size)) {
					pickColors.forEachIndexed { i, label ->
						if (selectable(label, i == pickedColor)) {
							pickedColor = i
						}
					}
					listBoxFooter()
				}

				end()
			}
		}

		try {

			// finally!! render something!! =D =D =D
			renderer.render()

		} catch (ex: SwapchainOutOfDateException) {

			// probably the window changed, need to re-create the renderer
			device.waitForIdle()
			renderer = Renderer(renderer.swapchain).autoClose(replace = renderer)
		}
	}

	// wait for the device to finish before cleaning up
	device.waitForIdle()

} // end of scope here cleans up all autoClose() resources
