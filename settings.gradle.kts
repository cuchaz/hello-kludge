rootProject.name = "hello-kludge"

pluginManagement {
	repositories {
		maven("https://dl.bintray.com/kotlin/kotlin-eap")
		jcenter()
		maven("https://plugins.gradle.org/m2/")
	}
}

includeBuild("../kludge")
