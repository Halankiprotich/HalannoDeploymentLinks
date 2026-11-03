package com.tracker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import org.springframework.boot.jdbc.DataSourceBuilder;

/**
 * Uses Render's DATABASE_URL (postgres://user:pass@host:port/db) when present.
 * Falls back to a local H2 file so you can run it on your computer.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) throws Exception {
        String raw = env.getProperty("DATABASE_URL");

        if (raw == null || raw.isBlank()) {
            return DataSourceBuilder.create()
                .url("jdbc:h2:file:./data/tracker;AUTO_SERVER=TRUE")
                .username("sa").password("").build();
        }

        URI uri = new URI(raw.replaceFirst("^postgres(ql)?://", "http://"));
        String[] userInfo = uri.getUserInfo().split(":", 2);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String host = uri.getHost();
        // External Render hostnames contain dots and require SSL.
        String ssl = host.contains(".") ? "?sslmode=require" : "";
        String jdbc = "jdbc:postgresql://" + host + ":" + port + uri.getPath() + ssl;

        return DataSourceBuilder.create()
            .url(jdbc).username(userInfo[0]).password(userInfo.length > 1 ? userInfo[1] : "")
            .build();
    }
}
