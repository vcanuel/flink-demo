package com.solidaris;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.*;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.expressions.TimeIntervalUnit;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;

import static org.apache.flink.table.api.Expressions.$;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        LOG.info("start flink job...");

        EnvironmentSettings settings = EnvironmentSettings
                .newInstance()
                .inStreamingMode()
                .build();
        TableEnvironment tableEnv = TableEnvironment.create(settings);
        Properties props = loadConfig();
        final String mysqlServer = props.getProperty("mysql");
        final String kafkaServers = props.getProperty("bootstrap.servers");
        Table transactions = createTableTransactions(tableEnv, kafkaServers);
        createTableReport(tableEnv, mysqlServer);
        Table tableResult = transactionsToReport(transactions);
        tableResult.executeInsert("spend_report");
        //createPrintSinkTable(tableEnv);
        //tableResult.executeInsert("printSinkTable");
    }

    public static Table transactionsToReport(Table transactions) {
        return transactions.select(
                        $("account_id"),
                        $("transaction_time").floor(TimeIntervalUnit.HOUR).as("log_ts"),
                        $("amount"))
                .groupBy($("account_id"), $("log_ts"))
                .select(
                        $("account_id"),
                        $("log_ts"),
                        $("amount").sum().as("amount"));
    }

    private static Table createTableTransactions(TableEnvironment tableEnv, String kafkaServers) {
        tableEnv.executeSql("CREATE TABLE transactions (\n" +
                "    account_id  BIGINT,\n" +
                "    amount      BIGINT,\n" +
                "    transaction_time TIMESTAMP(3),\n" +
                "    WATERMARK FOR transaction_time AS transaction_time - INTERVAL '5' SECOND\n" +
                ") WITH (\n" +
                "    'connector' = 'kafka',\n" +
                "    'topic'     = 'transactions',\n" +
                "    'properties.bootstrap.servers' = '" + kafkaServers + "',\n" +
                "    'scan.startup.mode' = 'earliest-offset',\n" +
                "    'format'    = 'csv'\n" +
                ")");
        return tableEnv.from("transactions");
    }

    private static void createTableReport(TableEnvironment tableEnv, String mysqlServer) {
        tableEnv.executeSql("CREATE TABLE spend_report (\n" +
                "    account_id BIGINT,\n" +
                "    log_ts     TIMESTAMP(3),\n" +
                "    amount     BIGINT\n," +
                "    PRIMARY KEY (account_id, log_ts) NOT ENFORCED" +
                ") WITH (\n" +
                "  'connector'  = 'jdbc',\n" +
                "  'url'        = 'jdbc:mysql://" + mysqlServer + "/sql-demo',\n" +
                "  'table-name' = 'spend_report',\n" +
                "  'driver'     = 'com.mysql.jdbc.Driver',\n" +
                "  'username'   = 'sql-demo',\n" +
                "  'password'   = 'demo-sql'\n" +
                ")");

    }

    private static void createPrintSinkTable(StreamTableEnvironment tableEnv) {
        final Schema schema = Schema.newBuilder()
                .column("a", DataTypes.BIGINT())
                .column("b", DataTypes.TIMESTAMP())
                .column("c", DataTypes.BIGINT())
                .build();

        tableEnv.createTemporaryTable("printSinkTable", TableDescriptor.forConnector("print")
                .schema(schema)
                .build());
    }

    public static Table getLocalTransactions(StreamExecutionEnvironment executionEnvironment, StreamTableEnvironment tableEnv) {
        DataStream<Row> dataStream = executionEnvironment.fromElements(Row.of(2L, Timestamp.from(Instant.now()), 100L),
                Row.of(2L, Timestamp.from(Instant.now().plusSeconds(100)), 200L),
                Row.of(2L, Timestamp.from(Instant.now().plusSeconds(3600)), 200L));

        return tableEnv.fromDataStream(dataStream).as("account_id", "transaction_time", "amount");
    }

    public static Properties loadConfig() {
        String profile = System.getenv("ENV_PROFILE");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Properties properties = new Properties();
        String configFile = "application.properties";
        if (profile != null && profile.contains("docker")) {
            configFile = "application-docker.properties";
        }
        if (profile != null && profile.contains("k8s")) {
            configFile = "application-k8s.properties";
        }
        LOG.info("load config file {}", configFile);
        try (InputStream is = loader.getResourceAsStream(configFile)) {
            properties.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return properties;
    }
}