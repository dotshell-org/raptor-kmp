plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// Include raptor-kmp library sources directly (pure Kotlin, no platform APIs)
sourceSets {
    main {
        kotlin.srcDir("../src/commonMain/kotlin")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    // JSON parsing for report generator
    jmhImplementation("org.json:json:20231013")
}

jmh {
    fork = 2
    warmupIterations = 5
    iterations = 10
    resultFormat = "JSON"
    resultsFile = project.file("build/reports/jmh/results.json")
    jvmArgs = listOf("-Xmx1g", "-Xms512m")
    jvmArgsAppend = listOf("-Draptor.dataRoot=${rootProject.projectDir}")
    profilers = listOf("gc")
    // Only the routing benchmark (Load/Memory target absent datasets and would blow the 3-min budget).
    // gc profiler still gives alloc/op on the routing benchmark itself.
    includes = listOf("io\\.raptor\\.benchmark\\.RaptorBenchmark")
}

tasks.test {
    systemProperty("raptor.dataRoot", rootProject.projectDir.absolutePath)
    jvmArgs("-Xmx512m")
}

tasks.register<JavaExec>("generateReport") {
    group = "benchmark"
    description = "Generate BENCHMARK_RESULTS.md from JMH JSON output"
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("io.raptor.benchmark.ReportGenerator")
    args = listOf(
        project.file("build/reports/jmh/results.json").absolutePath,
        rootProject.file("BENCHMARK_RESULTS.md").absolutePath
    )
    workingDir = rootProject.projectDir
}

// On-demand benchmarks excluded from the default `jmh` task (they would blow its time budget).
// Wrapping the JMH jar in gradle tasks avoids shell-specific quoting issues with `java -jar`
// (bash line continuations and -D properties don't survive PowerShell verbatim).
fun registerJmhRun(taskName: String, pattern: String, heap: String, extraArgs: List<String> = emptyList()) =
    tasks.register<JavaExec>(taskName) {
        group = "benchmark"
        description = "Run $pattern through the JMH jar (results: build/reports/jmh/$taskName.json)"
        dependsOn(tasks.named("jmhJar"))
        classpath = files(layout.buildDirectory.file("libs/benchmark-jmh.jar"))
        mainClass.set("org.openjdk.jmh.Main")
        maxHeapSize = heap

        doFirst {
            layout.buildDirectory.dir("reports/jmh").get().asFile.mkdirs()
        }

        // Forked benchmark JVMs inherit the parent's input arguments (-D and -Xmx included)
        systemProperty("raptor.dataRoot", rootProject.projectDir.absolutePath)
        args = listOf(pattern) + extraArgs + listOf(
            "-rf", "json",
            "-rff", layout.buildDirectory.file("reports/jmh/$taskName.json").get().asFile.absolutePath
        )
    }

registerJmhRun("jmhNamedLyon", "io.raptor.benchmark.NamedRoutesBenchmark.*", "1g")
// High-precision LYON run (5 forks, 10+20 iterations) for refreshing the README tables, ~35 min
registerJmhRun("jmhPrecisionLyon", "io.raptor.benchmark.LyonPrecision.*", "1g")
registerJmhRun("jmhNamedRtm", "io.raptor.benchmark.NamedRoutesRtmBenchmark.*", "1g")
registerJmhRun("jmhNamedParis", "io.raptor.benchmark.NamedRoutesParisBenchmark.*", "3g")
// 1000-random-query aggregate on the Paris network (dataset param overridden via JMH CLI)
registerJmhRun("jmhAggregateParis", "io.raptor.benchmark.RaptorBenchmark.*", "3g", listOf("-p", "dataset=PARIS"))
// findNearbyStops: spatial grid vs the old brute-force scan, on the IDFM (Paris) stop set
registerJmhRun("jmhNearbyParis", "io.raptor.benchmark.NearbyStopsBenchmark.*", "2g")
// Dynamic edge penalties and segment blacklisting latency benchmark (RFC 0001 / Issue #11)
registerJmhRun("jmhEdgeFilter", "io.raptor.benchmark.EdgeFilterBenchmark.*", "1g")
