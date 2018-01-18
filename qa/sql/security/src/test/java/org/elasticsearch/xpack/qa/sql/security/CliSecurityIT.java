/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.security;

import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.xpack.qa.sql.cli.RemoteCli;
import org.elasticsearch.xpack.qa.sql.cli.RemoteCli.SecurityConfig;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.qa.sql.cli.CliIntegrationTestCase.elasticsearchAddress;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

public class CliSecurityIT extends SqlSecurityTestCase {
    static SecurityConfig adminSecurityConfig() {
        String keystoreLocation;
        String keystorePassword;
        if (RestSqlIT.SSL_ENABLED) {
            Path keyStore;
            try {
                keyStore = PathUtils.get(RestSqlIT.class.getResource("/test-node.jks").toURI());
            } catch (URISyntaxException e) {
                throw new RuntimeException("exception while reading the store", e);
            }
            if (!Files.exists(keyStore)) {
                throw new IllegalStateException("Keystore file [" + keyStore + "] does not exist.");
            }
            keystoreLocation = keyStore.toAbsolutePath().toString();
            keystorePassword = "keypass";
        } else {
            keystoreLocation = null;
            keystorePassword = null;
        }
        return new SecurityConfig(RestSqlIT.SSL_ENABLED, "test_admin", "x-pack-test-password", keystoreLocation, keystorePassword);
    }

    /**
     * Perform security test actions using the CLI.
     */
    private static class CliActions implements Actions {
        private SecurityConfig userSecurity(String user) {
            SecurityConfig admin = adminSecurityConfig();
            if (user == null) {
                return admin;
            }
            return new SecurityConfig(RestSqlIT.SSL_ENABLED, user, "testpass", admin.keystoreLocation(), admin.keystorePassword());
        }

        @Override
        public void queryWorksAsAdmin() throws Exception {
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, adminSecurityConfig())) {
                assertThat(cli.command("SELECT * FROM test ORDER BY a"), containsString("a       |       b       |       c"));
                assertEquals("---------------+---------------+---------------", cli.readLine());
                assertThat(cli.readLine(), containsString("1              |2              |3"));
                assertThat(cli.readLine(), containsString("4              |5              |6"));
                assertEquals("", cli.readLine());
            }
        }

        @Override
        public void expectMatchesAdmin(String adminSql, String user, String userSql) throws Exception {
            expectMatchesAdmin(adminSql, user, userSql, cli -> {});
        }

        @Override
        public void expectScrollMatchesAdmin(String adminSql, String user, String userSql) throws Exception {
            expectMatchesAdmin(adminSql, user, userSql, cli -> {
                assertEquals("fetch size set to [90m1[0m", cli.command("fetch size = 1"));
                assertEquals("fetch separator set to \"[90m -- fetch sep -- [0m\"",
                        cli.command("fetch separator = \" -- fetch sep -- \""));
            });
        }

        public void expectMatchesAdmin(String adminSql, String user, String userSql,
                CheckedConsumer<RemoteCli, Exception> customizer) throws Exception {
            List<String> adminResult = new ArrayList<>();
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, adminSecurityConfig())) {
                customizer.accept(cli);
                adminResult.add(cli.command(adminSql));
                String line;
                do {
                    line = cli.readLine();
                    adminResult.add(line);
                } while (false == (line.equals("[0m") || line.equals("")));
                adminResult.add(line);
            }

            Iterator<String> expected = adminResult.iterator();
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, userSecurity(user))) {
                customizer.accept(cli);
                assertTrue(expected.hasNext());
                assertEquals(expected.next(), cli.command(userSql));
                String line;
                do {
                    line = cli.readLine();
                    assertTrue(expected.hasNext());
                    assertEquals(expected.next(), line);
                } while (false == (line.equals("[0m") || line.equals("")));
                assertTrue(expected.hasNext());
                assertEquals(expected.next(), line);
                assertFalse(expected.hasNext());
            }
        }

        @Override
        public void expectDescribe(Map<String, String> columns, String user) throws Exception {
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, userSecurity(user))) {
                assertThat(cli.command("DESCRIBE test"), containsString("column     |     type"));
                assertEquals("---------------+---------------", cli.readLine());
                for (Map.Entry<String, String> column : columns.entrySet()) {
                    assertThat(cli.readLine(), both(startsWith(column.getKey())).and(containsString("|" + column.getValue())));
                }
                assertEquals("", cli.readLine());
            }
        }

        @Override
        public void expectShowTables(List<String> tables, String user) throws Exception {
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, userSecurity(user))) {
                String tablesOutput = cli.command("SHOW TABLES");
                assertThat(tablesOutput, containsString("name"));
                assertThat(tablesOutput, containsString("type"));
                assertEquals("---------------+---------------", cli.readLine());
                for (String table : tables) {
                    String line = null;
                    while (line == null || line.startsWith(".security")) {
                        line = cli.readLine();
                    }
                    assertThat(line, containsString(table));
                }
                assertEquals("", cli.readLine());
            }
        }

        @Override
        public void expectUnknownIndex(String user, String sql) throws Exception {
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, userSecurity(user))) {
                assertThat(cli.command(sql), containsString("Bad request"));
                assertThat(cli.readLine(), containsString("Unknown index"));
            }
        }

        @Override
        public void expectForbidden(String user, String sql) throws Exception {
            /*
             * Cause the CLI to skip its connection test on startup so we
             * can get a forbidden exception when we run the query.
             */
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), false, userSecurity(user))) {
                assertThat(cli.command(sql), containsString("is unauthorized for user [" + user + "]"));
            }
        }

        @Override
        public void expectUnknownColumn(String user, String sql, String column) throws Exception {
            try (RemoteCli cli = new RemoteCli(elasticsearchAddress(), true, userSecurity(user))) {
                assertThat(cli.command(sql), containsString("[1;31mBad request"));
                assertThat(cli.readLine(), containsString("Unknown column [" + column + "][1;23;31m][0m"));
            }
        }
    }

    public CliSecurityIT() {
        super(new CliActions());
    }
}
