package com.khronodragon.bluestone;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.re2j.Pattern;
import com.j256.ormlite.dao.Dao;
import com.jagrosh.jdautilities.waiter.EventWaiter;
import com.khronodragon.bluestone.annotations.*;
import com.khronodragon.bluestone.errors.PassException;
import com.khronodragon.bluestone.handlers.MessageWaitEventListener;
import com.khronodragon.bluestone.handlers.RejectedExecHandlerImpl;
import com.khronodragon.bluestone.sql.BotAdmin;
import com.khronodragon.bluestone.util.ArrayListView;
import com.khronodragon.bluestone.util.ClassUtilities;
import com.khronodragon.bluestone.util.RandomSelect;
import com.khronodragon.bluestone.util.Strings;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import net.dv8tion.jda.bot.entities.ApplicationInfo;
import net.dv8tion.jda.core.*;
import net.dv8tion.jda.core.JDA.ShardInfo;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.*;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.reflections.Reflections;
import sun.misc.Unsafe;

import javax.security.auth.login.LoginException;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.khronodragon.bluestone.util.NullValueWrapper.val;
import static com.khronodragon.bluestone.util.Strings.str;
import static com.khronodragon.bluestone.util.Strings.format;
import static net.dv8tion.jda.core.entities.Game.playing;
import static net.dv8tion.jda.core.entities.Game.listening;
import static net.dv8tion.jda.core.entities.Game.streaming;
import static net.dv8tion.jda.core.entities.Game.watching;

public class Bot extends ListenerAdapter implements ClassUtilities {
    private static final Logger defLog = LogManager.getLogger(Bot.class);
    public static final String NAME = "Goldmine";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    public static final JSONObject EMPTY_JSON_OBJECT = new JSONObject();
    public static final JSONArray EMPTY_JSON_ARRAY = new JSONArray();
    private static final Pattern GENERAL_MENTION_PATTERN = Pattern.compile("^<@[!&]?[0-9]{17,20}>\\s*");
    public Logger logger = defLog;
    private final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(6, new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("Bot BG-Task Thread %d")
            .build());
    private final ThreadPoolExecutor cogEventExecutor = new ThreadPoolExecutor(4, 32, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("Bot Cog-Event Pool Thread %d")
            .build(), new RejectedExecHandlerImpl("Cog-Event"));
    public final ThreadPoolExecutor threadExecutor = new ThreadPoolExecutor(4, 85, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(72), new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("Bot Command-Exec Pool Thread %d")
            .build(), new RejectedExecHandlerImpl("Command-Exec"));
    private final EventWaiter eventWaiter = new EventWaiter();
    private static Unsafe unsafe = null;
    private JDA jda;
    private final ShardUtil shardUtil;
    public static JSONObject patreonData = new JSONObject();
    public static TLongSet patronIds = new TLongHashSet();
    private final Set<ScheduledFuture> tasks = new HashSet<>();
    public final Map<String, Command> commands = new HashMap<>();
    public final Map<String, Cog> cogs = new HashMap<>();
    private final Map<Class<? extends Event>, List<ExtraEvent>> extraEvents = new HashMap<>();
    public static final OkHttpClient http = new OkHttpClient.Builder()
            .cache(new Cache(new File("data/http_cache"), 24000000000L))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    public User owner;
    public final PrefixStore prefixStore;

    static {
        ensureUnsafe();
    }

    public Dao<BotAdmin, Long> getAdminDao() {
        return shardUtil.getAdminDao();
    }

    public JSONObject getConfig() {
        return shardUtil.getConfig();
    }

    public JSONObject getKeys() {
        return getConfig().getJSONObject("keys");
    }

    public EventWaiter getEventWaiter() {
        return eventWaiter;
    }

    public Bot(ShardUtil util) {
        super();

        shardUtil = util;
        prefixStore = new PrefixStore(shardUtil.getPool(), shardUtil.getConfig().optString("default_prefix", "!"));
        scheduledExecutor.setMaximumPoolSize(6);
        scheduledExecutor.setKeepAliveTime(16L, TimeUnit.SECONDS);
    }

