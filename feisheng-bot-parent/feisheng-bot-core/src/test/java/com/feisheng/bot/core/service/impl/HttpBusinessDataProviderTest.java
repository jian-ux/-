package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.service.BusinessDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBusinessDataProviderTest {
    @Test
    void sendsCallerIdentityAndAcceptsOwnedOrder() {
        HttpBusinessDataProvider provider = provider(true);
        MockRestServiceServer server = MockRestServiceServer.createServer(provider.restTemplate());
        server.expect(requestTo("https://business.example/orders/FS202607170001"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Channel-Type", "web"))
            .andExpect(header("X-Channel-User-Id", "user-1"))
            .andExpect(header("X-Request-Id", "req-1"))
            .andRespond(withSuccess("""
                {"data":{"orderNo":"FS202607170001","channelType":"web",
                "channelUserId":"user-1","status":"已发货","paymentStatus":"已支付",
                "amountCents":19900,"currency":"CNY"}}
                """, MediaType.APPLICATION_JSON));

        BusinessDataProvider.QueryResult<BusinessDataProvider.OrderView> result =
            provider.findOrder(new BusinessDataProvider.QueryIdentity("web", "user-1"),
                "FS202607170001", "req-1");

        assertEquals(BusinessDataProvider.QueryStatus.FOUND, result.status());
        assertEquals("已发货", result.data().status());
        server.verify();
    }

    @Test
    void rejectsResponseWhoseOwnerDoesNotMatchCaller() {
        HttpBusinessDataProvider provider = provider(true);
        MockRestServiceServer server = MockRestServiceServer.createServer(provider.restTemplate());
        server.expect(requestTo("https://business.example/orders/FS202607170001"))
            .andRespond(withSuccess("""
                {"orderNo":"FS202607170001","channelType":"web",
                "channelUserId":"another-user","status":"已发货"}
                """, MediaType.APPLICATION_JSON));

        BusinessDataProvider.QueryResult<BusinessDataProvider.OrderView> result =
            provider.findOrder(new BusinessDataProvider.QueryIdentity("web", "user-1"),
                "FS202607170001", "req-2");

        assertEquals(BusinessDataProvider.QueryStatus.FORBIDDEN, result.status());
        server.verify();
    }

    private HttpBusinessDataProvider provider(boolean requireOwnerMatch) {
        return new HttpBusinessDataProvider(new RestTemplateBuilder(),
            "https://business.example", "token", "/orders/{orderNo}",
            "/logistics/{orderNo}", 3, 8, requireOwnerMatch);
    }
}
