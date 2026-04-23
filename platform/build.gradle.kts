import com.core.schema.tasks.GenerateFixTask
import com.core.schema.tasks.GenerateSchemaTask

plugins {
    id("core.java-conventions")
}

dependencies {
    api(project(":infrastructure"))
    api("io.aeron:aeron-client:1.50.1")
    api("io.aeron:aeron-annotations:1.50.1")
    api("io.aeron:aeron-driver:1.50.1")
    api("io.aeron:aeron-archive:1.50.1")
    api("uk.co.real-logic:sbe-tool:1.37.0")

    testFixturesApi(testFixtures(project(":infrastructure")))
}

val generateSchema by tasks.registering(GenerateSchemaTask::class) {
    schemaXml = "${projectDir}/src/testFixtures/resources/test-schema.xml"
    outputDir = layout.buildDirectory.dir("generated/schema").get().asFile.absolutePath
}
sourceSets.named("testFixtures") {
    java.srcDir(layout.buildDirectory.dir("generated/schema"))
}
idea {
    module {
        generatedSourceDirs.add(layout.buildDirectory.dir("generated/schema").get().asFile)
    }
}

val generateFixSchema by tasks.registering(GenerateFixTask::class) {
    schemaXml = "${projectDir}/src/main/resources/quickfix42.xml"
    outputDir = layout.buildDirectory.dir("generated/fix").get().asFile.absolutePath
}
sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated/fix"))
}
idea {
    module {
        generatedSourceDirs.add(layout.buildDirectory.dir("generated/fix").get().asFile)
    }
}

tasks.compileJava {
    dependsOn(generateSchema, generateFixSchema)
}
