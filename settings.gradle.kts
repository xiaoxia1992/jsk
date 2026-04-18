rootProject.name = "kjs"

include(
    ":engine",
    ":cli",
    ":tests:unit",
    ":tests:test262-runner",
    ":tests:bench",
)
