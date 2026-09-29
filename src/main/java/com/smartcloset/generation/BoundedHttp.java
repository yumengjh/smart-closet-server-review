package com.smartcloset.generation;

import com.smartcloset.image.ProcessingException;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;

/** No redirects, no retries; limit the body while receiving it, including chunked responses. */
final class BoundedHttp {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(15)).build();

    HttpResponse<byte[]> send(HttpRequest request, int limit) {
        var call = client.sendAsync(request, info -> new LimitedBody(limit));
        try {
            return call.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw responseTimeout();
        } catch (InterruptedException e) {
            call.cancel(true); Thread.currentThread().interrupt();
            throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_INTERRUPTED", "请求已中断，服务商可能仍在处理；不会自动重试。");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpConnectTimeoutException)
                throw new ProcessingException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_CONNECT_TIMEOUT", "本机连接服务商超时，请检查网络和服务商地址。不会自动重试。");
            if (e.getCause() instanceof HttpTimeoutException) throw responseTimeout();
            throw new ProcessingException(HttpStatus.BAD_GATEWAY, "PROVIDER_CONNECTION_FAILED", "服务商连接失败或响应超过大小限制；不会自动重试。");
        }
    }

    private ProcessingException responseTimeout() {
        return new ProcessingException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT",
                "本机等待服务商响应超过配置的时间上限；上游可能仍在处理或已计费，请先检查服务商记录。不会自动重试。");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var b : buffers) {
                if ((long) output.size() + b.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response too large")); return;
                }
                byte[] bytes = new byte[b.remaining()]; b.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
