// Writes gradle.lockfile for every project so that OSV-Scanner sees the
// resolved runtime dependencies (security workflow). The build itself does not
// lock. Test classpaths are skipped: Android cannot resolve them on their own.
allprojects {
    dependencyLocking { lockAllConfigurations() }
    tasks.register("lockRuntimeClasspaths") {
        notCompatibleWithConfigurationCache("resolves configurations")
        doLast {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
            configurations
                .filter { it.isCanBeResolved && it.name.endsWith("untimeClasspath") && "Test" !in it.name }
                .forEach { it.incoming.resolutionResult.root }
        }
    }
}
