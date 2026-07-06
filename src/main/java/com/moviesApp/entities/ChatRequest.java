package com.moviesApp.entities;

import java.util.List;

public class ChatRequest {

    private List<ChatMessage> messages;
    private double temperature;
    private int maxTokens;
    private boolean stream;
    // which embedding provider to use for RAG; defaults to "jina" if null
    private String embedder;
    // "combined" (default) | "vector" — skip graph expansion | "graph" — skip vector, use keyword search
    private String ragMode;

    public ChatRequest() {}

    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public String getEmbedder() { return embedder; }
    public void setEmbedder(String embedder) { this.embedder = embedder; }

    public String getRagMode() { return ragMode; }
    public void setRagMode(String ragMode) { this.ragMode = ragMode; }

}
