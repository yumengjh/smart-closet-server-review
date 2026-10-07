package com.smartcloset.processing;

public interface ProcessingHandler {
    record Candidate(String kind,String businessId,int stage,String owner,String pool,String account,int priority) {}
    String kind();
    default String frozenInput(Candidate candidate){return null;}
    default String result(ProcessingStore.Task task){return null;}
    default boolean autoRetryRejected(){return true;}
    java.util.List<Candidate> candidates();
    void execute(ProcessingStore.Task task) throws Exception;
    default int quotaUnits(ProcessingStore.Task task){return 0;}
    default void quotaUnavailable(ProcessingStore.Task task){}
    default void retry(ProcessingStore.Task task){throw com.smartcloset.image.ProcessingException.invalid("此旧流程请从衣物页面继续本地处理。");}
    default void cancel(ProcessingStore.Task task){}
    default boolean unknownResource(ProcessingStore.Task task){return false;}
    default String outcome(ProcessingStore.Task task){return "SUCCEEDED";}
}
