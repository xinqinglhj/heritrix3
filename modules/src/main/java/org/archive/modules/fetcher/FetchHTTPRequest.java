/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.fetcher;

import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_ETAG_HEADER;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_STATUS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolVersion;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.AbortableHttpRequestBase;
import org.apache.http.client.methods.BasicAbortableHttpEntityEnclosingRequest;
import org.apache.http.client.methods.BasicAbortableHttpRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.MessageConstraints;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.SocketClientConnection;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainSocketFactory;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultClientConnectionFactory;
import org.apache.http.impl.conn.DefaultHttpResponseParserFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SocketClientConnectionImpl;
import org.apache.http.impl.io.SessionBufferImplFactory;
import org.apache.http.io.HttpMessageParserFactory;
import org.apache.http.io.HttpMessageWriterFactory;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.archive.modules.CrawlURI;
import org.archive.modules.CrawlURI.FetchType;
import org.archive.modules.Processor;
import org.archive.modules.credential.Credential;
import org.archive.modules.credential.HtmlFormCredential;
import org.archive.modules.credential.HttpAuthenticationCredential;
import org.archive.modules.extractor.LinkContext;
import org.archive.modules.net.CrawlHost;
import org.archive.modules.net.CrawlServer;
import org.archive.modules.net.ServerCache;
import org.archive.util.Recorder;

/**
 * @contributor nlevitt
 */
class FetchHTTPRequest {

    protected static class RecordingSocketClientConnection extends SocketClientConnectionImpl {
        private final AbortableHttpRequestBase request;
        private final CrawlURI curi;
        private FetchHTTP fetcher;

        protected RecordingSocketClientConnection(FetchHTTP fetcher,
                int buffersize, CharsetDecoder chardecoder,
                CharsetEncoder charencoder, MessageConstraints constraints,
                ContentLengthStrategy incomingContentStrategy,
                ContentLengthStrategy outgoingContentStrategy,
                HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
                HttpMessageParserFactory<HttpResponse> responseParserFactory,
                SessionBufferImplFactory sessionBufferFactory,
                AbortableHttpRequestBase request, CrawlURI curi) {
            super(buffersize, chardecoder, charencoder, constraints,
                    incomingContentStrategy, outgoingContentStrategy,
                    requestWriterFactory, responseParserFactory,
                    sessionBufferFactory);
            this.fetcher = fetcher;
            this.request = request;
            this.curi = curi;
        }

        @Override
        public void receiveResponseEntity(HttpResponse response)
                throws HttpException, IOException {
            Recorder recorder = Recorder.getHttpRecorder();
            if (recorder != null) {
                recorder.markContentBegin();
            }

            if (!fetcher.maybeMidfetchAbort(curi, request)) {
                super.receiveResponseEntity(response);
            }
        }
        
        @Override
        public void shutdown() throws IOException {
            super.shutdown();

            /*
             * Need to do this to avoid "java.io.IOException: RIS already open"
             * on urls that are retried within httpcomponents. Exercised by
             * FetchHTTPTests.testNoResponse()
             */
            Recorder recorder = Recorder.getHttpRecorder();
            if (recorder != null) {
                recorder.close();
                recorder.closeRecorders();
            }
        }
    }
    
    /**
     * Implementation of {@link DnsResolver} that uses the server cache which is
     * normally expected to have been populated by FetchDNS.
     */
    protected static class ServerCacheResolver implements DnsResolver {
        protected static Logger logger = Logger.getLogger(DnsResolver.class.getName());
        protected ServerCache serverCache;

        public ServerCacheResolver(ServerCache serverCache) {
            this.serverCache = serverCache;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            CrawlHost crawlHost = this.serverCache.getHostFor(host);
            if (crawlHost != null) {
                InetAddress ip = crawlHost.getIP();
                if (ip != null) {
                    return new InetAddress[] {ip};
                }
            }

            logger.info("host \"" + host + "\" is not in serverCache, allowing java to resolve it");
            return new InetAddress[] {InetAddress.getByName(host)};
        }
    }

    private static Logger logger = Logger.getLogger(FetchHTTPRequest.class.getName());

    protected FetchHTTP fetcher;
    protected CrawlURI curi;
    protected HttpClientBuilder httpClientBuilder;
    protected RequestConfig.Builder requestConfigBuilder;
    protected HttpClientContext httpClientContext;
    protected AbortableHttpRequestBase request;
    protected HttpHost targetHost;
    protected boolean addedCredentials;
    protected HttpHost proxyHost;

