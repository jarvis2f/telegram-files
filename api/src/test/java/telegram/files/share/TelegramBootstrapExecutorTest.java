package telegram.files.share;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import telegram.files.repository.ShareSourceRecord;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramBootstrapExecutorTest {

    @Test
    void refreshesTheDownloadFileFromThePersistedMessageIdentity() {
        ShareSourceRecord source = mock(ShareSourceRecord.class);
        when(source.fileUniqueId()).thenReturn("stable-file-id");
        when(source.fileSize()).thenReturn(1024L);

        TdApi.File currentFile = new TdApi.File();
        currentFile.id = 99;
        currentFile.expectedSize = 1024;
        currentFile.remote = new TdApi.RemoteFile();
        currentFile.remote.uniqueId = "stable-file-id";

        TdApi.Audio audio = new TdApi.Audio();
        audio.audio = currentFile;
        TdApi.MessageAudio content = new TdApi.MessageAudio();
        content.audio = audio;
        TdApi.Message message = new TdApi.Message();
        message.content = content;

        var result = TelegramBootstrapExecutor.validateMessageFile(source, message);

        assertTrue(result.succeeded());
        assertSame(currentFile, result.result());
    }
}
