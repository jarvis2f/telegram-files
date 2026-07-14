package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class V1TorrentService {

    public static final String TORRENT_VERSION = "V1_PRIVATE";

    public static final int MIN_PIECE_LENGTH = 256 * 1024;

    public static final int MAX_PIECE_LENGTH = 16 * 1024 * 1024;

    public static final int MAX_TORRENT_BYTES = 2 * 1024 * 1024;

    private final Vertx vertx;

    public V1TorrentService(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    public Future<TorrentMetadata> create(
            Path content,
            String fileName,
            long expectedSize
    ) {
        Path normalized = Objects.requireNonNull(content, "content").toAbsolutePath().normalize();
        String safeName = validateName(fileName);
        if (expectedSize < 0) {
            return Future.failedFuture(new IllegalArgumentException("Torrent content size is invalid"));
        }
        return vertx.executeBlocking(() -> createBlocking(normalized, safeName, expectedSize), false);
    }

    public TorrentMetadata parseCanonical(
            String torrentBase64,
            String expectedInfoHash,
            String expectedName,
            long expectedSize
    ) {
        if (torrentBase64 == null || torrentBase64.isEmpty() || torrentBase64.length() % 4 != 0
            || !torrentBase64.matches("[A-Za-z0-9+/]*={0,2}")) {
            throw new IllegalArgumentException("Torrent metadata is not canonical Base64");
        }
        byte[] canonical;
        try {
            canonical = Base64.getDecoder().decode(torrentBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Torrent metadata is not valid Base64", exception);
        }
        if (canonical.length == 0 || canonical.length > MAX_TORRENT_BYTES
            || !Base64.getEncoder().encodeToString(canonical).equals(torrentBase64)) {
            throw new IllegalArgumentException("Torrent metadata size or Base64 form is invalid");
        }
        DecodedRoot decoded = new Decoder(canonical).decodeRoot();
        if (decoded.root().containsKey("announce") || decoded.root().containsKey("announce-list")) {
            throw new IllegalArgumentException("Canonical Torrent must not contain a Tracker credential");
        }
        Map<String, Object> info = dictionary(decoded.root().get("info"), "Torrent info is missing");
        if (integer(info.get("private"), "Torrent private flag is missing") != 1) {
            throw new IllegalArgumentException("Torrent must set private=1");
        }
        if (info.containsKey("files")) {
            throw new IllegalArgumentException("MVP accepts only single-file Torrents");
        }
        long fileSize = integer(info.get("length"), "Torrent file size is missing");
        if (expectedSize >= 0 && fileSize != expectedSize) {
            throw new IllegalArgumentException("Torrent file size does not match the task");
        }
        String name = validateName(utf8(bytes(info.get("name"), "Torrent name is missing")));
        if (expectedName != null && !expectedName.equals(name)) {
            throw new IllegalArgumentException("Torrent name does not match the task");
        }
        long rawPieceLength = integer(info.get("piece length"), "Torrent piece length is missing");
        if (rawPieceLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Torrent piece length is too large");
        }
        int pieceLength = (int) rawPieceLength;
        if (pieceLength != selectPieceLength(fileSize)) {
            throw new IllegalArgumentException("Torrent piece length is not canonical");
        }
        byte[] pieces = bytes(info.get("pieces"), "Torrent pieces are missing");
        int count = pieceCount(fileSize, pieceLength);
        if (pieces.length != count * 20) {
            throw new IllegalArgumentException("Torrent pieces do not match the file size");
        }
        String infoHash = hash("SHA-1", decoded.infoBytes());
        if (expectedInfoHash != null && !expectedInfoHash.equalsIgnoreCase(infoHash)) {
            throw new IllegalArgumentException("Torrent infoHash does not match the task");
        }
        return new TorrentMetadata(canonical, decoded.infoBytes(), infoHash, name, fileSize, pieceLength, count);
    }

    public TorrentMetadata parseCanonical(byte[] canonicalBytes, String expectedInfoHash) {
        if (canonicalBytes == null || canonicalBytes.length == 0) {
            throw new IllegalArgumentException("Torrent metadata is empty");
        }
        return parseCanonical(
                Base64.getEncoder().encodeToString(canonicalBytes),
                expectedInfoHash,
                null,
                -1
        );
    }

    public byte[] withTracker(TorrentMetadata metadata, URI trackerBaseUri, String credential) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(trackerBaseUri, "trackerBaseUri");
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{32,1024}")) {
            throw new IllegalArgumentException("Node Tracker credential is invalid");
        }
        String path = trackerBaseUri.getPath();
        if (trackerBaseUri.getHost() == null || trackerBaseUri.getUserInfo() != null
            || trackerBaseUri.getQuery() != null || trackerBaseUri.getFragment() != null
            || !("http".equalsIgnoreCase(trackerBaseUri.getScheme())
                 || "https".equalsIgnoreCase(trackerBaseUri.getScheme()))
            || path == null || !path.endsWith("/announce/")) {
            throw new IllegalArgumentException("Tracker base URL is invalid");
        }
        String announce = trackerBaseUri.toString() + credential;
        Map<String, byte[]> root = new LinkedHashMap<>();
        root.put("announce", byteString(announce.getBytes(StandardCharsets.UTF_8)));
        root.put("info", metadata.infoBytes());
        byte[] announced = dictionaryBytes(root);
        TorrentMetadata verified = parseAnnounced(announced, metadata);
        if (!verified.infoHashV1().equals(metadata.infoHashV1())) {
            throw new IllegalStateException("Tracker injection changed the Torrent infoHash");
        }
        return announced;
    }

    public static int selectPieceLength(long fileSize) {
        if (fileSize < 0) {
            throw new IllegalArgumentException("Torrent file size cannot be negative");
        }
        int pieceLength = MIN_PIECE_LENGTH;
        while (pieceLength < MAX_PIECE_LENGTH && pieceCount(fileSize, pieceLength) > 2048) {
            pieceLength *= 2;
        }
        return pieceLength;
    }

    public static String safeFileName(String proposed, String resourceId) {
        String value = proposed == null || proposed.isBlank() ? "shared-" + resourceId : proposed;
        return validateName(value);
    }

    private TorrentMetadata createBlocking(Path content, String name, long expectedSize) throws IOException {
        if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(content)
            || Files.size(content) != expectedSize) {
            throw new IOException("Torrent content must be a stable regular file of the expected size");
        }
        int pieceLength = selectPieceLength(expectedSize);
        ByteArrayOutputStream pieces = new ByteArrayOutputStream(pieceCount(expectedSize, pieceLength) * 20);
        byte[] buffer = new byte[pieceLength];
        try (InputStream input = Files.newInputStream(content)) {
            if (expectedSize == 0) {
                pieces.writeBytes(digest("SHA-1", new byte[0], 0));
            } else {
                int used = 0;
                int read;
                while ((read = input.read(buffer, used, buffer.length - used)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    used += read;
                    if (used == buffer.length) {
                        pieces.writeBytes(digest("SHA-1", buffer, used));
                        used = 0;
                    }
                }
                if (used > 0) {
                    pieces.writeBytes(digest("SHA-1", buffer, used));
                }
            }
        }
        Map<String, byte[]> info = new LinkedHashMap<>();
        info.put("length", integerBytes(expectedSize));
        info.put("name", byteString(name.getBytes(StandardCharsets.UTF_8)));
        info.put("piece length", integerBytes(pieceLength));
        info.put("pieces", byteString(pieces.toByteArray()));
        info.put("private", integerBytes(1));
        byte[] infoBytes = dictionaryBytes(info);
        byte[] canonical = dictionaryBytes(Map.of("info", infoBytes));
        return new TorrentMetadata(
                canonical,
                infoBytes,
                hash("SHA-1", infoBytes),
                name,
                expectedSize,
                pieceLength,
                pieceCount(expectedSize, pieceLength)
        );
    }

    private TorrentMetadata parseAnnounced(byte[] announced, TorrentMetadata expected) {
        DecodedRoot decoded = new Decoder(announced).decodeRoot();
        if (!decoded.root().containsKey("announce")) {
            throw new IllegalArgumentException("Announced Torrent is missing its Tracker URL");
        }
        return new TorrentMetadata(
                expected.canonicalBytes(), decoded.infoBytes(), hash("SHA-1", decoded.infoBytes()),
                expected.name(), expected.fileSize(), expected.pieceLength(), expected.pieceCount()
        );
    }

    private static int pieceCount(long fileSize, int pieceLength) {
        long count = fileSize == 0 ? 1 : (fileSize + pieceLength - 1) / pieceLength;
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Torrent has too many pieces");
        }
        return (int) count;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
            || name.matches("^[A-Za-z]:.*")
            || name.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Torrent file name is unsafe");
        }
        return name;
    }

    private static byte[] dictionaryBytes(Map<String, byte[]> values) {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8),
                V1TorrentService::compareBytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('d');
        for (Map.Entry<String, byte[]> entry : entries) {
            output.writeBytes(byteString(entry.getKey().getBytes(StandardCharsets.UTF_8)));
            output.writeBytes(entry.getValue());
        }
        output.write('e');
        return output.toByteArray();
    }

    private static byte[] byteString(byte[] bytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        output.write(':');
        output.writeBytes(bytes);
        return output.toByteArray();
    }

    private static byte[] integerBytes(long value) {
        return ("i" + value + "e").getBytes(StandardCharsets.US_ASCII);
    }

    private static int compareBytes(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right) ? 0 : java.util.Arrays.compareUnsigned(left, right);
    }

    private static byte[] digest(String algorithm, byte[] bytes, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(bytes, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static String hash(String algorithm, byte[] bytes) {
        return HexFormat.of().formatHex(digest(algorithm, bytes, bytes.length));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dictionary(Object value, String message) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(message);
        }
        return (Map<String, Object>) value;
    }

    private static byte[] bytes(Object value, String message) {
        if (!(value instanceof byte[] result)) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static long integer(Object value, String message) {
        if (!(value instanceof Long result) || result < 0) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Torrent text is not valid UTF-8", exception);
        }
    }

    public record TorrentMetadata(
            byte[] canonicalBytes,
            byte[] infoBytes,
            String infoHashV1,
            String name,
            long fileSize,
            int pieceLength,
            int pieceCount
    ) {
        public TorrentMetadata {
            canonicalBytes = canonicalBytes.clone();
            infoBytes = infoBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        @Override
        public byte[] infoBytes() {
            return infoBytes.clone();
        }

        public String canonicalBase64() {
            return Base64.getEncoder().encodeToString(canonicalBytes);
        }
    }

    private record DecodedRoot(Map<String, Object> root, byte[] infoBytes) {
    }

    private static final class Decoder {
        private final byte[] bytes;

        private int offset;

        private Decoder(byte[] bytes) {
            this.bytes = bytes;
        }

        private DecodedRoot decodeRoot() {
            if (take() != 'd') {
                throw new IllegalArgumentException("Torrent root must be a dictionary");
            }
            Map<String, Object> root = new LinkedHashMap<>();
            byte[] previous = null;
            byte[] info = null;
            while (peek() != 'e') {
                byte[] keyBytes = decodeBytes();
                if (previous != null && compareBytes(previous, keyBytes) >= 0) {
                    throw new IllegalArgumentException("Bencode dictionary keys are not canonical");
                }
                previous = keyBytes;
                String key = utf8(keyBytes);
                int start = offset;
                Object value = decodeValue(1);
                if ("info".equals(key)) {
                    info = java.util.Arrays.copyOfRange(bytes, start, offset);
                }
                root.put(key, value);
            }
            offset++;
            if (offset != bytes.length || info == null) {
                throw new IllegalArgumentException("Torrent metadata is truncated or has trailing bytes");
            }
            return new DecodedRoot(root, info);
        }

        private Object decodeValue(int depth) {
            if (depth > 16) {
                throw new IllegalArgumentException("Bencode nesting is too deep");
            }
            int prefix = peek();
            if (prefix >= '0' && prefix <= '9') {
                return decodeBytes();
            }
            if (prefix == 'i') {
                return decodeInteger();
            }
            if (prefix == 'l') {
                offset++;
                List<Object> values = new ArrayList<>();
                while (peek() != 'e') {
                    values.add(decodeValue(depth + 1));
                }
                offset++;
                return values;
            }
            if (prefix == 'd') {
                offset++;
                Map<String, Object> values = new LinkedHashMap<>();
                byte[] previous = null;
                while (peek() != 'e') {
                    byte[] keyBytes = decodeBytes();
                    if (previous != null && compareBytes(previous, keyBytes) >= 0) {
                        throw new IllegalArgumentException("Bencode dictionary keys are not canonical");
                    }
                    previous = keyBytes;
                    values.put(utf8(keyBytes), decodeValue(depth + 1));
                }
                offset++;
                return values;
            }
            throw new IllegalArgumentException("Bencode token is invalid");
        }

        private long decodeInteger() {
            offset++;
            int end = indexOf((byte) 'e', offset);
            if (end < 0) {
                throw new IllegalArgumentException("Bencode integer is unterminated");
            }
            String raw = new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
            if (!raw.matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException("Bencode integer is not canonical");
            }
            offset = end + 1;
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Bencode integer is too large", exception);
            }
        }

        private byte[] decodeBytes() {
            int colon = indexOf((byte) ':', offset);
            if (colon < 0 || colon - offset > 9) {
                throw new IllegalArgumentException("Bencode byte string length is invalid");
            }
            String raw = new String(bytes, offset, colon - offset, StandardCharsets.US_ASCII);
            if (!raw.matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException("Bencode byte string length is not canonical");
            }
            int length;
            try {
                length = Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Bencode byte string is too large", exception);
            }
            int start = colon + 1;
            int end = start + length;
            if (end < start || end > bytes.length) {
                throw new IllegalArgumentException("Bencode byte string is truncated");
            }
            offset = end;
            return java.util.Arrays.copyOfRange(bytes, start, end);
        }

        private int indexOf(byte value, int start) {
            for (int index = start; index < bytes.length; index++) {
                if (bytes[index] == value) {
                    return index;
                }
            }
            return -1;
        }

        private int peek() {
            if (offset >= bytes.length) {
                throw new IllegalArgumentException("Bencode value is truncated");
            }
            return bytes[offset] & 0xff;
        }

        private int take() {
            int value = peek();
            offset++;
            return value;
        }
    }
}
