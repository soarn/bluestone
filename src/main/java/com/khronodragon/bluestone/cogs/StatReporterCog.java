package com.khronodragon.bluestone.cogs;

import com.khronodragon.bluestone.Bot;
import com.khronodragon.bluestone.Cog;
import com.khronodragon.bluestone.ShardUtil;
import com.khronodragon.bluestone.annotations.EventHandler;
import com.zanox.lib.simplegraphiteclient.SimpleGraphiteClient;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.khronodragon.bluestone.util.Strings.str;

public class StatReporterCog extends Cog {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Logger logger = LogManager.getLogger(StatReporterCog.class);
    private SimpleGraphiteClient graphiteClient;
    private static final AtomicInteger messagesSinceLastReport = new AtomicInteger();
    private static final AtomicInteger newGuildsSinceLastReport = new AtomicInteger();

    private enum Endpoints {
        DISCORD_BOTS("https://bots.discord.pw/api/bots/{0}/stats"),
        CARBONITEX("https://www.carbonitex.net/discord/data/botdata.php"),
        DISCORD_BOTS_ORG("https://discordbots.org/api/bots/{0}/stats");

        private String endpoint;

        Endpoints(String endpoint) {
            this.endpoint = endpoint;
        }

        String format(Object... args) {
            return MessageFormat.format(endpoint, args);
        }

        String getUrl() {
            return endpoint;
        }
    }

    public StatReporterCog(Bot bot) {
        super(bot);

        if (bot.getShardNum() == 1 && bot.getConfig().has("graphite_host") &&
                bot.getConfig().has("graphite_port")) {
            graphiteClient = new SimpleGraphiteClient(bot.getConfig().getString("graphite_host"),
                    bot.getConfig().getInt("graphite_port"));
            bot.getScheduledExecutor().scheduleAtFixedRate(this::graphiteReport, 2, 15, TimeUnit.SECONDS);
        }
    }

    public String getName() {
        return "Statistic Reporter";
    }

    public String getDescription() {
        return "A cog to report bot stats to services like Discord Bots and Carbonitex.";
    }

    public void load() {
        super.load();
        report();
    }

    private void graphiteReport() {
        try {
            graphiteClient.sendMetrics(new HashMap<String, Number>() {{
                ShardUtil shardUtil = bot.getShardUtil();
                Runtime runtime = Runtime.getRuntime();

                put("bot.guilds", shardUtil.getGuildCount());
                put("bot.channels", shardUtil.getChannelCount());
                put("bot.voice_channels", shardUtil.getVoiceChannelCount());
                put("bot.text_channels", shardUtil.getTextChannelCount());
                put("bot.requests", shardUtil.getRequestCount());
                put("bot.users", shardUtil.getUserCount());
                put("bot.shards", shardUtil.getShardCount());
                put("bot.cpu_usage", UtilityCog.systemBean.getProcessCpuLoad());
                put("bot.cpu_time", UtilityCog.systemBean.getProcessCpuTime());
                put("bot.emotes", shardUtil.getEmoteCount());
                put("bot.music_tracks", shardUtil.getTrackCount());
                put("bot.music_streams", shardUtil.getStreamCount());
                put("bot.messages_per_min", messagesSinceLastReport.getAndSet(0));
                put("bot.guilds_per_min", newGuildsSinceLastReport.getAndSet(0));

                put("system.load_average", UtilityCog.systemBean.getSystemLoadAverage());
                put("system.cpu_usage", UtilityCog.systemBean.getSystemCpuLoad());
                put("system.memory_free", UtilityCog.systemBean.getFreePhysicalMemorySize());
                put("system.memory_used", runtime.totalMemory() - runtime.freeMemory());

                for (Map.Entry<String, AtomicInteger> entry: shardUtil.getCommandCalls().entrySet()) {
                    put("bot.command." + entry.getKey() + ".requests", entry.getValue().get());
                }
            }});
        } catch (Exception e) {
            logger.error("Error reporting stats to Graphite", e);
        }
    }

    @EventHandler
    public void onMessageReceived(MessageReceivedEvent event) {
        messagesSinceLastReport.incrementAndGet();
    }

