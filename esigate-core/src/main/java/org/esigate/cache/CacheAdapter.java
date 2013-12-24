/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.esigate.cache;

import java.io.IOException;
import java.sql.Date;
import java.util.Properties;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.client.cache.CacheResponseStatus;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.execchain.ClientExecChain;
import org.esigate.ConfigurationException;
import org.esigate.Parameters;
import org.esigate.events.EventManager;
import org.esigate.events.impl.FetchEvent;
import org.esigate.http.DateUtils;
import org.esigate.http.HttpClientRequestExecutor;

/**
 * This class is changes the behavior of the HttpCache by transforming the headers in the requests or response.
 * 
 * @author Francois-Xavier Bonnet
 * 
 */
public class CacheAdapter {
    protected static final String HTTP_ROUTE = "HTTP_ROUTE";
    private int staleIfError;
    private int staleWhileRevalidate;
    private int ttl;
    private boolean xCacheHeader;
    private boolean viaHeader;

    public void init(Properties properties) {
        staleIfError = Parameters.STALE_IF_ERROR.getValueInt(properties);
        staleWhileRevalidate = Parameters.STALE_WHILE_REVALIDATE.getValueInt(properties);
        int maxAsynchronousWorkers = Parameters.MAX_ASYNCHRONOUS_WORKERS.getValueInt(properties);
        if (staleWhileRevalidate > 0 && maxAsynchronousWorkers == 0) {
            throw new ConfigurationException("You must set a positive value for maxAsynchronousWorkers "
                    + "in order to enable background revalidation (staleWhileRevalidate)");
        }
        ttl = Parameters.TTL.getValueInt(properties);
        xCacheHeader = Parameters.X_CACHE_HEADER.getValueBoolean(properties);
        viaHeader = Parameters.VIA_HEADER.getValueBoolean(properties);
    }

    public ClientExecChain wrapCachingHttpClient(final ClientExecChain wrapped) {
        return new ClientExecChain() {

            /**
             * Removes client http cache directives like "Cache-control" and "Pragma". Users must not be able to bypass
             * the cache just by making a refresh in the browser. Generates X-cache header.
             * 
             */
            @Override
            public CloseableHttpResponse execute(HttpRoute route, HttpRequestWrapper request,
                    HttpClientContext context, HttpExecutionAware execAware) throws IOException, HttpException {

                // Switch route for the cache to generate the right cache key
                HttpHost virtualHost = context.getTargetHost();
                HttpRoute virtualRoute = new HttpRoute(virtualHost);
                // Save the real route to restore later
                context.setAttribute(HTTP_ROUTE, route);

                CloseableHttpResponse response = wrapped.execute(virtualRoute, request, context, execAware);

                // Remove previously added Cache-control header
                if (request.getRequestLine().getMethod().equalsIgnoreCase("GET")
                        && (staleWhileRevalidate > 0 || staleIfError > 0)) {
                    response.removeHeader(response.getLastHeader("Cache-control"));
                }
                // Add X-cache header
                if (xCacheHeader) {
                    if (context != null) {
                        CacheResponseStatus cacheResponseStatus = (CacheResponseStatus) context
                                .getAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS);
                        String xCacheString;
                        if (cacheResponseStatus.equals(CacheResponseStatus.CACHE_HIT)) {
                            xCacheString = "HIT";
                        } else if (cacheResponseStatus.equals(CacheResponseStatus.VALIDATED)) {
                            xCacheString = "VALIDATED";
                        } else {
                            xCacheString = "MISS";
                        }
                        xCacheString += " from " + route.getTargetHost().toHostString();
                        xCacheString += " (" + request.getRequestLine().getMethod() + " "
                                + request.getRequestLine().getUri() + ")";
                        response.addHeader("X-Cache", xCacheString);
                    }
                }

                // Remove Via header
                if (!viaHeader && response.containsHeader("Via")) {
                    response.removeHeaders("Via");
                }
                return response;
            }
        };
    }

    public ClientExecChain wrapBackendHttpClient(final EventManager eventManager, final ClientExecChain wrapped) {
        return new ClientExecChain() {

            private boolean isCacheableStatus(int statusCode) {
                return (statusCode == HttpStatus.SC_OK || statusCode == HttpStatus.SC_MOVED_PERMANENTLY
                        || statusCode == HttpStatus.SC_MOVED_TEMPORARILY || statusCode == HttpStatus.SC_NOT_FOUND
                        || statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                        || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE || statusCode == HttpStatus.SC_NOT_MODIFIED);
            }

            /**
             * Fire pre-fetch and post-fetch events Enables cache for all GET requests if cache ttl was forced to a
             * certain duration in the configuration. This is done even for non 200 return codes! This is a very
             * aggressive but efficient caching policy. Adds "stale-while-revalidate" and "stale-if-error" cache-control
             * directives depending on the configuration.
             */
            @Override
            public CloseableHttpResponse execute(HttpRoute route, HttpRequestWrapper request,
                    HttpClientContext context, HttpExecutionAware execAware) throws IOException, HttpException {
                // Restore real route
                HttpRoute realRoute = context.getAttribute(HTTP_ROUTE, HttpRoute.class);

                // In case we are bypassing the cache
                if (realRoute == null) {
                    realRoute = route;
                }

                // Create request event
                Boolean proxy = (Boolean) context.getAttribute(HttpClientRequestExecutor.PROXY);
                if (proxy == null) {
                    proxy = Boolean.FALSE;
                }
                FetchEvent fetchEvent = new FetchEvent(proxy);
                fetchEvent.httpResponse = null;
                fetchEvent.httpContext = context;
                fetchEvent.httpRequest = request;

                eventManager.fire(EventManager.EVENT_FETCH_PRE, fetchEvent);

                CloseableHttpResponse response = null;
                if (!fetchEvent.exit) {
                    response = wrapped.execute(realRoute, request, context, execAware);
                } else {
                    if (fetchEvent.httpResponse != null) {
                        response = new BasicCloseableHttpResponse(fetchEvent.httpResponse);
                    }
                }

                // TODO: returning null may be hard. However, this only happens if
                // an extension cancels the request. Need to think on the usecase.

                // Update the event and fire post event
                fetchEvent.httpResponse = response;
                eventManager.fire(EventManager.EVENT_FETCH_POST, fetchEvent);

                String method = request.getRequestLine().getMethod();
                int statusCode = response.getStatusLine().getStatusCode();

                // If ttl is set, force caching even for error pages
                if (ttl > 0 && method.equalsIgnoreCase("GET") && isCacheableStatus(statusCode)) {
                    response.removeHeaders("Date");
                    response.removeHeaders("Cache-control");
                    response.removeHeaders("Expires");
                    response.setHeader("Date", DateUtils.formatDate(new Date(System.currentTimeMillis())));
                    response.setHeader("Cache-control", "public, max-age=" + ttl);
                    response.setHeader("Expires",
                            DateUtils.formatDate(new Date(System.currentTimeMillis() + ((long) ttl) * 1000)));
                }
                if (request.getRequestLine().getMethod().equalsIgnoreCase("GET")) {
                    String cacheControlHeader = "";
                    if (staleWhileRevalidate > 0) {
                        cacheControlHeader += "stale-while-revalidate=" + staleWhileRevalidate;
                    }
                    if (staleIfError > 0) {
                        if (cacheControlHeader.length() > 0) {
                            cacheControlHeader += ",";
                        }
                        cacheControlHeader += "stale-if-error=" + staleIfError;
                    }
                    if (cacheControlHeader.length() > 0) {
                        response.addHeader("Cache-control", cacheControlHeader);
                    }
                }

                return response;
            }

        };
    }
}
