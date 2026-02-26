package nl.bartoostveen.tcsbot.command

import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.onButton
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.interactions.commands.option
import dev.minn.jda.ktx.interactions.commands.restrict
import dev.minn.jda.ktx.interactions.commands.slash
import dev.minn.jda.ktx.interactions.components.*
import dev.minn.jda.ktx.messages.Embed
import dev.minn.jda.ktx.messages.MessageCreate
import dev.minn.jda.ktx.messages.MessageEdit
import dev.minn.jda.ktx.messages.send
import io.ktor.http.encodeURLPath
import io.ktor.util.generateNonce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import nl.bartoostveen.tcsbot.AppConfig
import nl.bartoostveen.tcsbot.canvas.CanvasAPI
import nl.bartoostveen.tcsbot.canvas.CourseUser
import nl.bartoostveen.tcsbot.database.*
import nl.bartoostveen.tcsbot.util.adminPermissions
import nl.bartoostveen.tcsbot.util.printException
import nl.bartoostveen.tcsbot.util.suspendTransaction
import nl.bartoostveen.tcsbot.util.unaryPlus
import java.awt.Color
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

context(list: CommandListUpdateAction)
fun JDA.verifyCommands() {
  with(list) {
    slash("verify", "Show verification dialog") {
      restrict(guild = true, adminPermissions)
    }

    slash("setverifiedrole", "Set the verified role") {
      restrict(guild = true, adminPermissions)

      option<Role>("role", "The role", required = true)
    }

    slash("reloadnickname", "Reload user nickname") {
      restrict(guild = true, adminPermissions)

      option<net.dv8tion.jda.api.entities.Member>("member", "The member to reload", required = true)
    }

    slash("changeuserdata", "Manually verify a member / edit someones data") {
      restrict(guild = true) // restrict only to deployer's Discord ID manually
      option<net.dv8tion.jda.api.entities.Member>("member", "The member to edit", required = true)
      option<String>("name", "Must be non-null if email is non-null")
      option<String>("email", "Must be non-null if name is non-null")
    }

    slash("unlink", "Unlink this Discord account from current UT account")
  }

  onCommand("verify") { event ->
    +event.deferReply(true)
    if (getGuild(event.guild!!.id)?.verifiedRole == null)
      return@onCommand +event.hook.editOriginal("Set the verified role first using /setverifiedrole!")
    +event.hook.editOriginal(":white_check_mark:")

    (event.channel as? TextChannel)?.sendMessage(MessageCreate {
      embeds += Embed(
        title = "Before you can access significant channels, you need to verify yourself.",
        description = """
          You can log in using your UT account by tapping the button below
          This way, we'll update your display name on all module servers you're in, just like in the old official server(s)

          We won't store any information (see Microsoft authentication dialog) except for your email
          If you still have questions or concerns, this bot is entirely open source!
          https://github.com/bartoostveen/tcs-bot (or send a DM to @huizengek ([@bart:bartoostveen.nl on Matrix](https://matrix.to/#/@bart:bartoostveen.nl)) / an email to [tcsbot@bartoostveen.nl](mailto:tcsbot@bartoostveen.nl))
        """.trimIndent(),
        authorIcon = selfUser.effectiveAvatarUrl,
        authorName = selfUser.asTag,
        color = Color.BLUE.rgb
      )
      components += row(
        success("verify", "Link Discord to UT")
      )
    })?.queue()
  }

  onButton("verify") { event ->
    +event.deferReply(true)
    val dbMember = getMember(
      discordId = event.member!!.id,
      guildId = event.guild!!.id,
      fetchGuilds = true
    )
    if (dbMember?.email != null) {
      assignRole(dbMember)
      return@onButton +event.hook.editOriginal("Already verified, applying changes...")
    }

    val nonce = generateNonce()
    runCatching {
      editMember(event.member!!.id, event.guild!!.id) {
        authNonce = nonce
      }
    }.printException().onFailure {
      return@onButton +event.hook.editOriginal("An error occurred")
    }

    val url = "${AppConfig.HOSTNAME}/oauth/redirect?nonce=${nonce.encodeURLPath()}"
    +event.hook.editOriginal("Press this link to authorize your UT account: **$url**")
  }

  onCommand("setverifiedrole") { event ->
    setRoleCommand(event) { verifiedRole = it }
  }

  onCommand("reloadnickname") { event ->
    +event.deferReply(true)
    val member = event.getOption<net.dv8tion.jda.api.entities.Member>("member")!!
    val dbMember = getMember(member.id).takeIf { it?.name != null && it.email != null }
      ?: return@onCommand +event.hook.editOriginal("User not verified yet!")

    assignRole(dbMember)
    +event.hook.editOriginal(":white_check_mark:")
  }

  onCommand("changeuserdata") { event ->
    +event.deferReply(true)

    if (event.member?.id !in AppConfig.DISCORD_DEPLOYER_ID)
      return@onCommand +event.hook.editOriginal("You are not the deployer of this bot!")

    val member = event.getOption<net.dv8tion.jda.api.entities.Member>("member")!!
    val name = event.getOption<String>("name")
      ?.takeUnless { it.equals("null", ignoreCase = true) }
      ?.takeIf { it.isNotBlank() }
    val email = event.getOption<String>("email")
      ?.takeUnless { it.equals("null", ignoreCase = true) }
      ?.takeIf { it.isNotBlank() }

    editMember(member.id, event.guild!!.id) {
      when {
        name == null && email == null -> {
          this.name = null
          this.email = null
        }

        name != null && email != null -> {
          this.authNonce = null
          this.name = name
          this.email = email
        }

        else -> return@editMember +event.hook.editOriginal("Cannot update member if name and email are not both null or non-null!")
      }
      +event.hook.editOriginal("Updated member ${member.asMention} :white_check_mark:")
    }
  }

  onCommand("unlink") { event ->
    +event.deferReply(true)
    +event.hook.editOriginal(MessageEdit {
      embeds += Embed(
        title = "Are you really 100% sure that you want to do this?",
        description = """
          Your permissions will instantly get removed, please be careful with this action! You probably do not want to do this:

          - If your name is incorrect, please ask an administrator to change it
          - You should only need this when wanting to re-link your UT account

          Please note: if for some reason the bot was unable to find an associated account, it'll silently ignore you ever tried to unlink
        """.trimIndent(),
        authorIcon = selfUser.effectiveAvatarUrl,
        authorName = selfUser.asTag,
        color = Color.RED.rgb
      )
      components += row(
        danger("confirm-unlink", "Yes, I really am 100% sure!"),
        secondary("cancel-unlink", "Abort mission")
      )
    })
  }

  onButton("cancel-unlink") { event ->
    +event.hook.editOriginal(MessageEdit(replace = true) {
      content = "Unlinking aborted"
    })
  }

  onButton("confirm-unlink") { event ->
    +event.hook.editOriginal(MessageEdit(replace = true) {
      content = "Performing unlink, this may take some time..."
    })
    removeRoles(event.user)
  }
}

