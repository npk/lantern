package org.lantern.udtrelay;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Future;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.junit.Test;
import org.lantern.LanternUtils;
import org.littleshoot.proxy.DefaultHttpProxyServer;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.ProxyCacheManager;
import org.littleshoot.proxy.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UdtRelayTest {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Test
    public void test() throws Exception {
        // The idea here is to start an HTTP proxy server locally that the UDT
        // relay relays to -- i.e. just like the real world setup.
        
        // Note that an internet connection is required to run this test.
        final int proxyPort = LanternUtils.randomPort();
        final int relayPort = LanternUtils.randomPort();
        startProxyServer(proxyPort);
        final InetSocketAddress localRelayAddress = 
            new InetSocketAddress("127.0.0.1", relayPort);
        final UdtRelayProxy relay = 
            new UdtRelayProxy(localRelayAddress.getPort(), "127.0.0.1", 
                proxyPort);
        startRelay(relay, localRelayAddress.getPort());
        
        // We do this a few times to make sure there are no issues with 
        // subsequent runs.
        for (int i = 0; i < 3; i++) {
            hitRelayRaw(relayPort);
        }
    }
    
    private static final String RESPONSE = 
            "HTTP 200 OK\r\n" +
            "Server: Gnutella\r\n"+
            "Content-type: application/binary\r\n"+
            "Content-Length: 0\r\n" +
            "\r\n";
    
    private void startProxyServer(final int port) throws Exception {
        final Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                final HttpProxyServer server = new DefaultHttpProxyServer(port, 
                    new ProxyCacheManager() {
                    
                    @Override
                    public boolean returnCacheHit(final HttpRequest request, 
                            final Channel channel) {
                        System.err.println("RETURNING CACHE HIT");
                        channel.write(ChannelBuffers.wrappedBuffer(RESPONSE.getBytes()));
                        ProxyUtils.closeOnFlush(channel);
                        return true;
                    }
                    
                    @Override
                    public Future<String> cache(final HttpRequest originalRequest,
                        final org.jboss.netty.handler.codec.http.HttpResponse httpResponse,
                        final Object response, ChannelBuffer encoded) {
                        return null;
                    }
                });
                
                System.out.println("About to start...");
                server.start();
            }
        }, "Relay-Test-Thread");
        t.setDaemon(true);
        t.start();
        LanternUtils.waitForServer(port, 6000);
    }

    private void hitRelayRaw(final int relayPort) throws Exception {
        final Socket sock = new Socket();
        final String request = 
            "GET http://www.google.com HTTP/1.1\r\n"+
            "Host: www.google.com\r\n"+
            "User-Agent: Apache-HttpClient/4.2.2 (java 1.5)\r\n"+
            "Connection: Keep-Alive\r\n\r\n";
        sock.connect(new InetSocketAddress("127.0.0.1", relayPort));
        
        sock.getOutputStream().write(request.getBytes());
        final BufferedReader br = 
            new BufferedReader(new InputStreamReader(sock.getInputStream()));
        final StringBuilder sb = new StringBuilder();
        String cur = br.readLine();
        sb.append(cur);
        while(StringUtils.isNotBlank(cur)) {
            System.err.println(cur);
            cur = br.readLine();
            sb.append(cur);
        }
        assertTrue("Unexpected response "+sb.toString(), sb.toString().startsWith("HTTP 200 OK"));
        System.out.println("");
        sock.close();
    }
    
    private void hitRelay(final int relayPort) throws Exception {
        // We create new clients each time here to ensure that we're always
        // using a new client-side port.
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", relayPort);
        
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        httpClient.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler(2,true));
        httpClient.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 50000);
        httpClient.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 120000);
        
        //final HttpGet get = new HttpGet("http://www.google.com");
        final HttpGet get = new HttpGet("http://127.0.0.1");
        final HttpResponse response = httpClient.execute(get);
        final HttpEntity entity = response.getEntity();
        final String body = 
            IOUtils.toString(entity.getContent()).toLowerCase();
        EntityUtils.consume(entity);
        assertTrue(body.trim().endsWith("</script></body></html>"));
        
        get.reset();
    }
    
    private void startRelay(final UdtRelayProxy relay, 
        final int localRelayPort) throws Exception {
        final Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    relay.run();
                } catch (Exception e) {
                    throw new RuntimeException("Error running server", e);
                }
            }
        }, "Relay-Test-Thread");
        t.setDaemon(true);
        t.start();
        LanternUtils.waitForServer(localRelayPort, 6000);
    }

}