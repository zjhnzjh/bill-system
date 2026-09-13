package com.bill.order;

public final class KafkaTopics {
    public static final String COMMAND = "order-create-command";
    public static final String RETRY = "order-create-retry";
    public static final String DLQ = "order-create-dlq";
    public static final String STATUS = "order-status-event";
    public static final String GROUP = "order-create-v1";
    private KafkaTopics() {}
}
