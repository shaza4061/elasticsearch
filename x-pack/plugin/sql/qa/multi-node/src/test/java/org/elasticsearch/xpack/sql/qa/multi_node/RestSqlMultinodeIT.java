/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.qa.multi_node;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.NotEqualMessageBuilder;
import org.elasticsearch.test.rest.ESRestTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.UnsupportedCharsetException;
import java.sql.JDBCType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static org.elasticsearch.xpack.sql.qa.rest.RestSqlTestCase.columnInfo;
import static org.elasticsearch.xpack.sql.qa.rest.RestSqlTestCase.mode;
import static org.elasticsearch.xpack.sql.qa.rest.RestSqlTestCase.randomMode;

/**
 * Tests specific to multiple nodes.
 */
public class RestSqlMultinodeIT extends ESRestTestCase {
    /**
     * Tests count of index run across multiple nodes.
     */
    public void testIndexSpread() throws IOException {
        int documents = between(10, 100);
        createTestData(documents);
        assertCount(client(), documents);
    }

    /**
     * Tests count against index on a node that doesn't have any shards of the index.
     */
    public void testIndexOnWrongNode() throws IOException {
        HttpHost firstHost = getClusterHosts().get(0);
        String firstHostName = null;

        String match = firstHost.getHostName() + ":" + firstHost.getPort();
        Map<String, Object> nodesInfo = responseToMap(client().performRequest(new Request("GET", "/_nodes")));
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) nodesInfo.get("nodes");
        for (Map.Entry<String, Object> node : nodes.entrySet()) {
            String name = node.getKey();
            Map<?, ?> nodeEntries = (Map<?, ?>) node.getValue();
            Map<?, ?> http = (Map<?, ?>) nodeEntries.get("http");
            List<?> boundAddress = (List<?>) http.get("bound_address");
            if (boundAddress.contains(match)) {
                firstHostName = name;
                break;
            }
        }
        assertNotNull("Didn't find first host among published addresses", firstHostName);

        XContentBuilder index = JsonXContent.contentBuilder().prettyPrint().startObject();
        index.startObject("settings"); {
            index.field("routing.allocation.exclude._name", firstHostName);
        }
        index.endObject();
        index.endObject();
        Request request = new Request("PUT", "/test");
        request.setJsonEntity(Strings.toString(index));
        client().performRequest(request);
        int documents = between(10, 100);
        createTestData(documents);

        try (RestClient firstNodeClient = buildClient(restClientSettings(), new HttpHost[] {firstHost})) {
            assertCount(firstNodeClient, documents);
        }
    }

    private void createTestData(int documents) throws UnsupportedCharsetException, IOException {
        Request request = new Request("PUT", "/test/test/_bulk");
        request.addParameter("refresh", "true");

        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < documents; i++) {
            int a = 3 * i;
            int b = a + 1;
            int c = b + 1;
            bulk.append("{\"index\":{\"_id\":\"" + i + "\"}\n");
            bulk.append("{\"a\": " + a + ", \"b\": " + b + ", \"c\": " + c + "}\n");
        }
        request.setJsonEntity(bulk.toString());

        client().performRequest(request);
    }

    private Map<String, Object> responseToMap(Response response) throws IOException {
        try (InputStream content = response.getEntity().getContent()) {
            return XContentHelper.convertToMap(JsonXContent.jsonXContent, content, false);
        }
    }

    private void assertCount(RestClient client, int count) throws IOException {
        Map<String, Object> expected = new HashMap<>();
        String mode = randomMode();
        expected.put("columns", singletonList(columnInfo(mode, "COUNT(1)", "long", JDBCType.BIGINT, 20)));
        expected.put("rows", singletonList(singletonList(count)));

        Request request = new Request("POST", "/_xpack/sql");
        request.setJsonEntity("{\"query\": \"SELECT COUNT(*) FROM test\"" + mode(mode) + "}");
        Map<String, Object> actual = responseToMap(client.performRequest(request));

        if (false == expected.equals(actual)) {
            NotEqualMessageBuilder message = new NotEqualMessageBuilder();
            message.compareMaps(actual, expected);
            fail("Response does not match:\n" + message.toString());
        }
    }
}