    public FetchHTTPRequest(FetchHTTP fetcher, CrawlURI curi) throws URIException {
        this.fetcher = fetcher;
        this.curi = curi;
        
        this.targetHost = new HttpHost(curi.getUURI().getHost(), 
                curi.getUURI().getPort(), curi.getUURI().getScheme());
        
        this.httpClientContext = new HttpClientContext();
        this.requestConfigBuilder = RequestConfig.custom();

        ProtocolVersion httpVersion = fetcher.getConfiguredHttpVersion();
        String proxyHostname = (String) fetcher.getAttributeEither(curi, "httpProxyHost");
        Integer proxyPort = (Integer) fetcher.getAttributeEither(curi, "httpProxyPort");
                
        String requestLineUri;
        if (StringUtils.isNotEmpty(proxyHostname) && proxyPort != null) {
            this.proxyHost = new HttpHost(proxyHostname, proxyPort);
            this.requestConfigBuilder.setDefaultProxy(this.proxyHost);
            requestLineUri = curi.getUURI().toString();
        } else {
            requestLineUri = curi.getUURI().getPathQuery();
        }

        if (curi.getFetchType() == FetchType.HTTP_POST) {
            this.request = new BasicAbortableHttpEntityEnclosingRequest("POST", 
                    requestLineUri, httpVersion);
        } else {
            this.request = new BasicAbortableHttpRequest("GET", 
                    requestLineUri, httpVersion);
            curi.setFetchType(FetchType.HTTP_GET);
        }

        if (proxyHost != null) {
            request.addHeader("Proxy-Connection", "close");
        }
        
        initHttpClientBuilder();
        configureHttpClientBuilder();
        
        configureRequestHeaders();
        configureRequest();
        
        this.addedCredentials = populateTargetCredential();
        populateHttpProxyCredential();
    }
    
    protected void configureRequestHeaders() {
        if (fetcher.getAcceptCompression()) {
            request.addHeader("Accept-Encoding", "gzip,deflate");
        }
        
        String from = fetcher.getUserAgentProvider().getFrom();
        if (StringUtils.isNotBlank(from)) {
            request.setHeader(HttpHeaders.FROM, from);
        }
        
        if (fetcher.getMaxLengthBytes() > 0 && fetcher.getSendRange()) {
            String rangeEnd = Long.toString(fetcher.getMaxLengthBytes() - 1);
            request.setHeader(HttpHeaders.RANGE, "bytes=0-" + rangeEnd);
        }

        if (fetcher.getSendConnectionClose()) {
            request.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
        }
        
        // referer
        if (fetcher.getSendReferer() && !LinkContext.PREREQ_MISC.equals(curi.getViaContext())) {
            // RFC2616 says no referer header if referer is https and the url is not
            String via = Processor.flattenVia(curi);
            if (!StringUtils.isEmpty(via)
                    && !(curi.getVia().getScheme().equals(FetchHTTP.HTTPS_SCHEME) 
                            && curi.getUURI().getScheme().equals(FetchHTTP.HTTP_SCHEME))) {
                request.setHeader(HttpHeaders.REFERER, via);
            }
        }

        if (!curi.isPrerequisite()) {
            maybeAddConditionalGetHeader(fetcher.getSendIfModifiedSince(),
                    A_LAST_MODIFIED_HEADER, "If-Modified-Since");
            maybeAddConditionalGetHeader(fetcher.getSendIfNoneMatch(),
                    A_ETAG_HEADER, "If-None-Match");
        }

        // TODO: What happens if below method adds a header already added above,
        // e.g. Connection, Range, or Referer?
        for (String headerString: fetcher.getAcceptHeaders()) {
            String[] nameValue = headerString.split(": +");
            if (nameValue.length == 2) {
                request.addHeader(nameValue[0], nameValue[1]);
            } else {
                logger.warning("Invalid accept header: " + headerString);
            }
        }
    }

    /**
     * Add the given conditional-GET header, if the setting is enabled and
     * a suitable value is available in the URI history. 
     * @param setting true/false enablement setting name to consult
     * @param sourceHeader header to consult in URI history
     * @param targetHeader header to set if possible
     */
    protected void maybeAddConditionalGetHeader(boolean conditional,
            String sourceHeader, String targetHeader) {
        if (conditional) {
            try {
                HashMap<String, Object>[] history = curi.getFetchHistory();
                int previousStatus = (Integer) history[0].get(A_STATUS);
                if (previousStatus <= 0) {
                    // do not reuse headers from any broken fetch
                    return;
                }
                String previousValue = (String) history[0].get(sourceHeader);
                if (previousValue != null) {
                    request.setHeader(targetHeader, previousValue);
                }
            } catch (RuntimeException e) {
                // for absent key, bad index, etc. just do nothing
            }
        }
    }