    public void setJda(JDA jda) {
        this.jda = jda;
        jda.addEventListener(eventWaiter);
        final ShardInfo sInfo = jda.getShardInfo();

        if (sInfo != null) {
            logger = LogManager.getLogger("Bot [" + sInfo.getShardString() + ']');
        }
    }

    public JDA getJda() {
        return jda;
    }

    public ScheduledThreadPoolExecutor getScheduledExecutor() {
        return scheduledExecutor;
    }

    public ShardUtil getShardUtil() {
        return shardUtil;
    }

    public int getShardNum() {
        ShardInfo sInfo = jda.getShardInfo();
        if (sInfo == null) {
            return 1;
        } else {
            return sInfo.getShardId() + 1;
        }
    }

    public int getShardTotal() {
        ShardInfo sInfo = jda.getShardInfo();
        if (sInfo == null) {
            return 1;
        } else {
            return sInfo.getShardTotal();
        }
    }

    protected static StringBuilder addVagueElement(StringBuilder builder, StackTraceElement elem) {
        return builder.append("> ")
                .append(StringUtils.replaceOnce(StringUtils.replaceOnce(elem.getClassName(),
                        "java.base/java.util", "stdlib"),
                        "com.khronodragon.bluestone", "bot"))
                .append('.')
                .append(elem.getMethodName())
                .append(elem.isNativeMethod() ? "(native)" : "(" + elem.getLineNumber() + ")");
    }

    public static String vagueTrace(Throwable e) {
        StackTraceElement[] elements = e.getStackTrace();
        StackTraceElement[] limitedElems = {elements[0], elements[1]};
        StringBuilder stack = new StringBuilder(e.getClass().getSimpleName())
                .append(": ")
                .append(e.getMessage());

        for (StackTraceElement elem: limitedElems) {
            stack.append("\n\u2007\u2007");
            addVagueElement(stack, elem);
        }

        if (stack.indexOf("> bot.cogs") == -1) {
            for (StackTraceElement elem: elements) {
                if (elem.getClassName().startsWith("com.khronodragon.bluestone.cogs.")) {
                    stack.append("\n\n\u2007\u2007");
                    addVagueElement(stack, elem);
                    break;
                }
            }
        }

        return stack.toString();
    }

    public static String renderStackTrace(Throwable e) {
        return renderStackTrace(e, "    ", "at ");
    }

    public static String renderStackTrace(Throwable e, String joinSpaces, String elemPrefix) {
        StackTraceElement[] elements = e.getStackTrace();
        StringBuilder stack = new StringBuilder(e.getClass().getSimpleName())
                .append(": ")
                .append(e.getMessage());

        for (StackTraceElement elem: elements) {
            stack.append('\n')
                    .append(joinSpaces)
                    .append(elemPrefix)
                    .append(elem);
        }

        return stack.toString();
    }

    @Override
    public void onGenericEvent(Event event) {
        for (Map.Entry<Class<? extends Event>, List<ExtraEvent>> entry: extraEvents.entrySet()) {
            Class<? extends Event> eventClass = entry.getKey();

            if (eventClass.isInstance(event)) {
                List<ExtraEvent> events = entry.getValue();

                for (ExtraEvent extraEvent: events) {
                    Runnable task = () -> {
                        try {
                            extraEvent.getMethod().invoke(extraEvent.getParent(), event);
                        } catch (IllegalAccessException e) {
                            logger.error("Error dispatching {} to {} - handler not public",
                                    event.getClass().getSimpleName(),
                                    extraEvent.getMethod().getDeclaringClass().getName(), e);
                        } catch (InvocationTargetException eContainer) {
                            Throwable e = eContainer.getCause();

                            logger.error("{} errored while handling a {}",
                                    extraEvent.getMethod().getDeclaringClass().getName(),
                                    event.getClass().getSimpleName(), e);
                        }
                    };

                    if (extraEvent.isThreaded())
                        cogEventExecutor.execute(task);
                    else
                        task.run();
                }
            }
        }
    }

