package io.github.sfsmoon.animatuna

import java.io.File

import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.host.LauncherSymbols
import io.github.sfsmoon.animatuna.host.ShellSymbols

/** Every launcher member and field this module reaches is there in each recorded Pixel Launcher build. */
class LauncherSymbolsTest {

    @Test
    fun everyShellMemberIsInEveryRecordedBuild() {
        val fixtures = recordedBuilds(ShellSymbols.PACKAGE)
        assertTrue("No SystemUI build recorded", fixtures.isNotEmpty())
        fixtures.forEach { fixture ->
            val members = methodsByClass(fixture)
            val missing = ShellSymbols.all.filter { members[it.className]?.contains(it.signature) != true }
            assertTrue("${fixture.parentFile.name} lacks ${missing.map { "${it.className}.${it.signature}" }}", missing.isEmpty())
        }
    }

    @Test
    fun everyMemberIsInEveryRecordedBuild() {
        val fixtures = recordedBuilds(LauncherSymbols.PACKAGE)
        assertTrue("No Pixel Launcher build recorded", fixtures.isNotEmpty())
        fixtures.forEach { fixture ->
            val members = methodsByClass(fixture)
            val missing = LauncherSymbols.all.filter { members[it.className]?.contains(it.signature) != true }
            assertTrue("${fixture.parentFile.name} lacks ${missing.map { "${it.className}.${it.signature}" }}", missing.isEmpty())
            val missingFields = LauncherSymbols.fields.filter { members[it.className]?.contains("field ${it.name}") != true }
            assertTrue("${fixture.parentFile.name} lacks ${missingFields.map { "${it.className}.${it.name}" }}", missingFields.isEmpty())
        }
    }

    private fun recordedBuilds(host: String): List<File> {
        val hosts = File(requireNotNull(javaClass.classLoader.getResource("hosts")).toURI())
        return hosts.listFiles().orEmpty().mapNotNull { File(it, "$host.txt").takeIf(File::isFile) }
    }

    private fun methodsByClass(fixture: File): Map<String, Set<String>> {
        val members = mutableMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        fixture.readLines().forEach { line ->
            when {
                line.startsWith("class ") -> current = members.getOrPut(line.removePrefix("class ").trim()) { mutableSetOf() }
                line.startsWith("  method ") -> current?.add(line.removePrefix("  method ").trim())
                line.startsWith("  field ") -> current?.add(line.trim())
            }
        }
        return members
    }
}
