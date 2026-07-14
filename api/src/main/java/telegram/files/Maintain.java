package telegram.files;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import telegram.files.maintains.AdminMaintenanceCli;
import telegram.files.maintains.AlbumCaptionMaintainVerticle;
import telegram.files.maintains.MaintainVerticle;
import telegram.files.maintains.ThumbnailMaintainVerticle;

public class Maintain {
    static {
        LogFactory.setCurrentLogFactory(new Config.JDKLogFactory());
    }

    private static final Log log = LogFactory.get();

    private static final Vertx vertx = Vertx.vertx();

    public static void main(String[] args) {
        if (ArrayUtil.isEmpty(args)) {
            System.out.println("Missing maintain name");
            System.out.println("Usage: java -cp api.jar telegram.files.Maintain <maintain-name>");
            System.out.println("Maintain names:");
            System.out.println("  album-caption");
            System.out.println("  thumbnail");
            System.out.println("  admin reset-password <username>");
            System.out.println("  admin apply-reset <username>");
            System.exit(1);
        }

        String maintainName = args[0];
        try {
            MaintainVerticle maintainVerticle = switch (maintainName) {
                case "album-caption" -> new AlbumCaptionMaintainVerticle();
                case "thumbnail" -> new ThumbnailMaintainVerticle();
                case "admin" -> new AdminMaintenanceCli(args, System.console(), System.out);
                default -> {
                    System.out.println("Unknown maintain name: " + maintainName);
                    System.exit(1);
                    yield null;
                }
            };

            vertx.eventBus().consumer(EventEnum.MAINTAIN.address(), message -> {
                JsonObject result = (JsonObject) message.body();
                int exitCode = result != null && result.getBoolean("success", false) ? 0 : 1;
                if (exitCode != 0 && result != null) {
                    log.error("Maintain failed: {}", result.getString("message"));
                }
                vertx.undeploy(maintainVerticle.deploymentID())
                        .onSuccess(_ -> {
                            log.trace("Undeploy maintain verticle success");
                            System.exit(exitCode);
                        })
                        .onFailure(err -> {
                            log.error("Failed to undeploy maintain verticle", err);
                            System.exit(1);
                        });
            });

            MessyUtils.await(vertx.deployVerticle(
                    maintainVerticle,
                    Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS
            ).onFailure(err -> {
                log.error("Failed to deploy %s maintain verticle".formatted(maintainName), err);
                System.exit(1);
            }));
        } catch (Exception e) {
            log.error("Failed to maintain", e);
            System.exit(1);
        }
    }

}
