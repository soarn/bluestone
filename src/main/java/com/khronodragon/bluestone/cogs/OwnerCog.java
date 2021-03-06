package com.khronodragon.bluestone.cogs;

import com.khronodragon.bluestone.*;
import com.khronodragon.bluestone.annotations.Command;
import com.khronodragon.bluestone.annotations.Cooldown;
import com.khronodragon.bluestone.enums.BucketType;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.requests.RestAction;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.khronodragon.bluestone.util.NullValueWrapper.val;
import static com.khronodragon.bluestone.util.Strings.str;

public class OwnerCog extends Cog {
    private static final Logger logger = LogManager.getLogger(OwnerCog.class);
    private ScriptEngine evalEngine = new ScriptEngineManager().getEngineByName("groovy");

    public OwnerCog(Bot bot) {
        super(bot);
        evalEngine.put("last", null);
        evalEngine.put("bot", bot);
        evalEngine.put("test", "Test right back at ya!");
    }

    public String getName() {
        return "Owner";
    }
    public String getDescription() {
        return "Commands for the bot owner.";
    }

    @Perm.Owner
    @Command(name = "shutdown", desc = "Shutdown the bot.", thread = true)
    public void cmdShutdown(Context ctx) {
        ctx.send(Emotes.getFailure() + " Are you **sure** you want to stop the entire bot? Type `yes` to continue.").complete();
        Message resp = bot.waitForMessage(7000, msg -> msg.getAuthor().getIdLong() == ctx.author.getIdLong() &&
                msg.getChannel().getIdLong() == ctx.channel.getIdLong() &&
                msg.getContentRaw().equalsIgnoreCase("yes"));
        if (resp != null) {
            ctx.jda.getPresence().setStatus(OnlineStatus.INVISIBLE);
            logger.info("Global shutdown requested.");
            if (ctx.jda.getShardInfo() == null) {
                ctx.jda.shutdown();
            } else {
                System.exit(0);
            }
        }
    }

    @Perm.Owner
    @Command(name = "stopshard", desc = "Stop the current shard.", aliases = {"restart"}, thread = true)
    public void cmdStopShard(Context ctx) {
        final Integer n = ctx.rawArgs.length() > 0 ? Integer.valueOf(ctx.rawArgs) : ctx.bot.getShardNum() - 1;
        ctx.send(Emotes.getFailure() + " Are you **sure** you want to stop (restart) shard " + n + "? Type `yes` to continue.").complete();
        Message resp = bot.waitForMessage(7000, msg -> msg.getAuthor().getIdLong() == ctx.author.getIdLong() &&
                msg.getChannel().getIdLong() == ctx.channel.getIdLong() &&
                msg.getContentRaw().equalsIgnoreCase("yes"));
        if (resp != null) {
            logger.info("Shard {} shutting down...", n);
            ctx.bot.getShardUtil().getShard(n).getJda().shutdown();
        }
    }

    @Perm.Owner
    @Command(name = "shardinfo", desc = "Display global shard information.")
    public void cmdShardTree(Context ctx) {
        MessageBuilder result = new MessageBuilder()
                .append("```css\n");

        for (Bot shard: ctx.bot.getShardUtil().getShards()) {
            result.append('[')
                    .append(bot.getShardNum() == shard.getShardNum() ? '*' : ' ')
                    .append("] Shard ")
                    .append(shard.getShardNum() - 1)
                    .append(" | [")
                    .append(shard.getJda().getStatus().name())
                    .append("] | Guilds: ")
                    .append(shard.getJda().getGuilds().size())
                    .append(" | Users: ")
                    .append(shard.getJda().getUsers().size());

            MusicCog musicCog = (MusicCog) shard.cogs.get("Music");
            if (musicCog != null)
                result.append(" | MStreams: ")
                        .append(musicCog.getActiveStreamCount())
                        .append(" | MTracks: ")
                        .append(musicCog.getTracksLoaded());

            result.append(" | WSPing: ")
                    .append(shard.getJda().getPing())
                    .append('\n');
        }
        result.append("\nTotal: ")
                .append(bot.getShardTotal())
                .append(" shard(s)```");

        if (result.length() > 2000) {
            for (int i = 0; i < result.length(); i += 1999) {
                result.getStringBuilder().insert(i - 3, "``````css\n");
            }
        }

        for (Message msg: result.buildAll(MessageBuilder.SplitPolicy.ANYWHERE)) {
            ctx.send(msg).queue();
        }
    }

