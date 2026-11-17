package cn.erp.ai.gateway;

public record ModelResult(String text, String modelId, long inputTokens, long outputTokens) {}
