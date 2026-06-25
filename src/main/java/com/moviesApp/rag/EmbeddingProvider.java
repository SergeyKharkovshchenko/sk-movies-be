package com.moviesApp.rag;

import java.util.List;

public interface EmbeddingProvider {
    String getId();
    // batch embed: one float[] per input text, in the same order
    List<float[]> embed(List<String> texts) throws Exception;
}
