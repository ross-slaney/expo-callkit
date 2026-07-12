plugins {
    kotlin("jvm") version "2.1.20"
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("../../android/src/main/java")
        kotlin.include("dev/rossslaney/expocallkit/PendingAnswers.kt")
        kotlin.include("dev/rossslaney/expocallkit/Models.kt")
        kotlin.include("dev/rossslaney/expocallkit/EventReplayPolicy.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test {
    useJUnit()
}
