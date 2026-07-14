package telegram.files;

@FunctionalInterface
public interface TelegramGatewayFactory {

    TelegramGateway create();

    static TelegramGatewayFactory tdlib() {
        return TelegramClient::new;
    }
}
