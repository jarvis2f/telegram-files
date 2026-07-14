package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

        assertSame(gateway, verticle.client);
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

}
