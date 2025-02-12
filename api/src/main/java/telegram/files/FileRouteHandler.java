package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.MultiMap;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.impl.URIDecoder;
import io.vertx.ext.web.RoutingContext;

import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpResponseStatus.PARTIAL_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE;

public class FileRouteHandler {
    private static final Log LOG = LogFactory.get();

    public void handle(RoutingContext context, String path, String mimeType) {
        HttpServerRequest request = context.request();

        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            if (LOG.isTraceEnabled())
                LOG.trace("Not GET or HEAD so ignoring request");
            context.next();
        } else {
            if (!request.isEnded()) {
                request.pause();
            }
            // decode URL path
            String uriDecodedPath = URIDecoder.decodeURIComponent(context.normalizedPath(), false);
            // if the normalized path is null it cannot be resolved
            if (uriDecodedPath == null) {
                LOG.warn("Invalid path: " + context.request().path());
                context.next();
                return;
            }
            // Access fileSystem once here to be safe
            FileSystem fs = context.vertx().fileSystem();

            sendStatic(context, fs, path, mimeType);
        }
    }

    private void sendStatic(RoutingContext context, FileSystem fileSystem, String path, String mimeType) {
        // verify if the file exists
        fileSystem
                .exists(path, exists -> {
                    if (exists.failed()) {
                        if (!context.request().isEnded()) {
                            context.request().resume();
                        }
                        context.fail(exists.cause());
                        return;
                    }

                    // file does not exist, continue...
                    if (!exists.result()) {
                        if (!context.request().isEnded()) {
                            context.request().resume();
                        }
                        context.next();
                        return;
                    }

                    // Need to read the props from the filesystem
                    fileSystem.props(path, res -> {
                        if (res.succeeded()) {
                            FileProps props = res.result();
                            if (props == null) {
                                if (!context.request().isEnded()) {
                                    context.request().resume();
                                }
                                context.next();
                            } else if (props.isDirectory()) {
                                context.next();
                            } else {
                                sendFile(context, path, mimeType, props);
                            }
                        } else {
                            if (!context.request().isEnded()) {
                                context.request().resume();
                            }
                            context.fail(res.cause());
                        }
                    });
                });
    }

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d+)-(\\d*)$");

    private void sendFile(RoutingContext context, String file, String contentType, FileProps fileProps) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = context.response();

        Long offset = null;
        long end;
        MultiMap headers;

        if (response.closed())
            return;

        // check if the client is making a range request
        String range = request.getHeader("Range");
        // end byte is length - 1
        end = fileProps.size() - 1;

        if (range != null) {
            Matcher m = RANGE.matcher(range);
            if (m.matches()) {
                try {
                    String part = m.group(1);
                    // offset cannot be empty
                    offset = Long.parseLong(part);
                    // offset must fall inside the limits of the file
                    if (offset < 0 || offset >= fileProps.size()) {
                        throw new IndexOutOfBoundsException();
                    }
                    // length can be empty
                    part = m.group(2);
                    if (part != null && !part.isEmpty()) {
                        // ranges are inclusive
                        end = Math.min(end, Long.parseLong(part));
                        // end offset must not be smaller than start offset
                        if (end < offset) {
                            throw new IndexOutOfBoundsException();
                        }
                    }
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    context.response().putHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + fileProps.size());
                    if (!context.request().isEnded()) {
                        context.request().resume();
                    }
                    context.fail(REQUESTED_RANGE_NOT_SATISFIABLE.code());
                    return;
                }
            }
        }

        // notify client we support range requests
        headers = response.headers();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // send the content length even for HEAD requests
        headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(end + 1 - (offset == null ? 0 : offset)));

        if (request.method() == HttpMethod.HEAD) {
            response.end();
        } else {
            if (offset != null) {
                // must return content range
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + offset + "-" + end + "/" + fileProps.size());
                // return a partial response
                response.setStatusCode(PARTIAL_CONTENT.code());

                final long finalOffset = offset;
                final long finalLength = end + 1 - offset;
                if (contentType != null) {
                    if (contentType.startsWith("text")) {
                        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType + ";charset=" + Charset.defaultCharset().name());
                    } else {
                        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
                    }
                }

                response.sendFile(file, finalOffset, finalLength, res2 -> {
                    if (res2.failed()) {
                        if (!context.request().isEnded()) {
                            context.request().resume();
                        }
                        context.fail(res2.cause());
                    }
                });
            } else {
                // guess content type
                if (contentType != null) {
                    if (contentType.startsWith("text")) {
                        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType + ";charset=" + Charset.defaultCharset().name());
                    } else {
                        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
                    }
                }

                response.sendFile(file, res2 -> {
                    if (res2.failed()) {
                        if (!context.request().isEnded()) {
                            context.request().resume();
                        }
                        context.fail(res2.cause());
                    }
                });
            }
        }
    }

}
