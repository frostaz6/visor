plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.assistant"
}

dependencies {
    api(project(":core:connection"))
    // The photo for a visual question comes over airship.
    implementation(project(":core:media"))
    implementation(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