    @EventHandler
    public void onReady(ReadyEvent event) {
        report();
    }

    @EventHandler
    public void onGuildJoin(GuildJoinEvent event) {
        newGuildsSinceLastReport.incrementAndGet();

        report();
    }

    @EventHandler
    public void onGuildLeave(GuildLeaveEvent event) {
        newGuildsSinceLastReport.decrementAndGet();

        report();
    }

    private void report() {
        String dbotsKey = bot.getKeys().optString("discord_bots", null);
        String carbonitexKey = bot.getKeys().optString("carbonitex", null);
        String dBotListKey = bot.getKeys().optString("discord_bots_org", null);

        if (dbotsKey != null || carbonitexKey != null)
            java.util.logging.Logger.getLogger("org.apache.http.client.protocol.ResponseProcessCookies")
                    .setLevel(Level.OFF);

        if (dbotsKey != null)
            reportDiscordBots(dbotsKey);

        if (carbonitexKey != null)
            reportCarbonitex(carbonitexKey);

        if (dBotListKey != null)
            reportDiscordBotsOrg(dBotListKey);
    }

    private void reportDiscordBots(String key) {
        JSONObject json = new JSONObject();
        if (bot.getJda().getShardInfo() != null) {
            JDA.ShardInfo sInfo = bot.getJda().getShardInfo();

            json.put("shard_id", sInfo.getShardId())
                    .put("shard_count", sInfo.getShardTotal())
                    .put("server_count", bot.getJda().getGuilds().size());
        } else {
            json.put("server_count", bot.getJda().getGuilds().size());
        }

        Bot.http.newCall(new Request.Builder()
                .post(RequestBody.create(JSON_MEDIA_TYPE, json.toString()))
                .url(Endpoints.DISCORD_BOTS.format(bot.getJda().getSelfUser().getId()))
                .header("Authorization", key)
                .build()).enqueue(Bot.callback(response -> {
            if (!response.isSuccessful()) {
                logger.warn("[Discord Bots] Bad response: {} {}", response.code(), response.message());
            }

            response.body().close();
        }, e -> {
            if (e instanceof SocketTimeoutException)
                logger.error("[Discord Bots] Report: timeout");
            else
                logger.error("[Discord Bots] Report failed", e);
        }));
    }

    private void reportCarbonitex(String key) {
        Bot.http.newCall(new Request.Builder()
                .post(new FormBody.Builder()
                        .add("key", key)
                        .add("servercount", str(bot.getShardUtil().getGuildCount()))
                        .build())
                .url(Endpoints.CARBONITEX.getUrl())
                .build()).enqueue(Bot.callback(response -> {
            if (!response.isSuccessful()) {
                logger.warn("[Carbonitex] Bad response: {} {}", response.code(), response.message());
            }

            response.body().close();
        }, e -> {
            if (e instanceof SocketTimeoutException)
                logger.error("[Carbonitex] Report: timeout");
            else
                logger.error("[Carbonitex] Report failed", e);
        }));
    }

    private void reportDiscordBotsOrg(String key) {
        JSONObject json = new JSONObject();
        if (bot.getJda().getShardInfo() != null) {
            JDA.ShardInfo sInfo = bot.getJda().getShardInfo();

            json.put("shard_id", sInfo.getShardId())
                    .put("shard_count", sInfo.getShardTotal())
                    .put("server_count", bot.getJda().getGuilds().size());
        } else {
            json.put("server_count", bot.getJda().getGuilds().size());
        }

        Bot.http.newCall(new Request.Builder()
                .post(RequestBody.create(JSON_MEDIA_TYPE, json.toString()))
                .url(Endpoints.DISCORD_BOTS_ORG.format(bot.getJda().getSelfUser().getId()))
                .header("Authorization", key)
                .build()).enqueue(Bot.callback(response -> {
            if (!response.isSuccessful())  {
                logger.warn("[Discord Bot List] Bad response: {} {}", response.code(), response.message());
            }

            response.body().close();
        }, e -> {
            if (e instanceof SocketTimeoutException)
                logger.error("[Discord Bot List] Report: timeout");
            else
                logger.error("[Discord Bot List] Report failed", e);
        }));
    }
}
