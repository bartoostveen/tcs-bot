package nl.bartoostveen.tcsbot.command

import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.interactions.commands.option
import dev.minn.jda.ktx.interactions.commands.restrict
import dev.minn.jda.ktx.interactions.commands.slash
import dev.minn.jda.ktx.interactions.components.button
import dev.minn.jda.ktx.interactions.components.getOption
import dev.minn.jda.ktx.messages.Embed
import dev.minn.jda.ktx.messages.MessageCreate
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import nl.bartoostveen.tcsbot.database.*
import nl.bartoostveen.tcsbot.util.adminPermissions
import nl.bartoostveen.tcsbot.util.printException
import nl.bartoostveen.tcsbot.util.unaryPlus
import java.awt.Color

context(list: CommandListUpdateAction)
fun JDA.roleCommands() {
  with(list) {
    slash("addrole", "Add role to role menu") {
      restrict(guild = true, adminPermissions)
      option<Role>("role", "The role to add", required = true)
      option<String>("menu_name", "The identifier of the menu")
      option<String>("description", "The description of the role")
    }

    slash("removerole", "Remove role from role menu") {
      restrict(guild = true, adminPermissions)
      option<Role>("role", "The role to remove", required = true)
      option<String>("menu_name", "The identifier of the menu")
    }

    slash("rolemenu", "Show role menu here") {
      restrict(guild = true, adminPermissions)
      option<String>("menu_name", "The identifier of the menu")
    }

    slash("setteacherrole", "Sets the teacher role") {
      restrict(guild = true, adminPermissions)
      option<Role>("role", "The role", required = true)
    }

    slash("setenrolledrole", "Sets the 'enrolled' role") {
      restrict(guild = true, adminPermissions)
      option<Role>("role", "The role", required = true)
    }
  }

  onCommand("addrole") { event ->
    +event.deferReply(true)

    val menuName = event.getOption<String>("menu_name") ?: "default"
    val role = event.getOption<Role>("role")!!
    val description = event.getOption<String>("description")

    runCatching {
      editRole(event.guild!!.id, role.id, description, menuName)
    }.printException().onFailure { return@onCommand +event.hook.editOriginal("An error occurred") }

    +event.hook.editOriginal(":white_check_mark:")
  }

  onCommand("removerole") { event ->
    +event.deferReply(true)
    +event.hook.editOriginal(":white_check_mark:")

    val menuName = event.getOption<String>("menu_name") ?: "default"
    val role = event.getOption<Role>("role")!!

    runCatching {
      removeRole(event.guild!!.id, role.id, menuName)
    }.printException()
  }

  onCommand("rolemenu") { event ->
    val menuName = (event.getOption<String>("menu_name")?.takeIf { it.isNotBlank() } ?: "default")
      .take(64)
    +event.deferReply(true)
    +event.hook.editOriginal(":white_check_mark:")

    val guild = event.guild!!
    val roles = getRoles(guild.id, menuName).associateBy { guild.getRoleById(it.discordId) }

    +event.messageChannel.sendMessage(MessageCreate {
      embeds += Embed {
        title = "Choose your roles"
        description = "Get them before they are sold out!"
        author(
          name = selfUser.asTag,
          iconUrl = selfUser.effectiveAvatarUrl
        )
        color = Color.BLUE.rgb
        roles.forEach { (role, dbRole) ->
          field("@${role?.name ?: dbRole.description}", dbRole.description ?: EmbedBuilder.ZERO_WIDTH_SPACE)
        }
      }
      actionRow(
        roles.map { (role, dbRole) ->
          button("rolemenu-${menuName}-${dbRole.discordId}", role?.name ?: dbRole.discordId)
        }
      )
    })
  }

  listener<ButtonInteractionEvent> { event ->
    val buttonId = event.button.customId
    if (buttonId?.startsWith("rolemenu-") != true) return@listener
    val arg = buttonId
      .split('-')
      .take(3)
      .takeIf { it.all(String::isNotBlank) } ?: return@listener
    val (_, menuName, roleId) = arg
    val guild = event.guild ?: return@listener
    val member = event.member ?: return@listener

    val dbRole = getRole(guild.id, roleId, menuName) ?: return@listener
    val role = guild.getRoleById(dbRole.discordId) ?: return@listener

    +event.deferReply(true)
    if (role in member.unsortedRoles) +guild.removeRoleFromMember(member, role)
    else +guild.addRoleToMember(member, role)
    +event.hook.editOriginal("Toggled role <@&$roleId>: ${dbRole.description}")
  }

  onCommand("setteacherrole") { event ->
    setRoleCommand(event) { teacherRole = it }
  }

  onCommand("setenrolledrole") { event ->
    setRoleCommand(event) { enrolledRole = it }
  }
}

suspend fun setRoleCommand(
  event: GenericCommandInteractionEvent,
  setRole: Guild.(String) -> Unit
) {
  val role = event.getOption<Role>("role")!!
  +event.deferReply(true)

  runCatching {
    editGuild(event.guild!!.id) {
      setRole(role.id)
    }
  }.printException().onFailure {
    return +event.hook.editOriginal("An error occurred")
  }

  +event.hook.editOriginal(":white_check_mark:")
}
