package com.vertx.commons.deploy;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;

/**
 * @author Luiz Schmidt
 */
@Log4j2
public class Deployer {

  private static final Long DEFAULT_TIMEOUT = 25000L;
  private Vertx vertx;

  public Deployer(Vertx vertx) {
    this.vertx = vertx;
  }

  /**
   * Deploy a verticle without options
   * 
   * @param name
   * @return Future
   */
  public Future<Void> deploy(String name) {
    return deploy(name, new DeploymentOptions(), DEFAULT_TIMEOUT);
  }

  /**
   * Deploy verticle with a JsonObject options
   * 
   * @param vertx
   * @param name
   * @param jsonOptions
   * @return Future
   */
  public Future<Void> deploy(String name, JsonObject jsonOptions) {
    DeploymentOptions deploymentOptions = new DeploymentOptions(jsonOptions);

    if (deploymentOptions.getConfig() == null)
      deploymentOptions.setConfig(new JsonObject());

    return deploy(name, deploymentOptions, deploymentOptions.getConfig().getLong("timeout", DEFAULT_TIMEOUT));
  }

  /**
   * Deploy Verticle with a DeploymentOptions options
   * 
   * @param name
   * @param options
   * @param timeout
   * @return
   */
  public Future<Void> deploy(String name, DeploymentOptions options, long timeout) {
    Thread.currentThread().setName("deploy");
    log.info(String.format("Deploying %s verticle.", printName(name)));

    Promise<String> deployPromise = Promise.promise();
    vertx.deployVerticle(name, options, deployPromise);
    vertx.setTimer(timeout, timer -> deployPromise
        .tryFail("Timeout on deploying verticle, be sure to call complete() after everything is fines"));

    Promise<Void> promise = Promise.promise();
    deployPromise.future().onSuccess(id -> {
      log.info("Success to Deploy Verticle: ".concat(printName(name)));
      promise.complete();
    }).onFailure(cause -> {
      log.error("Failed to Deploy Verticle " + printName(name) + ", reason: " + cause.getMessage(), cause);
      promise.fail(String.format("Failed to deploy: %s -> Cause: %s", name, cause.getMessage()));
    });

    return promise.future();
  }

  private static String printName(String name) {
    if (name == null) {
      return null;
    } else if (name.contains(".")) {
      return name.substring(name.lastIndexOf('.') + 1);
    } else {
      return name;
    }
  }
}