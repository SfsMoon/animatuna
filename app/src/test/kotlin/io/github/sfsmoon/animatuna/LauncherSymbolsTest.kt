package io.github.sfsmoon.animatuna

import java.io.File

import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.host.LauncherSymbols

/** Every launcher member this module reaches is there in each recorded Pixel Launcher build. */
class LauncherSymbolsTest {

    @Test
    fun everyMemberIsInEveryRecordedBuild() {
        val fixtures = recordedBuilds()
        assertTrue("No Pixel Launcher build recorded", fixtures.isNotEmpty())
        fixtures.forEach { fixture ->
            val members = methodsByClass(fixture)
            val missing = LauncherSymbols.all.filter { members[it.className]?.contains(it.signature) != true }
            assertTrue("${fixture.parentFile.name} lacks ${missing.map { "${it.className}.${it.signature}" }}", missing.isEmpty())
        }
    }

    private fun recordedBuilds(): List<File> {
        val hosts = File(requireNotNull(javaClass.classLoader.getResource("hosts")).toURI())
        return hosts.listFiles().orEmpty().mapNotNull { File(it, "${LauncherSymbols.PACKAGE}.txt").takeIf(File::isFile) }
    }

    private fun methodsByClass(fixture: File): Map<String, Set<String>> {
        val members = mutableMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        fixture.readLines().forEach { line ->
            when {
                line.startsWith("class ") -> current = members.getOrPut(line.removePrefix("class ").trim()) { mutableSetOf() }
                line.startsWith("  method ") -> current?.add(line.removePrefix("  method ").trim())
            }
        }
        return members
    }
}
