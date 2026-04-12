plugins {
    checkstyle
    idea
    jacoco
    java
    `java-library`
    `java-test-fixtures`
    `maven-publish`
    pmd
}

group = "com.core"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
}

dependencies {
    testImplementation("org.assertj:assertj-core:3.20.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.7.2")
    testImplementation("org.mockito:mockito-core:3.6.28")
    testFixturesImplementation("org.assertj:assertj-core:3.20.2")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.7.2")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-params:5.7.2")
    testFixturesImplementation("org.mockito:mockito-core:3.6.28")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation", "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"))
}

tasks.compileTestJava {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation", "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"))
}

checkstyle {
    toolVersion = "10.2"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isShowViolations = true
    isIgnoreFailures = false
}

pmd {
    incrementalAnalysis.set(true)
    toolVersion = "6.45.0"
    ruleSetConfig = resources.text.fromFile("${rootProject.projectDir}/config/pmd/pmd.xml")
    ruleSets = emptyList()
    isConsoleOutput = true
    isIgnoreFailures = false
}

tasks.javadoc {
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).apply {
        addBooleanOption("html5", true)
        tags = listOf("apiSpec", "apiNote", "implSpec", "implNote")
    }
    exclude("**/HighPrecisionTime.java")
}
