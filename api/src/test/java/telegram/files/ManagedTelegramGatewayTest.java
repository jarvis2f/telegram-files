package telegram.files;

import io.vertx.core.Promise;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedTelegramGatewayTest {

    @Test
    void waitsForWakeAndTracksRequestUntilCompletion() {
        Promise<Void> awake = Promise.promise();
        ScriptedTelegramGateway delegate = new ScriptedTelegramGateway(_ -> new TdApi.OptionValueEmpty());
        AtomicInteger active = new AtomicInteger();
        ManagedTelegramGateway gateway = new ManagedTelegramGateway(
                awake::future,
                () -> delegate,
                active::incrementAndGet,
                active::decrementAndGet
        );

        var result = gateway.execute(new TdApi.GetOption("version"));

        assertFalse(result.isComplete());
        assertTrue(delegate.requests().isEmpty());
        assertEquals(1, active.get());

        awake.complete();

        assertTrue(result.succeeded());
        assertEquals(1, delegate.requests().size());
        assertEquals(0, active.get());
    }
}
