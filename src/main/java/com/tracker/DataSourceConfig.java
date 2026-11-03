package com.tracker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Uses DATABASE_URL (postgres://user:pass@host/db) when present, for example from Neon or Render.
 * Falls back to a local H2 file so you can run it on your computer.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) throws Exception {
        String raw = env.getProperty("DATABASE_URL");
        HikariConfig cfg = new HikariConfig();

        if (raw == null || raw.isBlank()) {
            cfg.setJdbcUrl("jdbc:h2:file:./data/tracker;AUTO_SERVER=TRUE");
            cfg.setUsername("sa");
            cfg.setPassword("");
            return new HikariDataSource(cfg);
        }

        URI uri = new URI(raw.trim().replaceFirst("^postgres(ql)?://", "http://"));
        String[] userInfo = uri.getUserInfo().split(":", 2);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String host = uri.getHost();
        // Hosted databases (Neon, Render external) have dots in the host name and require SSL.
        String ssl = host.contains(".") ? "?sslmode=require" : "";

        cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + uri.getPath() + ssl);
        cfg.setUsername(userInfo[0]);
        cfg.setPassword(userInfo.length > 1 ? userInfo[1] : "");

        // Neon pauses idle databases, so keep the pool small and drop idle connections early.
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(240_000);
        cfg.setConnectionTimeout(30_000);
        return new HikariDataSource(cfg);
    }
}