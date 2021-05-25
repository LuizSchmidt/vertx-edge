/*
 * Vert.x Edge, open source.
 * Copyright (C) 2020-2021 Vert.x Edge
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vertx.edge.deploy;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.stream.Collectors;

import com.vertx.edge.deploy.config.PeriodicConfigurationStrategy;
import com.vertx.edge.deploy.service.ServiceDiscoveryVerticle;
import com.vertx.edge.utils.CompositeFutureBuilder;
import com.vertx.edge.utils.Timer;
import com.vertx.edge.verticle.ServiceInjectionVerticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;

/**
 * @author Luiz Schmidt
 */
@Log4j2
public final class DeployerVerticle extends AbstractVerticle {

  public static final String BASE_PACKAGE = "registryPackages";
  private static final String DIR_BANNER = "banner.txt";
  private static final String CONFIG = "config";
  private static final String DEPLOY_OPTIONS = "deployOptions";
  private static final String VERTICLE_WEB_CLIENT = "com.vertx.edge.web.client.verticle.WebClientVerticle";
  private static final String VERTICLE_WEB_SERVER = "com.vertx.edge.web.server.verticle.WebServerVerticle";
  private Deployer deployer;

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    Timer timer = Timer.start();
    String threadName = Thread.currentThread().getName();
    Thread.currentThread().setName("deploying");

    StartInfo.print(this.bannerFile());

    this.deployer = new Deployer(vertx);
    PeriodicConfigurationStrategy.create(vertx).load().onFailure(startPromise::fail).onSuccess(config -> {
      String registryPackages = config.getString(BASE_PACKAGE);
      RegisterCodec.registerAll(vertx, registryPackages);

      startDepoy(config, registryPackages).onSuccess(p -> {
        log.info("All Verticles are deployed successful");
        log.info("Elapsed time to deploy: {}", timer);
        log.info("Application started!");
        Thread.currentThread().setName(threadName);
      }).onFailure(startPromise::fail);
    });
  }

  /**
   * Show the banner.txt file in console
   * @return
   */
  private String bannerFile() {
    if (vertx.fileSystem().existsBlocking(DIR_BANNER))
      return vertx.fileSystem().readFileBlocking(DIR_BANNER).toString();
    return null;
  }

  /**
   * Start the deploy
   * @param config
   * @param registryPackages
   * @return
   */
  private Future<CompositeFuture> startDepoy(JsonObject config, String registryPackages) {
    Promise<CompositeFuture> promise = Promise.promise();

    JsonObject services = config.getJsonObject("services");
    JsonArray phases = config.getJsonArray("phases");
    JsonObject webServer = config.getJsonObject("web-server");
    JsonObject webClients = config.getJsonObject("web-client");
    JsonObject injections = config.getJsonObject("injections");

    CompositeFutureBuilder.create()
      .add(this.deployServices(services, registryPackages))
      .add(this.deployWebClient(webClients))
      .all()
        .compose(v -> this.deployInjectedService(injections, registryPackages))
        .compose(v -> CompositeFutureBuilder.create()
          .add(this.deployWebServer(webServer, registryPackages))
          .add(this.deployPhases(phases)).all()
            .onSuccess(cr -> promise.complete())
            .onFailure(promise::fail));

    return promise.future();
  }

  /**
   * Deploy Verticle ServiceInjectionVerticle
   * @param config
   * @param registryPackages
   * @return
   */
  private Future<Void> deployInjectedService(JsonObject config, String registryPackages) {
    if (config == null) {
      config = new JsonObject();
    }

    JsonObject options = new JsonObject().put(CONFIG, new JsonObject().put("injections", config).put(BASE_PACKAGE,
        registryPackages));
    return this.deployer.deploy(ServiceInjectionVerticle.class.getName(), options);
  }

  /**
   * Deploy Verticle ServiceDiscoveryVerticle
   * @param config
   * @param registryPackages
   * @return
   */
  private Future<Void> deployServices(JsonObject config, String registryPackages) {
    if (config == null) {
      log.info("The configuration \"services\" was not found, no one service will be discovered.");
      return Future.succeededFuture();
    }

    JsonObject options = new JsonObject().put(CONFIG, new JsonObject().put("services", config).put(BASE_PACKAGE,
        registryPackages));
    return this.deployer.deploy(ServiceDiscoveryVerticle.class.getName(), options);
  }

  /**
   * Deploy Verticle WebServerVerticle
   * @param config
   * @param registryPackages
   * @return
   */
  private Future<Void> deployWebServer(JsonObject config, String registryPackages) {
    if (config == null) {
      log.info("The configuration \"web-server\" was not found, no one @Operation will inject.");
      return Future.succeededFuture();
    }

    JsonObject options = config.getJsonObject(DEPLOY_OPTIONS, new JsonObject());
    config.remove(DEPLOY_OPTIONS);

    try {
      return this.deployer.deploy(Class.forName(VERTICLE_WEB_SERVER).getName(),
          options.put(CONFIG, config.put(BASE_PACKAGE, registryPackages)));
    } catch (ClassNotFoundException e) {
      log.trace(e);
      return Future.failedFuture("In the configuration file the WebServer field was found, but the package is missing. "
          + "Import the library -> groupId: com.vertx.edge | artifactId: web-server");
    }
  }

  /**
   * Deploy Verticle WebClientVerticle
   * @param config
   * @return
   */
  private Future<Void> deployWebClient(JsonObject config) {
    if (config == null) {
      log.info("The configuration \"web-client\" was not found, no one WebClient will be discovered.");
      return Future.succeededFuture();
    }

    JsonObject options = config.getJsonObject(DEPLOY_OPTIONS, new JsonObject());
    config.remove(DEPLOY_OPTIONS);

    try {
      return this.deployer.deploy(Class.forName(VERTICLE_WEB_CLIENT).getName(), options.put(CONFIG, config));
    } catch (ClassNotFoundException e) {
      log.trace(e);
      return Future.failedFuture("In the configuration file the WebClient field was found, but the package is missing. "
          + "Import the library -> groupId: com.vertx.edge | artifactId: web-client");
    }
  }

  /**
   * Deploy Phases
   * @param config
   * @return
   */
  private Future<Void> deployPhases(JsonArray config) {
    if (config == null || config.isEmpty()) {
      log.info("The configuration \"phases\" was not found, no one personalized verticle will be up.");
      return Future.succeededFuture();
    }

    Promise<Void> promise = Promise.promise();
    LinkedList<Phase> phases = config.stream().map(JsonObject.class::cast).map(Phase::new)
        .collect(Collectors.toCollection(LinkedList::new));

    Iterator<Phase> it = phases.iterator();
    this.deployPhase(it, promise);
    return promise.future();
  }

  /**
   * Deploy each phase
   * @param it
   * @param handler
   */
  private void deployPhase(Iterator<Phase> it, Handler<AsyncResult<Void>> handler) {
    if (it.hasNext()) {
      it.next().deploy(vertx).onSuccess(v -> deployPhase(it, handler))
          .onFailure(cause -> handler.handle(Future.failedFuture(cause)));
    } else {
      handler.handle(Future.succeededFuture());
    }
  }
}
