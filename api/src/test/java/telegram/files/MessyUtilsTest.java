package telegram.files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class MessyUtilsTest {

    private File smallFile;

    private File largeFile;

    @BeforeEach
    void setUp() throws Exception {
        smallFile = new File("small_test_file.txt");
        try (FileWriter writer = new FileWriter(smallFile)) {
            writer.write("Hello, this is a small test file!");
        }

        largeFile = new File("large_test_file.txt");
        try (FileWriter writer = new FileWriter(largeFile)) {
            for (int i = 0; i < 1_000_000; i++) {
                writer.write("This is a large test file for MD5 computation.\n");
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (smallFile.exists()) {
            smallFile.delete();
        }
        if (largeFile.exists()) {
            largeFile.delete();
        }
    }

    @Test
    void testCalculateFileMD5ForSmallFile() throws Exception {
        String md5 = MessyUtils.calculateFileMD5(smallFile);

        String expectedMd5 = calculateExpectedMD5(smallFile);
        assertEquals(expectedMd5, md5, "MD5 hash for the small file does not match!");
    }

    @Test
    void testCalculateFileMD5ForLargeFile() throws Exception {
        String md5 = MessyUtils.calculateFileMD5(largeFile);

        String expectedMd5 = calculateExpectedMD5(largeFile);
        assertEquals(expectedMd5, md5, "MD5 hash for the large file does not match!");
    }

    @Test
    void testCalculateFileMD5ForNonExistentFile() {
        File nonExistentFile = new File("non_existent_file.txt");
        String md5 = MessyUtils.calculateFileMD5(nonExistentFile);

        assertNull(md5, "MD5 hash for non-existent file should be null!");
    }

    @Test
    void testCompareFilesMD5() {
        assertTrue(MessyUtils.compareFilesMD5(smallFile, smallFile), "MD5 hashes for the same file should match!");
        assertFalse(MessyUtils.compareFilesMD5(smallFile, largeFile), "MD5 hashes for different files should not match!");
    }

    @Test
    void testCompareFilesMD5ForMissingFile() {
        File missingFile = new File("missing.txt");
        assertFalse(MessyUtils.compareFilesMD5(smallFile, missingFile),
                "MD5 comparison should return false if one of the files is missing!");
    }

    private String calculateExpectedMD5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] md5Bytes = md.digest(fileBytes);

        StringBuilder hexString = new StringBuilder();
        for (byte b : md5Bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}
