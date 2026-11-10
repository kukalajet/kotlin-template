plugins {
    id("kotlin-conventions")
}

// Business values, their invariants and domain errors. Arrow only: no SQL,
// HTTP or serialization.
dependencies {
    api(libs.arrow.core)
}
