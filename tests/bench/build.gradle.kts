plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":engine"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    timeUnit.set("us")
}
