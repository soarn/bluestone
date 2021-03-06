package com.khronodragon.bluestone;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.support.ConnectionSource;
import com.khronodragon.bluestone.sql.GuildPrefix;
import com.zaxxer.hikari.HikariDataSource;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;

public class PrefixStore {
    private static final Logger logger = LogManager.getLogger(PrefixStore.class);
    public final String defaultPrefix;
    public final HikariDataSource pool;
    public final TLongObjectMap<String> cache = new TLongObjectHashMap<>();

    PrefixStore(HikariDataSource pool, String defaultPrefix) {
        this.pool = pool;
        this.defaultPrefix = defaultPrefix;
    }

    public String getPrefix(long guildId) {
        String prefix = cache.get(guildId);

        if (prefix != null) {
            return prefix;
        } else {
            try { // TODO: directly query using connection from pool
                GuildPrefix result = dao.queryForId(guildId);
                if (result == null) {
                    cache.put(guildId, defaultPrefix);
                    return defaultPrefix;
                }

                prefix = result.getPrefix();
                cache.put(guildId, prefix);

                return prefix;
            } catch (SQLException|NullPointerException e) {
                logger.error("Error getting prefix from DB", e);
                return defaultPrefix;
            }
        }
    }
}
