import com.core.schema.tasks.GenerateSchemaTask
import com.core.schema.tasks.GenerateSbeTask

plugins {
    id("core.java-conventions")
}

dependencies {
    api(project(":infrastructure"))
    api(project(":platform"))

    testFixturesApi(testFixtures(project(":infrastructure")))
    testFixturesApi(testFixtures(project(":platform")))
}

val generateSchema by tasks.registering(GenerateSchemaTask::class) {
    schemaXml = "${projectDir}/src/main/resources/clob-schema.xml"
    outputDir = layout.buildDirectory.dir("generated/schema").get().asFile.absolutePath
}
sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated/schema"))
}
idea {
    module {
        generatedSourceDirs.add(layout.buildDirectory.dir("generated/schema").get().asFile)
    }
}

val generateSbeSchema by tasks.registering(GenerateSbeTask::class) {
    sbeXml = "${projectDir}/src/main/resources/clob-sbe-schema.xml"
    outputDir = layout.buildDirectory.dir("generated/sbe").get().asFile.absolutePath
}
sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated/sbe"))
}
idea {
    module {
        generatedSourceDirs.add(layout.buildDirectory.dir("generated/sbe").get().asFile)
    }
}

tasks.compileJava {
    dependsOn(generateSchema, generateSbeSchema)
}

tasks.register<Jar>("uberjar") {
    manifest {
        attributes("Main-Class" to "com.core.platform.Main")
    }
    archiveBaseName.set("core")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    with(tasks.jar.get())
}
