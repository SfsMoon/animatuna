package io.github.sfsmoon.animatuna.host

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Finds [Member]s among the classes of one host's class loader. */
class HostLookup(private val classLoader: ClassLoader) {

    fun method(member: Member): Method {
        require(member.name != Member.CONSTRUCTOR) { "Not a method: ${member.signature}" }
        return find(member) as Method
    }

    fun constructor(member: Member): Constructor<*> {
        require(member.name == Member.CONSTRUCTOR) { "Not a constructor: ${member.signature}" }
        return find(member) as Constructor<*>
    }

    /** The constant [name] of the enum [className]. */
    fun enumConstant(className: String, name: String): Any =
        type(className).enumConstants?.firstOrNull { (it as Enum<*>).name == name } ?: error("No $name in $className")

    fun field(field: HostField): Field = type(field.className).getDeclaredField(field.name).apply { isAccessible = true }

    private fun find(member: Member): Executable {
        val owner = type(member.className)
        val parameters = member.parameters.map(::type).toTypedArray()
        val found: Executable = if (member.name == Member.CONSTRUCTOR) {
            owner.getDeclaredConstructor(*parameters)
        } else {
            owner.getDeclaredMethod(member.name, *parameters)
        }
        found.isAccessible = true
        return found
    }

    private fun type(name: String): Class<*> = PRIMITIVES[name] ?: Class.forName(name, false, classLoader)

    private companion object {
        val PRIMITIVES = mapOf(
            "boolean" to Boolean::class.javaPrimitiveType!!,
            "int" to Int::class.javaPrimitiveType!!,
            "long" to Long::class.javaPrimitiveType!!,
            "float" to Float::class.javaPrimitiveType!!,
        )
    }
}
