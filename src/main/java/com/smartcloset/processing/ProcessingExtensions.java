package com.smartcloset.processing;

import java.util.Map;

/** Optional adapters only. No embedding task or token limiter is registered in this release. */
public final class ProcessingExtensions {
    private ProcessingExtensions(){}
    public interface EmbeddingProvider {
        record Input(String assetSha256,String text,String model,Map<String,Object> parameters){}
        record Output(float[] vector,String model,String requestId,Map<String,Object> usage){}
        Output embed(Input frozenInput);
    }
    public interface TokenBudget {
        boolean reserve(String credentialAccount,String requestBinding,long estimatedTokens,long deadlineMs);
        void settle(String requestBinding,long actualTokens,boolean dispatched);
    }
}
