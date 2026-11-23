package io.github.sfsmoon.animatuna.host

/** One change this module makes to Pixel Launcher. */
fun interface LauncherPatch {

    /** Installs into the launcher whose members [lookup] finds; throws where one is missing. */
    fun install(lookup: HostLookup)
}
