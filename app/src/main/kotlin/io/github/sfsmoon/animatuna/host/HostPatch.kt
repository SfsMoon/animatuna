package io.github.sfsmoon.animatuna.host

/** One change this module makes to a host: Pixel Launcher or SystemUI. */
fun interface HostPatch {

    /** Installs into the host whose members [lookup] finds; throws where one is missing. */
    fun install(lookup: HostLookup)
}