    @Perm.Owner
    @Cooldown(scope = BucketType.GLOBAL, delay = 10)
    @Command(name = "broadcast", desc = "Broadcast a message to all available guilds.",
            usage = "[message]", reportErrors = false)
    public void cmdBroadcast(Context ctx) {
        if (ctx.rawArgs.length() < 1) {
            ctx.fail("I need a message to broadcast!");
            return;
        }

        String ss = "";
        if (ctx.jda.getShardInfo() != null) {
            ss = ctx.jda.getShardInfo().getShardString() + ' ';

            if (!ctx._flag) {
                ctx._flag = true;

                Collection<Bot> shards = bot.getShardUtil().getShards();
                shards.remove(ctx.bot);

                for (Bot b: shards) {
                    if (b.cogs.containsKey("Owner")) {
                        ctx.bot = b;
                        ctx.jda = b.getJda();
                        ((OwnerCog) b.cogs.get("Owner")).cmdBroadcast(ctx);
                    }
                }
            }
        }
        ctx.jda = bot.getJda();
        ctx.bot = bot;

        ctx.send(ss + "Starting broadcast...").queue();
        int errors = 0;

        for (Guild guild: ctx.jda.getGuilds()) {
            if (!guild.isAvailable()) {
                ctx.send(Emotes.getFailure() + " Guild **" + val(guild.getName()).or("[unknown]") +
                        "** (`" + val(guild.getIdLong()).or(0L) + "`) unavailable.").queue();
                errors++;
                continue;
            }
            String message = StringUtils.replace(ctx.rawArgs, "%prefix%",
                    bot.prefixStore.getPrefix(guild.getIdLong()));
            TextChannel channel = defaultWritableChannel(guild.getSelfMember());

            if (channel != null && channel.canTalk())
                channel.sendMessage(message).queue();
            else {
                errors++;
            }
        }

        ctx.success("Broadcast finished, with **" + errors + "** guilds all muted.");
    }

    @Perm.Owner
    @Command(name = "eval", desc = "Evaluate some Groovy code.", usage = "[code]",
            aliases = {"reval"}, thread = true, reportErrors = false)
    public void cmdEval(Context ctx) {
        if (ctx.rawArgs.length() < 1) {
            ctx.fail("I need some code!");
            return;
        }

        evalEngine.put("ctx", ctx);
        evalEngine.put("event", ctx.event);
        evalEngine.put("jda", ctx.jda);
        evalEngine.put("message", ctx.message);
        evalEngine.put("author", ctx.author);
        evalEngine.put("channel", ctx.channel);
        evalEngine.put("guild", ctx.guild);
        evalEngine.put("msg", ctx.message);

        Object result;
        try {
            result = evalEngine.eval(ReplCog.GROOVY_PRE_INJECT + ReplCog.cleanUpCode(ctx.rawArgs));
        } catch (ScriptException e) {
            result = e.getCause();
            if (result instanceof ScriptException) {
                result = ((ScriptException) result).getCause();
            }
        }
        if (result instanceof RestAction)
            result = ((RestAction) result).complete();
        evalEngine.put("last", result);

        if (result == null)
            ctx.message.addReaction("✅").queue();
        else
            ctx.send("```java\n" + result.toString() + "```").queue();
    }

    @Perm.Owner
    @Command(name = "sendfile", desc = "Upload a local file here.",
            usage = "[file path]", reportErrors = false)
    public void cmdSendfile(Context ctx) throws IOException {
        if (ctx.rawArgs.length() < 1) {
            ctx.send("🤔 I need a file path!").queue();
            return;
        }

        ctx.channel.sendFile(new File(ctx.rawArgs), new MessageBuilder().append("📧 File incoming!")
                .build()).queue();
    }

    @Perm.Owner
    @Command(name = "command_calls", desc = "Get the top 25 command calls.",
            hidden = true, aliases = {"ccalls"})
    public void cmdCmdCalls(Context ctx) {
        EmbedBuilder emb = new EmbedBuilder()
                .setColor(randomColor())
                .setAuthor("Command Calls", null, ctx.jda.getSelfUser().getEffectiveAvatarUrl())
                .setDescription("Here are the top 25 command calls, sorted by amount.")
                .setTimestamp(Instant.now())
                .addField("Total", str(bot.getShardUtil().getRequestCount()), true);

        bot.getShardUtil().getCommandCalls().entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Collections.reverseOrder(Comparator.comparingInt(AtomicInteger::get))))
                .limit(24)
                .forEach(entry -> {
                    emb.addField(entry.getKey(), str(entry.getValue().get()), true);
                });

        ctx.send(emb.build()).queue();
    }

    @Perm.Owner
    @Command(name = "setavatar", desc = "Change my avatar.", aliases = {"set_avatar"})
    public void cmdSetAvatar(Context ctx) throws IOException {
        if (ctx.rawArgs.length() < 1) {
            ctx.fail("I need a file path!");
            return;
        }

        ctx.jda.getSelfUser().getManager().setAvatar(Icon.from(new File(ctx.rawArgs))).queue();
        ctx.success("Avatar changed.");
    }

    @Perm.Owner
    @Command(name = "setgame", desc = "Set my game.", aliases = {"set_game"})
    public void cmdSetGame(Context ctx) {
        if (ctx.rawArgs.length() < 1) {
            ctx.fail("I need a game to set!");
            return;
        }

        bot.getShardUtil().getShards().forEach(b -> b.getJda().getPresence().setGame(Game.playing(ctx.rawArgs)));
        ctx.success("Game set.");
    }

    @Perm.Owner
    @Command(name = "patreload", desc = "Reload the Patreon supporter list.",
            hidden = true, aliases = {"preload"}, thread = true)
    public void cmdPatReload(Context ctx) {
        boolean success = Bot.loadPatreonData();
        if (success) {
            ctx.success("List reloaded.");
        } else {
            ctx.fail("Failed to load list.");
        }
    }
}
