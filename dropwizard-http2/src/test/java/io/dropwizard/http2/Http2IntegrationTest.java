package io.dropwizard.http2;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.net.HttpHeaders;
import io.dropwizard.Configuration;
import io.dropwizard.logging.BootstrapLogging;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class Http2IntegrationTest {

    static {
        BootstrapLogging.bootstrap();
    }

    @Rule
    public final DropwizardAppRule<Configuration> appRule = new DropwizardAppRule<>(
            FakeApplication.class, ResourceHelpers.resourceFilePath("test-http2.yml"),
            Optional.of("tls_http2"),
            ConfigOverride.config("tls_http2", "server.connector.keyStorePath",
                    ResourceHelpers.resourceFilePath("stores/http2_server.jks"))
    );
    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private HttpClient client;

    @Before
    public void setUp() throws Exception {
        sslContextFactory.setTrustStorePath(ResourceHelpers.resourceFilePath("stores/http2_client.jts"));
        sslContextFactory.setTrustStorePassword("http2_client");
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        sslContextFactory.start();

        client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()), sslContextFactory);
        client.start();
    }

    @After
    public void tearDown() throws Exception {
        client.stop();
    }

    @Test
    public void testHttp11() throws Exception {
        final String hostname = "localhost";
        final int port = appRule.getLocalPort();
        final JerseyClient http11Client = new JerseyClientBuilder()
                .sslContext(sslContextFactory.getSslContext())
                .build();
        final Response response = http11Client.target("https://" + hostname + ":" + port + "/api/test")
                .request()
                .get();
        assertThat(response.getHeaderString(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.readEntity(String.class)).isEqualTo(FakeApplication.HELLO_WORLD);
        http11Client.close();
    }

    @Test
    public void testHttp2() throws Exception {
        final String hostname = "localhost";
        final int port = appRule.getLocalPort();

        final ContentResponse response = client.GET("https://" + hostname + ":" + port + "/api/test");
        assertThat(response.getVersion()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(FakeApplication.HELLO_WORLD);
    }

    @Test
    public void testHttp2ManyRequests() throws Exception {
        final String hostname = "localhost";
        final int port = appRule.getLocalPort();

        // For some reason the library requires to perform the first request synchronously with HTTP/2
        testHttp2();

        final int amount = 100;
        final CountDownLatch latch = new CountDownLatch(amount);
        for (int i = 0; i < amount; i++) {
            client.newRequest("https://" + hostname + ":" + port + "/api/test")
                    .send(new BufferingResponseListener() {
                        @Override
                        public void onComplete(Result result) {
                            assertThat(result.getResponse().getVersion()).isEqualTo(HttpVersion.HTTP_2);
                            assertThat(result.getResponse().getStatus()).isEqualTo(200);
                            assertThat(getContentAsString(Charsets.UTF_8)).isEqualTo(FakeApplication.HELLO_WORLD);
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
