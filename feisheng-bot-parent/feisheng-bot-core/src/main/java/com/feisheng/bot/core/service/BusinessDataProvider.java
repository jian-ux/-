package com.feisheng.bot.core.service;

import java.util.Date;

public interface BusinessDataProvider {
    String providerCode();

    boolean available();

    QueryResult<OrderView> findOrder(QueryIdentity identity, String orderNo, String requestId);

    QueryResult<LogisticsView> findLogistics(QueryIdentity identity, String orderNo, String requestId);

    enum QueryStatus {
        FOUND, NOT_FOUND, FORBIDDEN, UNAVAILABLE, ERROR
    }

    record QueryIdentity(String channelType, String channelUserId) {}

    record QueryResult<T>(QueryStatus status, T data, String message) {
        public static <T> QueryResult<T> found(T data) {
            return new QueryResult<>(QueryStatus.FOUND, data, null);
        }

        public static <T> QueryResult<T> notFound() {
            return new QueryResult<>(QueryStatus.NOT_FOUND, null, null);
        }

        public static <T> QueryResult<T> forbidden() {
            return new QueryResult<>(QueryStatus.FORBIDDEN, null, null);
        }

        public static <T> QueryResult<T> unavailable(String message) {
            return new QueryResult<>(QueryStatus.UNAVAILABLE, null, message);
        }

        public static <T> QueryResult<T> error(String message) {
            return new QueryResult<>(QueryStatus.ERROR, null, message);
        }
    }

    record OrderView(String orderNo, String status, String paymentStatus,
                     String itemSummary, Long amountCents, String currency,
                     Date orderTime) {}

    record LogisticsView(String orderNo, String carrier, String trackingNo,
                         String status, String latestEvent, Date latestEventTime,
                         Date estimatedDeliveryTime) {}
}
