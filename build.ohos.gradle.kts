plugins {
    // HarmonyOS uses an isolated build graph so Android/iOS remain untouched.
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
    id("com.google.devtools.ksp").version("2.0.21-1.0.27").apply(false)
}
