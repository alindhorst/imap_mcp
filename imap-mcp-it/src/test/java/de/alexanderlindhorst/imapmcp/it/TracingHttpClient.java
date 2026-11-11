package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** An HttpClient that delegates and records every exchange in a {@link ConnectorTrace}. */
final class TracingHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final ConnectorTrace trace;

    TracingHttpClient(HttpClient delegate, ConnectorTrace trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<T> response = delegate.send(request, handler);
        record(request, response, start);
        return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        long start = System.nanoTime();
        return delegate.sendAsync(request, handler).thenApply(r -> record(request, r, start));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        long start = System.nanoTime();
        return delegate.sendAsync(request, handler, push).thenApply(r -> record(request, r, start));
    }

    private <T> HttpResponse<T> record(HttpRequest request, HttpResponse<T> response, long start) {
        trace.record(request.method(), request.uri(), request.headers().map(), response.statusCode(),
                response.headers().firstValue("WWW-Authenticate").isPresent(), start);
        return response;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
