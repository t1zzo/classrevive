rootProject.name = "classrevive"
include("model", "bytecode", "intake", "engine-spi", "engine-cfr", "engine-vineflower", "engine-procyon", "engine-jadx", "dex", "transforms", "verification", "selection", "pipeline", "sandbox", "workbench", "gui", "cli")
rootProject.children.forEach { it.projectDir = file("modules/${it.name}") }
