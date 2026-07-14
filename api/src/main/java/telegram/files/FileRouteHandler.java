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
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
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

            // Access fileSystem once here to be safe
            FileSystem fs = context.vertx().fileSystem();
            sendStatic(context, fs, path, mimeType);
        }
    }

    private void sendStatic(RoutingContext context, FileSystem fileSystem, String path, String mimeType) {
        // verify if the file exists
        fileSystem
                .exists(path)
                .onFailure(err -> {
                    if (!context.request().isEnded()) {
                        context.request().resume();
                    }
                    context.fail(err);
                })
                .onSuccess(exists -> {
                    if (!exists) {
                        if (!context.request().isEnded()) {
                            context.request().resume();
                        }
                        context.next();
                        return;
                    }

                    // Need to read the props from the filesystem
                    fileSystem.props(path)
                            .onSuccess(props -> {
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
                            })
                            .onFailure(err -> {
                                if (!context.request().isEnded()) {
                                    context.request().resume();
                                }
                                context.fail(err);
                            });
                });
    }

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private void sendFile(RoutingContext context, String file, String contentType, FileProps fileProps) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = context.response();

        Range range = null;

        if (response.closed())
            return;

        response.exceptionHandler(err -> {
            if (isClientAbort(err)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Client closed file response early: " + err.getMessage());
                }
                return;
            }
            LOG.warn("File response failed", err);
        });

        // check if the client is making a range request
        String rangeHeader = request.getHeader("Range");

        if (rangeHeader != null) {
            range = parseRange(rangeHeader, fileProps.size());
            if (range == null) {
                response.putHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + fileProps.size());
                response.setStatusCode(REQUESTED_RANGE_NOT_SATISFIABLE.code()).end();
                return;
            }
        }

        // notify client we support range requests
        MultiMap headers = response.headers();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        putContentType(response, contentType);

        if (range != null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + fileProps.size());
            headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(range.length()));
            response.setStatusCode(PARTIAL_CONTENT.code());
        } else {
            headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(fileProps.size()));
        }

        // send the content length even for HEAD requests
        if (request.method() == HttpMethod.HEAD) {
            response.end();
        } else {
            if (range != null) {
                response.sendFile(file, range.start(), range.length())
                        .onFailure(err -> handleSendFileFailure(context, err));
            } else {
                response.sendFile(file)
                        .onFailure(err -> handleSendFileFailure(context, err));
            }
        }
    }

    private static void putContentType(HttpServerResponse response, String contentType) {
        if (contentType == null) {
            return;
        }
        if (contentType.startsWith("text")) {
            response.putHeader(HttpHeaders.CONTENT_TYPE, contentType + ";charset=" + Charset.defaultCharset().name());
        } else {
            response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        }
    }

    private static Range parseRange(String header, long fileSize) {
        Matcher matcher = RANGE.matcher(header);
        if (!matcher.matches() || fileSize <= 0) {
            return null;
        }

        String startPart = matcher.group(1);
        String endPart = matcher.group(2);
        if ((startPart == null || startPart.isEmpty()) && (endPart == null || endPart.isEmpty())) {
            return null;
        }

        try {
            long start;
            long end;
            if (startPart == null || startPart.isEmpty()) {
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, fileSize - suffixLength);
                end = fileSize - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart == null || endPart.isEmpty()
                        ? fileSize - 1
                        : Math.min(fileSize - 1, Long.parseLong(endPart));
            }

            if (start < 0 || start >= fileSize || end < start) {
                return null;
            }
            return new Range(start, end);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void handleSendFileFailure(RoutingContext context, Throwable err) {
        if (!context.request().isEnded()) {
            context.request().resume();
        }
        if (isClientAbort(err)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Client aborted file transfer: " + err.getMessage());
            }
            return;
        }
        LOG.warn("Failed to send file", err);
    }

    private static boolean isClientAbort(Throwable err) {
        Throwable cursor = err;
        while (cursor != null) {
            if (cursor instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            if (cursor instanceof IOException && cursor.getMessage() != null) {
                String message = cursor.getMessage();
                if (message.contains("Broken pipe") || message.contains("Connection reset by peer")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private record Range(long start, long end) {
        long length() {
            return end + 1 - start;
        }
    }

}
