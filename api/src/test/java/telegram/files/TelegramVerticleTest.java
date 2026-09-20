package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import telegram.files.repository.TelegramRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TelegramVerticleTest {

    @AfterEach
    void resetFactory() {
        TelegramVerticles.resetTelegramGatewayFactory();
    }

    @Test
    void usesConfiguredGatewayAndForwardsAuthorizationUpdates() {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> new TdApi.Ok());
        TelegramVerticles.configureTelegramGatewayFactory(() -> gateway);

        TelegramVerticle verticle = TelegramVerticles.create("/tmp/account-fixture");
        verticle.initializeTelegramGateway();
        gateway.emit(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateClosing()));

        assertSame(gateway, verticle.tdlibClient());
        assertInstanceOf(TdApi.AuthorizationStateClosing.class, verticle.lastAuthorizationState);
        assertTrue(gateway.requests().isEmpty());
    }

    @Test
    void scriptedGatewayCanModelTdlibErrorsWithoutNativeTelegram() {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(
                _ -> new TdApi.Error(404, "fixture not found")
        );
        gateway.initialize(_ -> { }, _ -> { }, _ -> { });

        assertTrue(gateway.execute(new TdApi.GetMe()).failed());
        assertNull(gateway.execute(new TdApi.GetMe(), true).result());
        assertEquals(2, gateway.requests().size());
    }

    @Test
    void accountListingDoesNotQueryTdlibWhileAccountIsStarting() {
        ScriptedTelegramGateway gateway = new ScriptedTelegramGateway(_ -> {
            throw new AssertionError("account listing must not query TDLib while waking");
        });
        TelegramVerticle verticle = new TelegramVerticle(
                new TelegramRecord(42L, "test", "/tmp/account-fixture", null),
                () -> gateway
        );
        verticle.initializeTelegramGateway();
        verticle.authorized = true;

        var account = verticle.getTelegramAccount().result();

        assertEquals("42", account.getString("id"));
        assertTrue(account.getBoolean("sleeping"));
        assertTrue(gateway.requests().isEmpty());
    }

}
