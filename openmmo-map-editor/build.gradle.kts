plugins {
  kotlin("jvm") version "2.3.20"
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jogamp.jogl:jogl-all:2.6.0")
  implementation("org.jogamp.jogl:jogl-all:2.6.0:natives-windows-amd64")
  implementation("org.jogamp.gluegen:gluegen-rt:2.6.0")
  implementation("org.jogamp.gluegen:gluegen-rt:2.6.0:natives-windows-amd64")
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(17)
}

application {
  mainClass.set("de.lananahwp.openmmo.mapeditor.MainKt")
  applicationDefaultJvmArgs =
      listOf(
          "--add-exports=java.base/java.lang=ALL-UNNAMED",
          "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
          "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
          "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
          "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
      )
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

tasks.register<JavaExec>("glTest") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("de.lananahwp.openmmo.mapeditor.GlTestKt")

    jvmArgs(
        "--add-exports=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
    )
}

tasks.register<JavaExec>("ndsHistoryTest") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.NdsHistoryTestKt")
}

tasks.register<JavaExec>("surfaceExtractionTest") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.SurfaceExtractionTestKt")
  args(projectDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("seamDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.NdsSeamDiagnosticKt")
  args(projectDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("textureDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.NdsTextureDiagnosticKt")
  args(projectDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("ndsEditingDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.NdsEditingDiagnosticKt")
  args(projectDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("propCatalogSheets") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.NdsPropCatalogGeneratorKt")
  args(projectDir.parentFile.absolutePath, layout.buildDirectory.dir("prop-catalog").get().asFile.absolutePath)
}

tasks.register<JavaExec>("meshInspect") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.MeshInspectKt")
  args(providers.gradleProperty("mesh").getOrElse(""))
}

tasks.register<JavaExec>("cliffDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.CliffDiagnosticKt")
  args(projectDir.parentFile.absolutePath, providers.gradleProperty("map").getOrElse(""))
}

tasks.register<JavaExec>("surfaceDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.SurfaceDiagnosticKt")
  args(projectDir.parentFile.absolutePath, providers.gradleProperty("map").getOrElse("MAP_NATIONAL_PARK"))
}

tasks.register<JavaExec>("lumiSceneExport") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.LumiSceneExportKt")
  args(
      providers.gradleProperty("decomp").getOrElse(projectDir.parentFile.resolve("decomp/pokeheartgold").absolutePath),
      providers.gradleProperty("map").getOrElse("MAP_ROUTE_1"),
      providers.gradleProperty("output").getOrElse(layout.buildDirectory.dir("lumi-scene").get().asFile.absolutePath))
  providers.gradleProperty("rom").orNull?.takeIf(String::isNotBlank)?.let { args(it) }
}

tasks.register<JavaExec>("spriteDiagnostic") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.core.NdsSpriteDiagnosticKt")
  args(providers.gradleProperty("rom").getOrElse(""))
}

tasks.register<JavaExec>("playerSpriteExport") {
  dependsOn(tasks.classes)
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("de.lananahwp.openmmo.mapeditor.core.NdsPlayerSpriteExportKt")
  args(
      providers.gradleProperty("rom").getOrElse(""),
      providers.gradleProperty("output").getOrElse(layout.buildDirectory.dir("player-sprites").get().asFile.absolutePath))
}