    private void updateOwner() {
        if (jda.getSelfUser().isBot()) {
            ApplicationInfo appInfo = jda.asBot().getApplicationInfo().complete();
            owner = appInfo.getOwner();
        } else {
            owner = jda.getSelfUser();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        jda.getPresence().setStatus(OnlineStatus.ONLINE);
        long uid = jda.getSelfUser().getIdLong();

        updateOwner();
        logger.info("Ready - ID {}", uid);

        if (jda.getGuildById(110373943822540800L) != null)
            Emotes.setHasDbots(true);

        if (jda.getGuildById(250780048943087618L) != null)
            Emotes.setHasParadise(true);

        Runnable task = () -> {
            Game status = new RandomSelect<Game>(50)
                    .add(() -> playing(format("with {0} users", shardUtil.getUserCount())))
                    .add(() -> playing(format("in {0} channels", shardUtil.getChannelCount())))
                    .add(() -> playing(format("in {0} servers", shardUtil.getGuildCount())))
                    .add(() -> playing(format("in {0} guilds", shardUtil.getGuildCount())))
                    .add(() -> playing(format("from shard {0} of {1}", getShardNum(), getShardTotal())))
                    .add(playing("with my buddies"))
                    .add(playing("with bits and bytes"))
                    .add(playing("World Domination"))
                    .add(playing("with you"))
                    .add(playing("with potatoes"))
                    .add(playing("something"))
                    .add(streaming("data", ""))
                    .add(streaming("music", "https://www.youtube.com/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ"))
                    .add(streaming("your tunes", "https://www.youtube.com/watch?v=zQJh0MWvccs"))
                    .add(listening("you"))
                    .add(watching("darkness"))
                    .add(watching("streams"))
                    .add(streaming("your face", "https://www.youtube.com/watch?v=IUjZtoCrpyA"))
                    .add(listening("alone"))
                    .add(streaming("Alone", "https://www.youtube.com/watch?v=YnwsMEabmSo"))
                    .add(streaming("bits and bytes", "https://www.youtube.com/watch?v=N3ZMvqISfvY"))
                    .add(listening("Rick Astley"))
                    .add(streaming("only the very best", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                    .add(listening("those potatoes"))
                    .add(playing("with my fellow shards"))
                    .add(listening("the cries of my shards"))
                    .add(listening("as the sun goes down"))
                    .add(streaming("Monstercat", "https://www.twitch.tv/monstercat"))
                    .add(watching("dem videos"))
                    .add(watching("you in your sleep"))
                    .add(watching("over you as I sleep"))
                    .add(watching("the movement of electrons"))
                    .add(playing("with some protons"))
                    .add(listening("the poor electrons"))
                    .add(listening("the poor neutrons"))
                    .add(listening("trigger-happy players"))
                    .add(playing("Discord Hacker v43.0"))
                    .add(listening("Discordians"))
                    .add(streaming("donations", "https://patreon.com/kdragon"))
                    .add(streaming("You should totally donate!", "https://patreon.com/kdragon"))
                    .add(listening("my people"))
                    .add(listening("my favorites"))
                    .add(watching("my minions"))
                    .add(watching("the chosen ones"))
                    .add(watching("stars combust"))
                    .add(watching("your demise"))
                    .add(streaming("the supernova", "https://www.youtube.com/watch?v=5WXyCJ1w3Ks"))
                    .add(watching("aliens die"))
                    .add(listening("something"))
                    .add(streaming("something", "https://www.youtube.com/watch?v=FM7MFYoylVs"))
                    .add(watching("I am Cow"))
                    .add(watching("you play"))
                    .add(watching("for raids"))
                    .add(playing("buffing before the raid"))
                    .add(streaming("this sick action", "https://www.youtube.com/watch?v=tD6KJ7QtQH8"))
                    .add(listening("memes"))
                    .add(watching("memes"))
                    .add(playing("memes")) // memes
                    .add(watching("that dank vid"))
                    .select();

            jda.getPresence().setGame(status);
        };

        ScheduledFuture future = scheduledExecutor.scheduleAtFixedRate(task, 10, 120, TimeUnit.SECONDS);
        tasks.add(future);

        Reflections reflector = new Reflections("com.khronodragon.bluestone.cogs");
        Set<Class<? extends Cog>> cogClasses = reflector.getSubTypesOf(Cog.class);
        for (Class<?> cogClass: cogClasses) {
            if (cogClass.isAnnotationPresent(DoNotAutoload.class))
                continue;

            try {
                Cog cog = (Cog) cogClass.getConstructor(Bot.class).newInstance(this);
                registerCog(cog);
                cog.load();
            } catch (Throwable e) {
                logger.error("Failed to register cog {}", cogClass.getName(), e);
            }
        }
    }

    public void registerCog(Cog cog) {
        Class<? extends Cog> clazz = cog.getClass();

        for (Method method: clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(com.khronodragon.bluestone.annotations.Command.class)) {
                com.khronodragon.bluestone.annotations.Command anno = method.getDeclaredAnnotation(com.khronodragon.bluestone.annotations.Command.class);

                List<Permission> perms = new ArrayList<>(method.getDeclaredAnnotations().length - 1);
                for (Annotation a: method.getDeclaredAnnotations()) {
                    Class<? extends Annotation> type = a.annotationType();
                    if (type == com.khronodragon.bluestone.annotations.Command.class)
                        continue;

                    if (type == Perm.Owner.class) {
                        perms.add(Permissions.BOT_OWNER);
                    } else if (type == Perm.Admin.class) {
                        perms.add(Permissions.BOT_ADMIN);
                    } else if (type == Perm.Patron.class) {
                        perms.add(Permissions.PATREON_SUPPORTER);
                    } /*else if (type == Perm.All.class) {
                        try {
                            perms.add(((Permission[]) type.getDeclaredMethod("value").invoke(a))[0]);
                                    // need to add AND + multi
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (type == Perm.PermAnds.class) {
                        try {
                            Perm.All[] alls = (Perm.All[]) type.getDeclaredMethod("value").invoke(a);

                            for (Perm.All all: alls) {
                                perms.add(all.value()[0]); // need to add AND + multi
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }*/
                    else {
                        Method valueMethod;
                        try {
                            valueMethod = type.getDeclaredMethod("value");
                        } catch (NoSuchMethodException ignored) {
                            continue;
                        }

                        Permission perm;

                        if (valueMethod.getReturnType() == Permission.class) {
                            try {
                                perm = (Permission) valueMethod.invoke(a);
                            } catch (ReflectiveOperationException e) {
                                throw new RuntimeException(e);
                            }
                        } else if (valueMethod.getReturnType() == Permission[].class) {
                            Permission[] permA;
                            try {
                                permA = (Permission[]) valueMethod.invoke(a);
                            } catch (ReflectiveOperationException e) {
                                throw new RuntimeException(e);
                            }

                            boolean isGuild = false;
                            boolean isChannel = false;
                            long finalRaw = 0;
                            StringBuilder joinedName = new StringBuilder();

                            for (Permission p: permA) {
                                if (p.isGuild()) isGuild = true;
                                if (p.isChannel()) isChannel = true;

                                if (p != Permission.UNKNOWN)
                                    finalRaw |= p.getRawValue();

                                joinedName.append(p.getName())
                                        .append(" & ");
                            }

                            String jn = joinedName.toString();
                            perm = Permissions.createPerm(58, isGuild, isChannel,
                                    jn.substring(0, jn.length() - 3));
                            Permissions.setRaw(perm, finalRaw);

                            Permissions.compoundMap.put(perm, permA);
                        } else {
                            continue;
                        }

                        perms.add(perm);
                    }
                }

                Command command = new Command(
                        anno.name(), anno.desc(), anno.usage(), anno.hidden(),
                        perms.toArray(new Permission[0]), anno.guildOnly(), anno.aliases(), method, cog,
                        anno.thread(), anno.reportErrors()
                );

                if (commands.containsKey(command.name))
                    throw new IllegalStateException("Command '" + command.name + "' already registered!");
                else
                    commands.put(command.name, command);

                for (String al: command.aliases) {
                    if (commands.containsKey(al))
                        throw new IllegalStateException("Command '" + al + "' already registered!");
                    else
                        commands.put(al, command);
                }
            } else if (method.isAnnotationPresent(EventHandler.class)) {
                EventHandler anno = method.getAnnotation(EventHandler.class);
                ExtraEvent extraEvent = new ExtraEvent(method, anno.threaded(), cog);
                Class eventClass = method.getParameterTypes()[0];

                if (extraEvents.containsKey(eventClass)) {
                    extraEvents.get(eventClass).add(extraEvent);
                } else {
                    List<ExtraEvent> list = new ArrayList<>();
                    list.add(extraEvent);

                    extraEvents.put(eventClass, list);
                }
            }
        }

        cogs.put(cog.getName(), cog);
    }

    public void unregisterCog(Cog cog) {
        for (Map.Entry<String, Command> entry: new HashSet<>(commands.entrySet())) {
            Command cmd = entry.getValue();

            if (cmd.cog == cog) {
                commands.remove(entry.getKey());
            }
        }

        cog.unload();
        cogs.remove(cog.getName(), cog);

        for (List<ExtraEvent> events: extraEvents.values()) {
            for (ExtraEvent event: new HashSet<>(events)) {
                if (event.getMethod().getDeclaringClass().equals(cog.getClass())) {
                    events.remove(event);
                }
            }
        }
    }

    @Override
    public void onResume(ResumedEvent event) {
        logger.info("WebSocket resumed.");
        updateOwner();
    }

    @Override
    public void onReconnect(ReconnectedEvent event) {
        logger.info("Reconnected.");
        updateOwner();
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        logger.info("Shutting down...");
        synchronized (this) {
            notifyAll();
        }
    }

    @Override
    public void onException(ExceptionEvent event) {
        if (event.getCause() instanceof OutOfMemoryError) {
            logger.fatal("OUT OF MEMORY! Exiting.");
            Runtime.getRuntime().halt(2);
        }

        logger.error("Error", event.getCause());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) { // TODO: more optimization
        final JDA jda = event.getJDA();
        final User author = event.getAuthor();

        if (author.isBot() || author.getIdLong() == jda.getSelfUser().getIdLong())
            return;

        final Message message = event.getMessage();
        final String prefix;
        if (message.getGuild() == null) {
            prefix = prefixStore.defaultPrefix;
        } else {
            prefix = prefixStore.getPrefix(message.getGuild().getIdLong());
        }
        final String content = message.getContentRaw();
        final MessageChannel channel = event.getChannel();

        if (content.startsWith(prefix)) {
            String[] split = content.substring(prefix.length()).split("\\s+"); // TODO: inefficient, compiling regex!
            ArrayListView(split);

            String cmdName = split[0].toLowerCase();

            if (commands.containsKey(cmdName)) {
                Command command = commands.get(cmdName);

                command.simpleInvoke(this, event, args, prefix, cmdName);

                try {
                    shardUtil.getCommandCalls().get(command.name).incrementAndGet();
                } catch (NullPointerException ignored) {
                    shardUtil.getCommandCalls().put(command.name, new AtomicInteger(1));
                }
            }
        } else if (message.isMentioned(jda.getSelfUser())) {
            String mention = message.getGuild() == null ?
                    jda.getSelfUser().getAsMention() : message.getGuild().getSelfMember().getAsMention();

            if (content.startsWith(mention) || content.startsWith("<@&")) {
                String request = Strings.renderMessage(message, message.getGuild(),
                        GENERAL_MENTION_PATTERN.matcher(message.getContentRaw()).replaceFirst(""));

                if (request.equalsIgnoreCase("prefix")) {
                    channel.sendMessage("My prefix here is `" + prefix + "`.").queue();
                } else if (request.length() > 0) {
                    chatengineResponse(channel, "bs_GMdbot2-" + author.getId(), request, null);
                } else {
                    String tag = Cog.getTag(jda.getSelfUser());

                    channel.sendMessage("Hey there! You can talk to me like `@" + tag +
                            " [message]`. And if you want my prefix, say `@" + tag +
                            " prefix`!\nCurrent prefix: `" + prefix +
                            "` \u2022 Help command: `" + prefix + "help`").queue();
                }
            }
        } else if (channel instanceof PrivateChannel &&
                !(author.getIdLong() == owner.getIdLong() && message.getContentRaw().charAt(0) == '`')) {
            String request = Strings.renderMessage(message, null, message.getContentRaw());

            if (request.length() < 1) {
                channel.sendMessage("**Hey there**! My prefix is `" + prefix + "` here.\nYou can use commands, or talk to me directly.").queue();
            } else {
                chatengineResponse(channel, "bs_GMdbot2-" + author.getId(), request, "💬 ");
            }
        }
    }

    public void chatengineResponse(MessageChannel channel, String sessionID, String query, String respPrefix) {
        String reqDest = getConfig().optString("chatengine_url", null);
        if (reqDest == null) {
            channel.sendMessage("My owner hasn't set up ChatEngine yet.").queue();
            return;
        }
        channel.sendTyping().queue();

        http.newCall(new Request.Builder()
                .post(RequestBody.create(JSON_MEDIA_TYPE, new JSONObject()
                        .put("session", sessionID)
                        .put("query", query)
                        .toString()))
                .url(reqDest)
                .header("Authorization", getKeys().optString("chatengine"))
                .build()).enqueue(Bot.callback(response -> {
            JSONObject resp = new JSONObject(response.body().string());

            if (!resp.optBoolean("success", false)) {
                logger.error("ChatEngine returned error: {}", resp.optString("error", "Not specified"));
                channel.sendMessage(Emotes.getFailure() + " An error occurred getting a response!").queue();
                return;
            }

            String toSend;
            if (respPrefix == null)
                toSend = resp.getString("response");
            else
                toSend = respPrefix + resp.getString("response");

            channel.sendMessage(toSend).queue();
        }, e -> {
            logger.error("Error getting ChatEngine response", e);
            channel.sendMessage(Emotes.getFailure() + " My brain isn't really working right now.").queue();
        }));
    }

    public void reportErrorToOwner(Throwable e, Message msg, Command cmd) {
        if (jda.getGuilds().size() < 100) return;

        owner.openPrivateChannel().queue(ch -> {
            ch.sendMessage(errorEmbed(e, msg, cmd)).queue();
        });
    }

    public static String briefSqlError(SQLException e) {
        String result = e.getMessage();
        if (e.getCause() != null) {
            result = result + "\n\u2007\u2007> " + e.getCause().getMessage();
        }

        return result;
    }

    public static Unsafe getUnsafe() {
        if (unsafe == null) {
            ensureUnsafe();
            return unsafe;
        }

        return unsafe;
    }

    private MessageEmbed errorEmbed(Throwable e, Message msg, Command cmd) {
        String stack = renderStackTrace(e, "\u3000", "> ");

        EmbedBuilder emb = new EmbedBuilder()
                .setAuthor(Cog.getTag(msg.getAuthor()), null, msg.getAuthor().getEffectiveAvatarUrl())
                .setTitle("Error in command `" + cmd.name + '`')
                .setColor(Color.ORANGE)
                .appendDescription("```java\n")
                .appendDescription(stack.substring(0, Math.min(stack.length(), 2037)))
                .appendDescription("```");

        if (e.getCause() != null) {
            String causeStack = renderStackTrace(e, "\u3000", "> ");
            emb.addField("Caused by", "```java\n" +
                    causeStack.substring(0, Math.min(causeStack.length(), 1013)) + "```", false);
        }

        emb.addField("Timestamp", System.currentTimeMillis() + "ms", true)
                .addField("Author ID", msg.getAuthor().getId(), true)
                .addField("Message ID", msg.getId(), true)
                .addField("Attachments", str(msg.getAttachments().size()), true)
                .addField("Guild", msg.getGuild() == null ? "None" : msg.getGuild().getName(), true)
                .addField("Guild ID", msg.getGuild() == null ? "None (no guild)" : msg.getGuild().getId(), true)
                .addField("Channel", msg.getChannel().getName(), true)
                .addField("Channel ID", msg.getChannel().getId(), true)
                .addField("Content", '`' + msg.getContentDisplay() + '`', true)
                .addField("Embeds", str(msg.getEmbeds().size()), true)
                .addField("Shard ID", str(getShardNum() - 1), true)
                .setTimestamp(Instant.now());

        if (msg.getGuild() != null)
            emb.setFooter("Guild Icon", msg.getGuild().getIconUrl());

        return emb.build();
    }

    public Message waitForMessage(long millis, Predicate<Message> check) {
        AtomicReference<Message> lock = new AtomicReference<>();
        MessageWaitEventListener listener = new MessageWaitEventListener(lock, check);
        jda.addEventListener(listener);

        synchronized (lock) {
            try {
                lock.wait(millis);
            } catch (InterruptedException e) {
                jda.removeEventListener(listener);
                return null;
            }
            return lock.get();
        }
    }

    public boolean isSelfbot() {
        return !jda.getSelfUser().isBot();
    }

    public boolean isBot() {
        return jda.getSelfUser().isBot();
    }

    private long getUptimeMillis() {
        return new Date().getTime() - shardUtil.startTime.getTime();
    }

    public String formatUptime() {
        return formatDuration(getUptimeMillis() / 1000L);
    }

    public static String formatMemory() {
        Runtime runtime = Runtime.getRuntime();
        NumberFormat format = NumberFormat.getInstance();
        return format.format((runtime.totalMemory() - runtime.freeMemory()) / 1048576.0f) + " MB";
    }

    public static String formatDuration(long duration) {
        if (duration == 9223372036854775L) { // Long.MAX_VALUE / 1000L
            return "[unknown]";
        }

        long h = duration / 3600;
        long m = (duration % 3600) / 60;
        long s = duration % 60;
        long d = h / 24;
        h = h % 24;
        String sd = (d > 0 ? String.valueOf(d) + " day" + (d == 1 ? "" : "s") : "");
        String sh = (h > 0 ? String.valueOf(h) + " hr" : "");
        String sm = (m < 10 && m > 0 && h > 0 ? "0" : "") + (m > 0 ? (h > 0 && s == 0 ? String.valueOf(m) : String.valueOf(m) + " min") : "");
        String ss = (s == 0 && (h > 0 || m > 0) ? "" : (s < 10 && (h > 0 || m > 0) ? "0" : "") + String.valueOf(s) + " sec");
        return sd + (d > 0 ? " " : "") + sh + (h > 0 ? " " : "") + sm + (m > 0 ? " " : "") + ss;
    }

    public static boolean loadPatreonData() {
        String jsonCode;
        try {
            jsonCode = new String(Files.readAllBytes(Paths.get("patreon.json")));
        } catch (IOException e) {
            defLog.error("Failed to load Patreon data", e);
            return false;
        }

        patreonData = new JSONObject(jsonCode);

        TLongSet ids = new TLongHashSet();
        for (Object iter: patreonData.getJSONArray("user_ids")) {
            ids.add(iter instanceof Long ? (Long) iter : Long.parseUnsignedLong((String) iter));
        }
        patronIds = ids;

        return true;
    }

    public static int start(String token, int shardCount, AccountType accountType, JSONObject config) throws LoginException, RateLimitedException {
        System.out.println("Starting...");

        if (shardCount < 1) {
            System.out.println("There needs to be at least 1 shard, or how will the bot work?");
            return 1;
        } else if (shardCount == 2) {
            System.out.println("2 shards is very buggy and doesn't work well. Use either 1 or 3+ shards.");
            return 1;
        }

        ShardUtil shardUtil = new ShardUtil(shardCount, config);
        JDABuilder builder = new JDABuilder(accountType)
                .setToken(token)
                .setAudioEnabled(true)
                .setAutoReconnect(true)
                .setWebsocketFactory(new WebSocketFactory()
                        .setConnectionTimeout(120000))
                .setBulkDeleteSplittingEnabled(false)
                .setStatus(OnlineStatus.IDLE)
                .setCorePoolSize(5)
                .setEnableShutdownHook(true)
                .setHttpClientBuilder(new OkHttpClient.Builder()
                        .retryOnConnectionFailure(true))
                .setGame(Game.playing("something"));

        if ((System.getProperty("os.arch").startsWith("x86") ||
                System.getProperty("os.arch").equals("amd64")) &&
                (SystemUtils.IS_OS_WINDOWS || SystemUtils.IS_OS_LINUX))
            builder.setAudioSendFactory(new NativeAudioSendFactory());

        loadPatreonData();

        for (int i = 0; i < shardCount; i++) {
            final int shardId = i;

            Runnable monitor = () -> {
                final Logger logger = LogManager.getLogger("ShardMonitor " + shardId);

                while (true) {
                    Bot bot = new Bot(shardUtil);

                    if (shardCount != 1) {
                        builder.useSharding(shardId, shardCount);
                    }
                    builder.addEventListener(bot);

                    JDA jda;
                    try {
                        jda = builder.buildAsync();
                    } catch (Exception e) {
                        logger.error("Failed to log in.", e);
                        if (shardCount == 1)
                            System.exit(1);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {}
                        continue;
                    } finally {
                        builder.removeEventListener(bot);
                    }

                    bot.setJda(jda);
                    shardUtil.setShard(shardId, bot);

                    synchronized (bot) {
                        try {
                            bot.wait();
                        } catch (InterruptedException e) {
                            while (jda.getStatus() == JDA.Status.CONNECTED) {
                                try {
                                    Thread.sleep(25);
                                } catch (InterruptedException ex) {}
                            }
                        }
                    }

                    if (jda.getStatus() != JDA.Status.DISCONNECTED) {
                        if (jda.getStatus() == JDA.Status.CONNECTED) {
                            jda.getPresence().setStatus(OnlineStatus.INVISIBLE);
                        }
                        jda.shutdown();
                    }
                    if (shardCount == 1) {
                        System.exit(0);
                    }
                }
            };

            Thread monThread = new Thread(monitor, "Bot Shard-" + shardId + " Monitor Thread");
            monThread.start();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {}
        }

        return 0;
    }

    private static void ensureUnsafe() {
        if (unsafe == null) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                unsafe = (Unsafe) f.get(null);
            } catch (ReflectiveOperationException e) {
                defLog.error("Failed to get Unsafe!");
            }
        }
    }

    public interface EConsumer<T> {
        void accept(T value) throws Throwable;
    }

    public static okhttp3.Callback callback(EConsumer<Response> success, EConsumer<Throwable> failure) {
        return new okhttp3.Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!(response.isSuccessful() || response.isRedirect())) {
                        failure.accept(new IOException("Request was not successful. Got status " +
                                response.code() + " " + response.message()));
                        return;
                    }

                    if (response.isRedirect()) {
                        defLog.warn("Response is redirect, status " + response.code() + " " + response.message() +
                                ". Destination: " + val(response.header("Location")).or("[not sent]"));
                    }

                    success.accept(response);
                } catch (Throwable e) {
                    try {
                        if (!(e instanceof PassException))
                            defLog.error("Error in HTTP success callback", e);

                        failure.accept(e);
                    } catch (Throwable ee) {
                        defLog.error("Error running HTTP call failure callback after error in success callback!", ee);
                    }
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                try {
                    failure.accept(e);
                } catch (Throwable ee) {
                    defLog.error("Error running HTTP call failure callback!", ee);
                }
            }
        };
    }
}