suspend fun updateNickname(
  member: net.dv8tion.jda.api.entities.Member,
  name: String,
  email: String? = null,
  course: String?,
  proxy: String?
): Pair<CourseUser.Enrollment.Role?, AuditableRestAction<Void?>> {
  val name = name.take(min(name.length, 22))

  val highestRole = course?.let {
    runCatching {
      CanvasAPI
        .searchUser(name, email, course, proxy)
        .getOrNull()
        ?.enrollments
        ?.maxOf { it.role }
    }.printException().getOrNull()
  }

  return highestRole to member.modifyNickname(
    when (highestRole) {
      null, CourseUser.Enrollment.Role.Student -> name
      CourseUser.Enrollment.Role.TA -> "$name [TA]"
      CourseUser.Enrollment.Role.Teacher -> "$name [Teacher]"
    }
  )
}

suspend fun JDA.assignRole(
  name: String,
  email: String,
  nonce: String
) = assignRole(
  name = name,
  email = email,
  dbMember = getMemberByNonce(nonce) ?: error("Invalid nonce")
)

@OptIn(ExperimentalTime::class)
suspend fun JDA.assignRole(name: String, email: String, dbMember: Member): Boolean {
  runCatching {
    editMember(dbMember) {
      this.name = name
      this.email = email
      this.authNonce = null
    }

    +retrieveUserById(dbMember.discordId).await().openPrivateChannel().await().send(
      embeds = listOf(
        Embed(
          title = "Successfully retrieved UT Account Info!",
          description = "You just verified as **$name** with $email, welcome to our server(s)!",
          authorName = selfUser.asTag,
          authorUrl = selfUser.effectiveAvatarUrl,
          color = Color.GREEN.rgb,
          timestamp = Clock.System.now().toJavaInstant()
        )
      )
    )
  }.printException().onFailure { return false }

  return assignRole(dbMember)
}

suspend fun JDA.assignRole(dbMember: Member): Boolean = runCatching {
  val name = dbMember.name?.let { it.take(min(it.length, 32)) }
    ?: error("Invalid state: member cannot have null name")

  suspendTransaction {
    val scope = CoroutineScope(currentCoroutineContext())
    dbMember.guilds.map { dbGuild ->
      scope.async {
        runCatching {
          val guild = getGuildById(dbGuild.discordId) ?: error("Guild does not exist anymore")
          val verifiedRole = dbGuild.verifiedRole?.let { guild.getRoleById(it) } ?: error("Role does not exist")
          val teacherRole = dbGuild.teacherRole?.let { guild.getRoleById(it) }
          val enrolledRole = dbGuild.enrolledRole?.let { guild.getRoleById(it) }
          val member = guild.retrieveMemberById(dbMember.discordId).await() ?: error("Member left guild")

          val (canvasRole, action) = updateNickname(
            member = member,
            name = name,
            email = dbMember.email,
            course = dbGuild.primaryCourse?.canvasId,
            proxy = dbGuild.primaryCourse?.proxyUrl
          )
          action.await()

          delay(500L) // Because of the server-side race condition in Discord
          +guild.addRoleToMember(member, verifiedRole)
          if (canvasRole != null) {
            if (canvasRole > CourseUser.Enrollment.Role.Student) teacherRole?.let {
              delay(500)
              +guild.addRoleToMember(member, it)
            }
            enrolledRole?.let {
              delay(500)
              +guild.addRoleToMember(member, it)
            }
          }
        }.printException().isSuccess
      }
    }.awaitAll().none { !it }
  }
}.printException().getOrDefault(false)

suspend fun JDA.removeRoles(member: UserSnowflake) = runCatching {
  getMember(member.id)?.let { removeRoles(it) }
}.printException().let { }

suspend fun JDA.removeRoles(dbMember: Member) = runCatching {
  suspendTransaction {
    editMember(dbMember) {
      this.name = null
      this.email = null
      this.authNonce = null
    }

    dbMember.guilds.forEach { dbGuild ->
      val guild = getGuildById(dbGuild.discordId) ?: error("Guild does not exist anymore")
      val member = guild.retrieveMemberById(dbMember.discordId).await() ?: error("Member left guild")
      dbGuild
        .allRoles
        .filterNotNull()
        .mapNotNull { guild.getRoleById(it) }
        .forEach {
          guild.removeRoleFromMember(member, it).await()
          delay(500)
        }
    }
  }
}.printException().let { }
