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
package com.vertx.edge.deploy.service.secret;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * @author Luiz Schmidt
 */
public final class Secret {

  private Secret() {
    // Nothing to do
  }

  /**
   * Return an JsonObject with username and password
   * 
   * @param vertx
   * @param config
   * @return
   */
  public static Future<JsonObject> getUsernameAndPassword(Vertx vertx, JsonObject config) {
    SecretType type = chooseType(config);

    if (type == null) {
      return Future
          .failedFuture("Please enter the database authentication type. (eg. secret-file, basic-auth, no-auth)");
    }

    Promise<JsonObject> promise = Promise.promise();
    type.getUserAndPassword(vertx, config).onSuccess(secret -> promise.complete(buildJsonAuthFile(secret)))
        .onFailure(promise::fail);
    return promise.future();
  }

  private static SecretType chooseType(JsonObject config) {
    SecretType type = null;
    if (config.containsKey("secret-file")) {
      type = SecretType.SECRET_FILE;
    } else if (config.containsKey("basic-auth")) {
      type = SecretType.BASIC_AUTH;
    } else if (config.containsKey("no-auth")) {
      type = SecretType.NO_AUTH;
    }
    return type;
  }

  private static JsonObject buildJsonAuthFile(JsonObject secret) {
    return new JsonObject().put("user", secret.getString("user")).put("pass", secret.getString("pass"));
  }

  public static void clear(JsonObject config) {
    config.remove("user");
    config.remove("pass");
    config.remove("username");
    config.remove("password");
  }
}