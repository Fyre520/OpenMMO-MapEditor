plugins {
  kotlin("jvm") version "2.3.20"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(17)
}

application {
  mainClass.set("de.lananahwp.openmmo.mapeditor.MainKt")
}

tasks.jar {
  manifest { attributes["Main-Class"] = "de.lananahwp.openmmo.mapeditor.MainKt" }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
