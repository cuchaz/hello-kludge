package cuchaz.hellokludge.texquad

import cuchaz.kludge.tools.*
import cuchaz.kludge.vulkan.*
import cuchaz.kludge.window.*
import java.awt.image.DataBufferByte
import java.nio.file.Paths
import javax.imageio.ImageIO


fun main(args: Array<String>) =	autoCloser {

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

	// read an image
	val cpuImage = javaClass.getResourceAsStream("/texquad/vulkan.png").use { instream ->
		ImageIO.read(instream)
	}
	val cpuImageBytes = (cpuImage.raster.dataBuffer as DataBufferByte).data

	// make a window that matches the image size
	val win = Window(
		size = Size(cpuImage.width, cpuImage.height),
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

	// create the command pool for command buffers submitted to graphics queues
	val commandPool = device.commandPool(graphicsFamily).autoClose()

	// allocate the image on the GPU
	val gpuImage = device.
		image(
			type = Image.Type.TwoD,
			extent = Extent3D(cpuImage.width, cpuImage.height, 1),
			format = Image.Format.R8G8B8A8_UNORM,
			usage = IntFlags.of(Image.Usage.TransferDst, Image.Usage.Sampled)
		)
		.autoClose()
		.allocate { memType ->
			memType.flags.hasAll(IntFlags.of(
				MemoryType.Flags.DeviceLocal
			))
		}
		.autoClose()
	val gpuImageView = gpuImage.image.view(
		// ImageIO has the color channels ABGR order for some reason
		components = Image.Components(
			Image.Swizzle.A,
			Image.Swizzle.B,
			Image.Swizzle.G,
			Image.Swizzle.R
		)
	).autoClose()

	// upload the image the GPU
	autoCloser {
		graphicsQueue.submit(commandPool.buffer().apply {
			begin(IntFlags.of(CommandBuffer.Usage.OneTimeSubmit))

			// transition image to transfer write
			pipelineBarrier(
				srcStage = IntFlags.of(PipelineStage.TopOfPipe),
				dstStage = IntFlags.of(PipelineStage.Transfer),
				images = listOf(
					gpuImage.image.barrier(
						dstAccess = IntFlags.of(Access.TransferWrite),
						newLayout = Image.Layout.TransferDstOptimal
					)
				)
			)

			// allocate a staging buffer and write the image to it
			val stagingBuf = device
				.buffer(
					cpuImageBytes.size.toLong(),
					IntFlags.of(Buffer.Usage.TransferSrc)
				)
				.autoClose()
				.allocate { memType ->
					memType.flags.hasAll(IntFlags.of(
						MemoryType.Flags.HostVisible,
						MemoryType.Flags.HostCoherent
					))
				}
				.autoClose()
				.apply {
					memory.map { buf ->
						buf.put(cpuImageBytes)
						buf.flip()
					}
				}

			copyBufferToImage(stagingBuf.buffer, gpuImage.image, Image.Layout.TransferDstOptimal)

			// transition image to shader read
			pipelineBarrier(
				srcStage = IntFlags.of(PipelineStage.Transfer),
				dstStage = IntFlags.of(PipelineStage.FragmentShader),
				images = listOf(
					gpuImage.image.barrier(
						// TODO: check if we really need the src/old spec, or if it's just redundant
						srcAccess = IntFlags.of(Access.TransferWrite),
						dstAccess = IntFlags.of(Access.ShaderRead),
						oldLayout = Image.Layout.TransferDstOptimal,
						newLayout = Image.Layout.ShaderReadOnlyOptimal
					)
				)
			)

			end()
		})
		graphicsQueue.waitForIdle()
	}

	// make a sampler
	val sampler = device.sampler().autoClose()

	// allocate the vertex buffer on the GPU
	val vertexBuf = device.
		buffer(
			size = Float.SIZE_BYTES.toLong()*4*4,
			usage = IntFlags.of(Buffer.Usage.VertexBuffer, Buffer.Usage.TransferDst)
		)
		.autoClose()
		.allocate { memType ->
			memType.flags.hasAll(IntFlags.of(
				MemoryType.Flags.DeviceLocal
			))
		}
		.autoClose()

	// upload vertices to the vertex buffer
	autoCloser {
		graphicsQueue.submit(commandPool.buffer().apply {
			begin(IntFlags.of(CommandBuffer.Usage.OneTimeSubmit))

			// allocate a staging buffer and write vertex data to it
			val stagingBuf = device
				.buffer(
					vertexBuf.buffer.size,
					IntFlags.of(Buffer.Usage.TransferSrc)
				)
				.autoClose()
				.allocate { memType ->
					memType.flags.hasAll(IntFlags.of(
						MemoryType.Flags.HostVisible,
						MemoryType.Flags.HostCoherent
					))
				}
				.autoClose()
				.apply {
					memory.map { buf ->

						// make two ccw triangles in a fan
						// in clip space
						// with texture coords
						buf.putFloats(
							-1.0f,  1.0f,  0.0f, 1.0f, // lower left
							 1.0f,  1.0f,  1.0f, 1.0f, // lower right
							 1.0f, -1.0f,  1.0f, 0.0f, // upper right
							-1.0f, -1.0f,  0.0f, 0.0f // upper left
						)
						buf.flip()
					}
				}

			copyBuffer(stagingBuf.buffer, vertexBuf.buffer)

			end()
		})
		graphicsQueue.waitForIdle()
	}

	// build the swapchain
	val swapchain = physicalDevice.swapchainSupport(surface).run {
		swapchain(
			device,
			surfaceFormat = find(Image.Format.B8G8R8A8_UNORM, Image.ColorSpace.SRGB_NONLINEAR)
				?: surfaceFormats.first().also { println("using fallback surface format: $it") },
			presentMode = find(PresentMode.Mailbox)
				?: find(PresentMode.FifoRelaxed)
				?: find(PresentMode.Fifo)
				?: presentModes.first().also { println("using fallback present mode: $it") }
		)
	}.autoClose()
	val swapchainImageViews = swapchain.images
		.map {
			it.view().autoClose()
		}

	// build the descriptor set layout
	val samplerBinding = DescriptorSetLayout.Binding(
		binding = 1,
		type = DescriptorType.CombinedImageSampler,
		stages = IntFlags.of(ShaderStage.Fragment)
	)
	val descriptorSetLayout = device.descriptorSetLayout(listOf(samplerBinding)).autoClose()

	// make a descriptor set for each swapchain image
	val descriptorPool = device.descriptorPool(
		maxSets = swapchainImageViews.size,
		sizes = DescriptorType.Counts(
			DescriptorType.CombinedImageSampler to swapchainImageViews.size
		)
	).autoClose()
	val descriptorSets = descriptorPool.allocate(
		swapchainImageViews.map { descriptorSetLayout }
	)

	// update each descriptor set with its sampler and image
	device.updateDescriptorSets(
		writes = descriptorSets.map { set ->
			set.address(samplerBinding).write(
				images = listOf(
					DescriptorSet.ImageInfo(
						sampler = sampler,
						view = gpuImageView,
						layout = Image.Layout.ShaderReadOnlyOptimal
					)
				)
			)
		}
	)

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

	// make the graphics pipeline
	val graphicsPipeline = device.graphicsPipeline(
		renderPass,
		stages = listOf(
			device.shaderModule(Paths.get("build/shaders/texquad/shader.vert.spv"))
				.autoClose()
				.stage("main", ShaderStage.Vertex),
			device.shaderModule(Paths.get("build/shaders/texquad/shader.frag.spv"))
				.autoClose()
				.stage("main", ShaderStage.Fragment)
		),
		descriptorSetLayouts = listOf(descriptorSetLayout),
		vertexInput = VertexInput {
			binding(stride = Float.SIZE_BYTES*4) {
				attribute(
					location = 0,
					format = Image.Format.R32G32_SFLOAT,
					offset = 0
				)
				attribute(
					location = 1,
					format = Image.Format.R32G32_SFLOAT,
					offset = Float.SIZE_BYTES*2
				)
			}
		},
		inputAssembly = InputAssembly(InputAssembly.Topology.TriangleFan),
		rasterizationState = RasterizationState(
			cullMode = IntFlags.of(CullMode.Back),
			frontFace = FrontFace.Counterclockwise
		),
		viewports = listOf(swapchain.viewport),
		scissors = listOf(swapchain.rect),
		attachmentBlends = listOf(
			colorAttachment to ColorBlendState.Attachment(
				color = ColorBlendState.Attachment.Part(
					src = BlendFactor.SrcAlpha,
					dst = BlendFactor.OneMinusSrcAlpha,
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

	// make one framebuffer for each swapchain image
	val framebuffers = swapchainImageViews
		.map { swapchainImageView ->
			device.framebuffer(
				renderPass,
				imageViews = listOf(swapchainImageView),
				extent = swapchain.extent
			).autoClose()
		}

	// make a graphics command buffer for each framebuffer
	val renderCommandBuffers = (0 until framebuffers.size).map { i ->
		commandPool.buffer().apply {

			// draw the quad in a single render pass
			begin(IntFlags.of(CommandBuffer.Usage.SimultaneousUse))
			beginRenderPass(
				renderPass,
				framebuffers[i],
				swapchain.rect,
				ClearValue.Color.Float(0.8f, 0.8f, 0.8f)
			)
			bindPipeline(graphicsPipeline)
			bindVertexBuffer(vertexBuf.buffer)
			bindDescriptorSet(descriptorSets[i], graphicsPipeline)
			draw(vertices = 4)
			endRenderPass()
			end()
		}
	}

	// make semaphores for command buffer synchronization
	val imageAvailable = device.semaphore().autoClose()
	val renderFinished = device.semaphore().autoClose()

	// main loop
	while (!win.shouldClose()) {

		Windows.pollEvents()

		// render a frame to the next image in the swapchain
		val imageIndex = swapchain.acquireNextImage(imageAvailable)
		graphicsQueue.submit(
			renderCommandBuffers[imageIndex],
			waitFor = listOf(Queue.WaitInfo(imageAvailable, IntFlags.of(PipelineStage.ColorAttachmentOutput))),
			signalTo = listOf(renderFinished)
		)

		// present the swapchain image
		surfaceQueue.present(
			swapchain,
			imageIndex,
			waitFor = renderFinished
		)
		surfaceQueue.waitForIdle()
	}

	// wait for the device to finish before cleaning up
	device.waitForIdle()

} // end of scope here cleans up all autoClose() resources
