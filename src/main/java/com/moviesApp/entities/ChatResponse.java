package com.moviesApp.entities;

import java.util.List;
import java.util.Map;

public class ChatResponse {

    private String id;
    private String model;
    private Choice choice;
    // RAG: graph nodes/relationships retrieved to answer the question
    private List<Map<String, Object>> graphContext;
    // RAG: follow-up questions based on what was retrieved
    private List<String> suggestedQuestions;

    public ChatResponse() {}

    public ChatResponse(String id, String model, Choice choice) {
        this.id     = id;
        this.model  = model;
        this.choice = choice;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Choice getChoice() { return choice; }
    public void setChoice(Choice choice) { this.choice = choice; }

    public List<Map<String, Object>> getGraphContext() { return graphContext; }
    public void setGraphContext(List<Map<String, Object>> graphContext) { this.graphContext = graphContext; }

    public List<String> getSuggestedQuestions() { return suggestedQuestions; }
    public void setSuggestedQuestions(List<String> suggestedQuestions) { this.suggestedQuestions = suggestedQuestions; }

    public static class Choice {

        private ChatMessage message;
        private String finishReason;

        public Choice() {}

        public Choice(ChatMessage message, String finishReason) {
            this.message      = message;
            this.finishReason = finishReason;
        }

        public ChatMessage getMessage() { return message; }
        public void setMessage(ChatMessage message) { this.message = message; }

        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    }

}