    protected void configureRequest() {
        if (fetcher.getIgnoreCookies()) {
            requestConfigBuilder.setCookieSpec(CookieSpecs.IGNORE_COOKIES);
        } else {
            requestConfigBuilder.setCookieSpec(CookieSpecs.BROWSER_COMPATIBILITY);
        }

        requestConfigBuilder.setConnectionRequestTimeout(fetcher.getSoTimeoutMs());
        requestConfigBuilder.setConnectTimeout(fetcher.getSoTimeoutMs());

        /*
         * XXX This socket timeout seems to be ignored. The one on the
         * socketConfig on the PoolingHttpClientConnectionManager in the
         * HttpClientBuilder is respected.
         */
        requestConfigBuilder.setSocketTimeout(fetcher.getSoTimeoutMs());        

        // local bind address
        String addressString = (String) fetcher.getAttributeEither(curi, FetchHTTP.HTTP_BIND_ADDRESS);
        if (StringUtils.isNotEmpty(addressString)) {
            try {
                InetAddress localAddress = InetAddress.getByName(addressString);
                requestConfigBuilder.setLocalAddress(localAddress);
            } catch (UnknownHostException e) {
                // Convert all to RuntimeException so get an exception out
                // if initialization fails.
                throw new RuntimeException("failed to resolve configured http bind address " + addressString, e);
            }
        }
    }
    
    /**
     * Add credentials if any to passed <code>method</code>.
     * 
     * Do credential handling. Credentials are in two places. 1. Credentials
     * that succeeded are added to the CrawlServer (Or rather, avatars for
     * credentials are whats added because its not safe to keep around
     * references to credentials). 2. Credentials to be tried are in the curi.
     * Returns true if found credentials to be tried.
     * 
     * @param curi
     *            Current CrawlURI.
     * @param request 
     * @param targetHost 
     * @param context
     *            The context to add credentials to.
     * @return True if prepopulated <code>method</code> with credentials AND
     *         the credentials came from the <code>curi</code>, not from the
     *         CrawlServer. The former is special in that if the
     *         <code>curi</curi> credentials
     * succeed, then the caller needs to promote them from the CrawlURI to the
     * CrawlServer so they are available for all subsequent CrawlURIs on this
     * server.
     */
    protected boolean populateTargetCredential() {
        // First look at the server avatars. Add any that are to be volunteered
        // on every request (e.g. RFC2617 credentials). Every time creds will
        // return true when we call 'isEveryTime().
        String serverKey;
        try {
            serverKey = CrawlServer.getServerKey(curi.getUURI());
        } catch (URIException e) {
            return false;
        }
        CrawlServer server = fetcher.getServerCache().getServerFor(serverKey);
        if (server.hasCredentials()) {
            for (Credential c: server.getCredentials()) {
                if (c.isEveryTime()) {
                    if (c instanceof HttpAuthenticationCredential) {
                        HttpAuthenticationCredential cred = (HttpAuthenticationCredential) c;
                        AuthScheme authScheme = fetcher.chooseAuthScheme(server.getHttpAuthChallenges(), HttpHeaders.WWW_AUTHENTICATE);
                        populateHttpCredential(targetHost, authScheme, cred.getLogin(), cred.getPassword());
                    } else {
                        populateHtmlFormCredential((HtmlFormCredential) c);
                    }
                }
            }
        }

        boolean result = false;

        // Now look in the curi. The Curi will have credentials loaded either
        // by the handle401 method if its a rfc2617 or it'll have been set into
        // the curi by the preconditionenforcer as this login uri came through.
        for (Credential c: curi.getCredentials()) {
            if (c instanceof HttpAuthenticationCredential) {
                HttpAuthenticationCredential cred = (HttpAuthenticationCredential) c;
                AuthScheme authScheme = fetcher.chooseAuthScheme(curi.getHttpAuthChallenges(), HttpHeaders.WWW_AUTHENTICATE);
                populateHttpCredential(targetHost, authScheme, cred.getLogin(), cred.getPassword());
                result = true;
            } else {
                result = populateHtmlFormCredential((HtmlFormCredential) c);
            }
        }

        return result;
    }
    
