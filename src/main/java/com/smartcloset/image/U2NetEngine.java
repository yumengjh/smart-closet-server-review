package com.smartcloset.image;

import ai.onnxruntime.*;
import com.smartcloset.ClosetProperties;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class U2NetEngine implements SegmentationEngine {
    private static final Logger log = LoggerFactory.getLogger(U2NetEngine.class);
    private static final int SIDE = 320;
    private final ClosetProperties properties;
    private OrtSession session;
    private String inputName;
    private String outputName;

    public U2NetEngine(ClosetProperties properties) { this.properties = properties; }
    public Method method() { return Method.U2NET; }
    public boolean modelPresent() { return Files.isRegularFile(properties.modelPath()); }
    public synchronized boolean loaded() { return session != null; }

    private synchronized OrtSession session() throws OrtException {
        if (session != null) return session;
        if (!modelPresent()) throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_INSTALLED",
                "未找到 U2Net 模型。请在项目目录执行 bash scripts/download-model.sh，或先使用 GRABCUT。");
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(properties.inferenceThreads());
            options.setInterOpNumThreads(1);
            var candidate = OrtEnvironment.getEnvironment().createSession(properties.modelPath().toString(), options);
            try {
                var input = candidate.getInputInfo().values().iterator().next();
                long[] shape = ((TensorInfo) input.getInfo()).getShape();
                if (!Arrays.equals(shape, new long[]{1, 3, SIDE, SIDE}))
                    throw new IllegalArgumentException("Expected U2Net [1,3,320,320] input");
                inputName = input.getName();
                outputName = candidate.getOutputNames().iterator().next();
                session = candidate;
            } catch (RuntimeException e) {
                candidate.close();
                throw e;
            }
        }
        return session;
    }

    public MaskResult segment(BufferedImage original, Region region) {
        int ow = original.getWidth(), oh = original.getHeight();
        if (region != null) region.validate(ow, oh);
        BufferedImage input = region == null ? original
                : original.getSubimage(region.x(), region.y(), region.width(), region.height());
        try {
            OrtSession current = session();
            BufferedImage resized = Images.resize(input, SIDE, SIDE);
            float[] channels = new float[3 * SIDE * SIDE];
            int maximum = 1;
            int[] pixels = resized.getRGB(0, 0, SIDE, SIDE, null, 0, SIDE);
            for (int p : pixels) maximum = Math.max(maximum, Math.max(p & 255, Math.max(p >> 8 & 255, p >> 16 & 255)));
            float[] means = {.485f, .456f, .406f}, std = {.229f, .224f, .225f};
            for (int c = 0; c < 3; c++) for (int i = 0; i < pixels.length; i++)
                channels[c * pixels.length + i] = (((pixels[i] >> (16 - c * 8)) & 255) / (float) maximum - means[c]) / std[c];
            try (var tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(channels), new long[]{1, 3, SIDE, SIDE});
                 var result = current.run(Map.of(inputName, tensor), Set.of(outputName))) {
                OnnxTensor output = (OnnxTensor) result.get(outputName).orElseThrow();
                if (!Arrays.equals(output.getInfo().getShape(), new long[]{1, 1, SIDE, SIDE}))
                    throw new IllegalArgumentException("Expected U2Net [1,1,320,320] output");
                FloatBuffer values = output.getFloatBuffer();
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < SIDE * SIDE; i++) {
                    float value = values.get(i);
                    if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite model output");
                    min = Math.min(min, value); max = Math.max(max, value);
                }
                BufferedImage smallMask = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_BYTE_GRAY);
                if (max - min > 1e-6f) for (int i = 0; i < SIDE * SIDE; i++)
                    smallMask.getRaster().setSample(i % SIDE, i / SIDE, 0, Math.round((values.get(i) - min) / (max - min) * 255));
                BufferedImage mask = Images.resize(smallMask, input.getWidth(), input.getHeight());
                if (region != null) {
                    BufferedImage full = new BufferedImage(ow, oh, BufferedImage.TYPE_BYTE_GRAY);
                    full.getRaster().setRect(region.x(), region.y(), mask.getRaster());
                    mask = full;
                }
                return new MaskResult(mask, List.of("SUBJECT_IS_NOT_VERIFIED_AS_CLOTHING"));
            }
        } catch (OrtException | IllegalArgumentException e) {
            log.error("U2Net inference failed", e);
            throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE",
                    "本地模型无法运行，请检查权重完整性和当前平台的 ONNX Runtime 支持。");
        }
    }

    @PreDestroy
    public synchronized void close() throws OrtException {
        if (session != null) { session.close(); session = null; }
    }
}
