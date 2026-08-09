plugins {
  kotlin("jvm") version "2.3.20"
  application
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

tasks.register<JavaExec>("smokeTest") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.SmokeTestKt")
}
