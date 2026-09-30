package com.smartcloset.security;

import com.smartcloset.generation.GenerationProperties;
import com.smartcloset.recognition.RecognitionProperties;
import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Invited users may select only server-configured default models. */
@Component
public class ClosetModelPolicy {
    private final GenerationProperties generation;
    private final RecognitionProperties recognition;
    public ClosetModelPolicy(GenerationProperties generation, RecognitionProperties recognition) {
        this.generation = generation; this.recognition = recognition;
    }
    public void checkGeneration(String provider, String model) {
        if (ClosetIdentity.username() == null || ClosetIdentity.administrator()) return;
        var entry = generation.providers().get(provider);
        if (entry == null || !Objects.equals(entry.defaultModel(), model)) denied();
    }
    public void checkRecognition(String provider, String model) {
        if (ClosetIdentity.username() == null || ClosetIdentity.administrator()) return;
        var entry = recognition.providers().get(provider);
        if (entry == null || !Objects.equals(entry.defaultModel(), model)) denied();
    }
    public List<com.smartcloset.generation.GenerationService.ModelInfo> generationModels(String provider, List<com.smartcloset.generation.GenerationService.ModelInfo> upstream) {
        if (ClosetIdentity.administrator()) return upstream;
        var selected = generation.providers().get(provider);
        return selected == null || selected.defaultModel() == null ? List.of() : List.of(new com.smartcloset.generation.GenerationService.ModelInfo(selected.defaultModel(), true));
    }
    public List<String> recognitionModels(String provider, List<String> upstream) {
        if (ClosetIdentity.administrator()) return upstream;
        var selected = recognition.providers().get(provider);
        return selected == null || selected.defaultModel() == null ? List.of() : List.of(selected.defaultModel());
    }
    private static void denied() {
        throw new ProcessingException(HttpStatus.FORBIDDEN, "MODEL_NOT_ALLOWED", "当前账号只能使用管理员许可的模型。");
    }
}
