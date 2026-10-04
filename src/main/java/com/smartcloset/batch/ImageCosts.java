package com.smartcloset.batch;

import tools.jackson.databind.JsonNode;

public final class ImageCosts {
    private ImageCosts() {}
    /** OpenAI 1:1 rates for the explicitly configured model; missing breakdown is unknown. */
    public static Double usd(String model, JsonNode usage) {
        if(usage == null || model == null) return null;
        if(model.equals("gpt-6-luna")) {
            if(!usage.path("prompt_tokens").isIntegralNumber() || !usage.path("completion_tokens").isIntegralNumber()
                || !usage.at("/prompt_tokens_details/cached_tokens").isIntegralNumber()) return null;
            long input=usage.path("prompt_tokens").asLong(),output=usage.path("completion_tokens").asLong(),cached=usage.at("/prompt_tokens_details/cached_tokens").asLong();
            if(input<0||output<0||cached<0||cached>input) return null;
            double factor=input>272000?2:1, outputFactor=input>272000?1.5:1;
            return ((input-cached)*.10*factor+cached*.01*factor+output*.50*outputFactor)/1000000;
        }
        if(!model.equals("gpt-image-2.5-flare")) return null;
        var details=usage.path("input_tokens_details");
        if(!details.path("image_tokens").isNumber() || !details.path("text_tokens").isNumber() || !usage.path("output_tokens").isNumber()) return null;
        return (details.path("image_tokens").asLong()*8d + details.path("text_tokens").asLong()*5d + usage.path("output_tokens").asLong()*30d)/1000000;
    }
}