    protected void populateHttpProxyCredential() {
        String user = (String) fetcher.getAttributeEither(curi, "httpProxyUser");
        String password = (String) fetcher.getAttributeEither(curi, "httpProxyPassword");
        
        @SuppressWarnings("unchecked")
        Map<String,String> challenges = (Map<String, String>) fetcher.getKeyedProperties().get("proxyAuthChallenges");
        
        if (proxyHost != null && challenges != null && StringUtils.isNotEmpty(user)) {
            AuthScheme authScheme = fetcher.chooseAuthScheme(challenges, HttpHeaders.PROXY_AUTHENTICATE);
            populateHttpCredential(proxyHost, authScheme, user, password);
        }
    }
    
    protected boolean populateHtmlFormCredential(HtmlFormCredential cred) {
        if (cred.getFormItems() == null || cred.getFormItems().size() <= 0) {
            logger.severe("No form items for " + curi);
            return false;
        }
        
        List<NameValuePair> formParams = new ArrayList<NameValuePair>();
        for (Entry<String, String> n: cred.getFormItems().entrySet()) {
            formParams.add(new BasicNameValuePair(n.getKey(), n.getValue()));
        }

        // XXX should it get charset from somewhere?
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formParams, HTTP.DEF_CONTENT_CHARSET);
        HttpEntityEnclosingRequest entityEnclosingRequest = (HttpEntityEnclosingRequest) request;
        entityEnclosingRequest.setEntity(entity);

        return true;
    }
    
    // http auth credential, either for proxy or target host
    protected void populateHttpCredential(HttpHost host, AuthScheme authScheme, String user, String password) {
        UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user, password);
        
        AuthCache authCache = httpClientContext.getAuthCache();
        if (authCache == null) {
            authCache = new BasicAuthCache();
            httpClientContext.setAuthCache(authCache);
        }
        authCache.put(host, authScheme);

        if (httpClientContext.getCredentialsProvider() == null) {
            httpClientContext.setCredentialsProvider(new BasicCredentialsProvider());
        }
        httpClientContext.getCredentialsProvider().setCredentials(new AuthScope(host), credentials);
    }
    
    protected void configureHttpClientBuilder() {
        String userAgent = curi.getUserAgent();
        if (userAgent == null) {
            userAgent = fetcher.getUserAgentProvider().getUserAgent();
        }
        httpClientBuilder.setUserAgent(userAgent);
        
        httpClientBuilder.setCookieStore(fetcher.getCookieStore());
        
        HttpClientConnectionManager connManager = buildConnectionManager();
        httpClientBuilder.setConnectionManager(connManager);
    }

    protected HttpClientConnectionManager buildConnectionManager() {
        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainSocketFactory.getSocketFactory())
                .register("https", new SSLSocketFactory(fetcher.sslContext(), new AllowAllHostnameVerifier()))
                .build();

        DefaultClientConnectionFactory connFactory = new DefaultClientConnectionFactory() {
            @Override
            protected SocketClientConnection create(CharsetDecoder chardecoder,
                    CharsetEncoder charencoder,
                    MessageConstraints messageConstraints) {
                return new RecordingSocketClientConnection(fetcher, 8 * 1024,
                        chardecoder, charencoder, messageConstraints, null,
                        null, null, DefaultHttpResponseParserFactory.INSTANCE,
                        RecordingSessionBufferFactory.INSTANCE, request, curi);
            }
        };

        DnsResolver dnsResolver = new ServerCacheResolver(fetcher.getServerCache());
        
        PoolingHttpClientConnectionManager connMan = new PoolingHttpClientConnectionManager(socketFactoryRegistry,
                connFactory, null, dnsResolver, -1, TimeUnit.MILLISECONDS);
        
        SocketConfig.Builder socketConfigBuilder = SocketConfig.custom();
        socketConfigBuilder.setSoTimeout(fetcher.getSoTimeoutMs());
        connMan.setDefaultSocketConfig(socketConfigBuilder.build());
        
        return connMan;
    }
    
    protected void initHttpClientBuilder() {
        httpClientBuilder = HttpClientBuilder.create();
        
        httpClientBuilder.setAuthSchemeRegistry(FetchHTTP.AUTH_SCHEME_REGISTRY);
        
        // we handle content compression manually
        httpClientBuilder.disableContentCompression();
        
        // we handle redirects manually
        httpClientBuilder.disableRedirectHandling();
    }
    
    public HttpResponse execute() throws ClientProtocolException, IOException {
        HttpClient httpClient = httpClientBuilder.build();
        
        RequestConfig requestConfig = requestConfigBuilder.build();
        httpClientContext.setRequestConfig(requestConfig);
        
        return httpClient.execute(targetHost, request, httpClientContext);
    }
}
