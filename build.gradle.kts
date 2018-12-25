import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
	kotlin("jvm") version "1.3.10"
}


group = "cuchaz"
version = "0.1"

repositories {
	jcenter()
}

dependencies {
	compile(kotlin("stdlib-jdk8"))
	compile("cuchaz:kludge")
}

configure<JavaPluginConvention> {
	sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {

	kotlinOptions {

		jvmTarget = "1.8"

		// enable experimental features
		languageVersion = "1.3"
		freeCompilerArgs += "-XXLanguage:+InlineClasses"
	}
}

tasks {

	val compileShaders by creating {
		group = "build"
		doLast {

			val workingDir = buildDir.resolve("shaders")
			workingDir.mkdirs()

			fileTree("src/main/glsl")
				.matching {
					include("**/*.vert")
					include("**/*.frag")
					// TODO: other shader stages?
				}
				.forEach { file ->
					exec {
						this.workingDir = workingDir
						commandLine("glslangValidator", "-V", file.absolutePath)
					}
				}
		}
	}

	this["build"].dependsOn(compileShaders)
}
