plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    implementation(kotlin("stdlib"))
    // ASM for emitting JVM bytecode at runtime (template JIT).
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
}
