plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("io.kjs.test262.MainKt")
}
