package com.clickhouse.kafka.connect.sink.helper;

import com.clickhouse.client.*;
import com.clickhouse.client.api.metrics.OperationMetrics;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import com.clickhouse.data.ClickHouseFormat;
import com.clickhouse.kafka.connect.sink.db.helper.ClickHouseFieldDescriptor;
import com.clickhouse.kafka.connect.sink.db.helper.ClickHouseHelperClient;
import com.clickhouse.kafka.connect.sink.db.mapping.Column;
import com.clickhouse.kafka.connect.sink.db.mapping.Type;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ClickHouseTestHelpers {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseTestHelpers.class);
    public static final String CLICKHOUSE_VERSION_DEFAULT = "24.3";
    public static final String CLICKHOUSE_PROXY_VERSION_DEFAULT = "23.8";
    public static final String CLICKHOUSE_DOCKER_IMAGE = String.format("clickhouse/clickhouse-server:%s", getClickhouseVersion());
    public static final String CLICKHOUSE_FOR_PROXY_DOCKER_IMAGE = String.format("clickhouse/clickhouse-server:%s", CLICKHOUSE_PROXY_VERSION_DEFAULT);

    public static final String HTTPS_PORT = "8443";
    public static final String DATABASE_DEFAULT = "default";
    public static final String USERNAME_DEFAULT = "default";
    public static String getClickhouseVersion() {
        String clickHouseVersion = System.getenv("CLICKHOUSE_VERSION");
        if (clickHouseVersion == null) {
            clickHouseVersion = CLICKHOUSE_VERSION_DEFAULT;
        }
        return clickHouseVersion;
    }
    public static boolean isCloud() {
        String version = System.getenv("CLICKHOUSE_VERSION");
        LOGGER.info("Version: {}", version);
        return version != null && version.equalsIgnoreCase("cloud");
    }

    public static void query(ClickHouseHelperClient chc, String query) {
        if (chc.isUseClientV2()) {
            chc.queryV2(query);
        } else {
            chc.queryV1(query);
        }
    }
    public static void dropTable(ClickHouseHelperClient chc, String tableName) {
        String dropTable = String.format("DROP TABLE IF EXISTS `%s`", tableName);
        try {
            chc.getClient().queryRecords(dropTable).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
    public static OperationMetrics createTable(ClickHouseHelperClient chc, String tableName, String createTableQuery) {
        LOGGER.info("Creating table: {}, Query: {}", tableName, createTableQuery);
        OperationMetrics operationMetrics = createTable(chc, tableName, createTableQuery, new HashMap<>());
        if (isCloud()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                LOGGER.error("Error while sleeping", e);
            }
        }
        return operationMetrics;
    }

    public static OperationMetrics createTable(ClickHouseHelperClient chc, String tableName, String createTableQuery, Map<String, Serializable> clientSettings) {
        final String createTableQueryTmp = String.format(createTableQuery, tableName);
        QuerySettings settings = new QuerySettings();
        for (Map.Entry<String, Serializable> entry : clientSettings.entrySet()) {
            settings.setOption(entry.getKey(), entry.getValue());
        }
        try {
            Records records = chc.getClient().queryRecords(createTableQueryTmp, settings).get(120, java.util.concurrent.TimeUnit.SECONDS);
            return records.getMetrics();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static List<JSONObject> getAllRowsAsJson(ClickHouseHelperClient chc, String tableName)  {
        String query = String.format("SELECT * FROM `%s`", tableName);
        QuerySettings querySettings = new QuerySettings();
        querySettings.setFormat(ClickHouseFormat.JSONEachRow);
        try {
            QueryResponse queryResponse = chc.getClient().query(query, querySettings).get();
            List<JSONObject> jsonObjects = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(queryResponse.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject jsonObject = new JSONObject(line);
                jsonObjects.add(jsonObject);
            }
            return jsonObjects;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    //        try (ClickHouseClient client = ClickHouseClient.builder()
    //                .options(chc.getDefaultClientOptions())
    //                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
    //                .build();
    //             ClickHouseResponse response = client.read(chc.getServer())
    //                     .query(query)
    //                     .format(ClickHouseFormat.JSONEachRow)
    //                     .executeAndWait()) {
    //
    //            return StreamSupport.stream(response.records().spliterator(), false)
    //                    .map(record -> record.getValue(0).asString())
    //                    .map(JSONObject::new)
    //                    .collect(Collectors.toList());
    //        } catch (ClickHouseException e) {
    //            throw new RuntimeException(e);
    //        }
    }

    public static int countRows(ClickHouseHelperClient chc, String tableName) {
        String queryCount = String.format("SELECT COUNT(*) FROM `%s`", tableName);

        try {
            Records records = chc.getClient().queryRecords(queryCount).get(120, TimeUnit.SECONDS);
            // Note we probrbly need asInteger() here
            String value = records.iterator().next().getString(1);
            return Integer.parseInt(value);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static int sumRows(ClickHouseHelperClient chc, String tableName, String column) {
        String queryCount = String.format("SELECT SUM(`%s`) FROM `%s`", column, tableName);
        try {
            Records records = chc.getClient().queryRecords(queryCount).get();
            String value = records.iterator().next().getString(1);
            return (int)(Float.parseFloat(value));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public static int countRowsWithEmojis(ClickHouseHelperClient chc, String tableName) {
        String queryCount = "SELECT COUNT(*) FROM `" + tableName + "` WHERE str LIKE '%\uD83D\uDE00%'";
        try {
            Records records = chc.getClient().queryRecords(queryCount).get();
            String value = records.iterator().next().getString(1);
            return (int)(Float.parseFloat(value));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean validateRows(ClickHouseHelperClient chc, String topic, Collection<SinkRecord> sinkRecords) {
        boolean match = false;
        try {
            QuerySettings querySettings = new QuerySettings();
            querySettings.setFormat(ClickHouseFormat.JSONStringsEachRow);
            QueryResponse queryResponse = chc.getClient().query(String.format("SELECT * FROM `%s`", topic), querySettings).get();
            Gson gson = new Gson();

            List<String> records = new ArrayList<>();
            for (SinkRecord record : sinkRecords) {
                Map<String, String> recordMap = new TreeMap<>();
                if (record.value() instanceof HashMap) {
                    for (Map.Entry<String, Object> entry : ((HashMap<String, Object>) record.value()).entrySet()) {
                        recordMap.put(entry.getKey(), entry.getValue().toString());
                    }
                } else if (record.value() instanceof Struct) {
                    ((Struct) record.value()).schema().fields().forEach(f -> {
                        recordMap.put(f.name(), ((Struct) record.value()).get(f).toString());
                    });
                }

                String gsonString = gson.toJson(recordMap);
                records.add(gsonString.replace(".0", "").replace(" ","").replace("'","").replace("\\u003d",":"));
            }
            List<String> results = new ArrayList<>();
            LOGGER.info("read rows [%d]", queryResponse.getReadRows());
            BufferedReader reader = new BufferedReader(new InputStreamReader(queryResponse.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String gsonString = line.replace("'","").replace(" ","").replace("\\u003d",":");
                Map<String, String> resultMap = new TreeMap<>((Map<String, String>) gson.fromJson(gsonString, new TypeToken<Map<String, String>>() {}.getType()));
                results.add(gson.toJson(resultMap));
            }
            for (String record : records) {
                if (results.get(0).equals(record)) {
                    match = true;
                    LOGGER.info("Matched record: {}", record);
                    LOGGER.info("Matched result: {}", results.get(0));
                    break;
                }
            }

            LOGGER.info("Match? {}", match);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return match;
    }

    @Deprecated(since = "for debug purposes only")
    public static void showRows(ClickHouseHelperClient chc, String topic) {
        String queryCount = String.format("select * from `%s`", topic);
        try (ClickHouseClient client = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);
             ClickHouseResponse response = client.read(chc.getServer()) // or client.connect(endpoints)
                     // you'll have to parse response manually if using a different format
                     .query(queryCount)
                     .executeAndWait()) {
            ClickHouseResponseSummary summary = response.getSummary();
            response.records().forEach(r -> {
                //int colsCount = r.size();
                System.out.println(r.getValue(0));
            });
        } catch (ClickHouseException e) {
            throw new RuntimeException(e);
        }
    }

    public static ClickHouseFieldDescriptor newDescriptor(String name, String valueType) {
        return ClickHouseFieldDescriptor
                .builder()
                .name(name)
                .type(valueType)
                .isSubcolumn(name.contains("."))
                .build();
    }

    public static ClickHouseFieldDescriptor newDescriptor(String valueType) {
        return ClickHouseFieldDescriptor
                .builder()
                .name("columnName")
                .type(valueType)
                .build();
    }

    public static Column col(Type type) {
        return Column.builder().type(type).build();
    }

    public static Column col(Type type, int precision, int scale) {
        return Column.builder().type(type).precision(precision).scale(scale).build();
    }
}
