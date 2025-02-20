/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.ams.server;

import com.alibaba.fastjson.JSONObject;
import com.netease.arctic.ams.server.controller.HealthCheckController;
import com.netease.arctic.ams.server.controller.LoginController;
import com.netease.arctic.ams.server.controller.OptimizerController;
import com.netease.arctic.ams.server.controller.TableController;
import com.netease.arctic.ams.server.controller.TerminalController;
import com.netease.arctic.ams.server.controller.VersionController;
import com.netease.arctic.ams.server.controller.response.ErrorResponse;
import com.netease.arctic.ams.server.exception.ForbiddenException;
import com.netease.arctic.ams.server.exception.SignatureCheckException;
import com.netease.arctic.ams.server.service.impl.ApiTokenService;
import com.netease.arctic.ams.server.utils.ParamSignatureCalculator;
import com.netease.arctic.ams.server.utils.Utils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpCode;
import io.javalin.http.staticfiles.Location;
import org.apache.arrow.util.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.session.SessionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

public class AmsRestServer {
  public static final Logger LOG = LoggerFactory.getLogger("AmsRestServer");
  private static Javalin app;

  public static void startRestServer(Integer port) {
    app = Javalin.create(config -> {
      config.addStaticFiles(staticFiles -> {
        staticFiles.hostedPath = "/";
        // change to host files on a subpath, like '/assets'
        staticFiles.directory = "/static";
        // the directory where your files are located
        staticFiles.location = Location.CLASSPATH;
        // Location.CLASSPATH (jar) or Location.EXTERNAL (file system)
        staticFiles.precompress = false;
        // if the files should be pre-compressed and cached in memory (optimization)
        staticFiles.aliasCheck = null;
        // you can configure this to enable symlinks (= ContextHandler.ApproveAliases())
        //staticFiles.headers = Map.of(...);
        // headers that will be set for the files
        staticFiles.skipFileFunction = req -> false;
        // you can use this to skip certain files in the dir, based on the HttpServletRequest
      });

      //redirect the static page url to index.html
      config.addSinglePageRoot("/login", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/overview", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/introduce", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/tables", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/optimizers", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/hive-tables", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/hive-tables/upgrade", "/static/index.html", Location.CLASSPATH);
      config.addSinglePageRoot("/terminal", "/static/index.html", Location.CLASSPATH);

      config.sessionHandler(() -> new SessionHandler());
      config.enableCorsForAllOrigins();
    });
    app.start(port);
    LOG.info("Javalin Rest server start at {}!!!", port);

    // before
    app.before(ctx -> {
      String uriPath = ctx.path();
      String token = ctx.queryParam("token");
      // if token of api request is not empty, so we check the query by token first
      if (StringUtils.isNotEmpty(token)) {
        Utils.checkSinglePageToken(ctx);
      } else {
        if (needApiKeyCheck(uriPath)) {
          checkApiToken(ctx.method(), ctx.url(), ctx.queryParam("apiKey"),
                  ctx.queryParam("signature"), ctx.queryParamMap());
        } else if (needLoginCheck(uriPath)) {
          if (null == ctx.sessionAttribute("user")) {
            LOG.info("session info: {}", ctx.sessionAttributeMap() == null ? null : JSONObject.toJSONString(
                    ctx.sessionAttributeMap()));
            throw new ForbiddenException();
          }
        }
      }
    });

    app.routes(() -> {
      /*backend routers*/
      path("", () -> {
        //  /docs/latest can't be locationed to the index.html, so we add rule to redict to it.
        get("/docs/latest", ctx -> ctx.redirect("/docs/latest/index.html"));
      });
      path("/ams/v1", () -> {
        /** login controller**/
        get("/login/current", LoginController::getCurrent);
        post("/login", LoginController::login);

        /**  table controller **/
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/details", TableController::getTableDetail);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/hive/details", TableController::getHiveTableDetail);
        post("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/upgrade", TableController::upgradeHiveTable);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/upgrade/status", TableController::getUpgradeStatus);
        get("/upgrade/properties", TableController::getUpgradeHiveTableProperties);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/optimize", TableController::getOptimizeInfo);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/transactions",
                TableController::getTableTransactions);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/transactions/{transactionId}/detail",
                TableController::getTransactionDetail);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/partitions", TableController::getTablePartitions);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/partitions/{partition}/files",
                TableController::getPartitionFileListInfo);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/operations", TableController::getTableOperations);

        get("/catalogs/{catalog}/databases/{db}/tables", TableController::getTableList);
        get("/catalogs/{catalog}/databases", TableController::getDatabaseList);
        get("/catalogs", TableController::getCatalogs);

        /** optimize controller **/
        get("/optimize/optimizerGroups/{optimizerGroup}/tables", OptimizerController::getOptimizerTables);
        get("/optimize/optimizerGroups/{optimizerGroup}/optimizers", OptimizerController::getOptimizers);
        get("/optimize/optimizerGroups", OptimizerController::getOptimizerGroups);
        get("/optimize/optimizerGroups/{optimizerGroup}/info", OptimizerController::getOptimizerGroupInfo);
        delete("/optimize/optimizerGroups/{optimizerGroup}/optimizers/{jobId}", OptimizerController::releaseOptimizer);
        post("/optimize/optimizerGroups/{optimizerGroup}/optimizers", OptimizerController::scaleOutOptimizer);

        /** console controller **/
        get("/terminal/examples", TerminalController::getExamples);
        get("/terminal/examples/{exampleName}", TerminalController::getSqlExamples);
        post("/terminal/catalogs/{catalog}/execute", TerminalController::executeSql);
        get("/terminal/{sessionId}/logs", TerminalController::getLogs);
        get("/terminal/{sessionId}/result", TerminalController::getSqlStatus);
        put("/terminal/{sessionId}/stop", TerminalController::stopSql);
        get("/terminal/latestInfos/", TerminalController::getLatestInfo);

        /** health check **/
        get("/health/status", HealthCheckController::healthCheck);

        /** version controller **/
        get("/versionInfo", VersionController::getVersionInfo);
      });
      // for open api
      path("/api/ams/v1", () -> {

        /**  table controller **/
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/details", TableController::getTableDetail);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/hive/details", TableController::getHiveTableDetail);
        post("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/upgrade", TableController::upgradeHiveTable);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/upgrade/status", TableController::getUpgradeStatus);
        get("/upgrade/properties", TableController::getUpgradeHiveTableProperties);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/optimize", TableController::getOptimizeInfo);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/transactions",
                TableController::getTableTransactions);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/transactions/{transactionId}/detail",
                TableController::getTransactionDetail);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/partitions", TableController::getTablePartitions);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/partitions/{partition}/files",
                TableController::getPartitionFileListInfo);
        get("/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/signature", TableController::getTableDetailTabToken);
        get("/catalogs/{catalog}/databases/{db}/tables", TableController::getTableList);
        get("/catalogs/{catalog}/databases", TableController::getDatabaseList);
        get("/catalogs", TableController::getCatalogs);

        /** optimize controller **/
        get("/optimize/optimizerGroups/{optimizerGroup}/tables", OptimizerController::getOptimizerTables);
        get("/optimize/optimizerGroups/{optimizerGroup}/optimizers", OptimizerController::getOptimizers);
        get("/optimize/optimizerGroups", OptimizerController::getOptimizerGroups);
        get("/optimize/optimizerGroups/{optimizerGroup}/info", OptimizerController::getOptimizerGroupInfo);
        delete("/optimize/optimizerGroups/{optimizerGroup}/optimizers/{jobId}", OptimizerController::releaseOptimizer);
        post("/optimize/optimizerGroups/{optimizerGroup}/optimizers", OptimizerController::scaleOutOptimizer);

        /** console controller **/
        get("/terminal/examples", TerminalController::getExamples);
        get("/terminal/examples/{exampleName}", TerminalController::getSqlExamples);
        post("/terminal/catalogs/{catalog}/execute", TerminalController::executeSql);
        get("/terminal/{sessionId}/logs", TerminalController::getLogs);
        get("/terminal/{sessionId}/result", TerminalController::getSqlStatus);
        put("/terminal/{sessionId}/stop", TerminalController::stopSql);
        get("/terminal/latestInfos/", TerminalController::getLatestInfo);

        /** health check **/
        get("/health/status", HealthCheckController::healthCheck);

        /** version controller **/
        get("/versionInfo", VersionController::getVersionInfo);
      });
    });

    // after-handler
    app.after(ctx -> {
    });

    // exception-handler
    app.exception(Exception.class, (e, ctx) -> {
      if (e instanceof ForbiddenException) {
        ctx.json(new ErrorResponse(HttpCode.FORBIDDEN, "need login! before request", ""));
        return;
      } else if (e instanceof SignatureCheckException) {
        ctx.json(new ErrorResponse(HttpCode.FORBIDDEN, "SignatureExceptoin! before request", ""));
      } else {
        LOG.error("Failed to handle request", e);
        ctx.json(new ErrorResponse(HttpCode.INTERNAL_SERVER_ERROR, e.getMessage(), ""));
      }
    });

    // default response handle
    app.error(HttpCode.NOT_FOUND.getStatus(), ctx -> {
      ctx.json(new ErrorResponse(HttpCode.NOT_FOUND, "page not found!", ""));
    });

    app.error(HttpCode.INTERNAL_SERVER_ERROR.getStatus(), ctx -> {
      ctx.json(new ErrorResponse(HttpCode.INTERNAL_SERVER_ERROR, "internal error!", ""));
    });
  }

  public static void stopRestServer() {
    if (app != null) {
      app.stop();
    }
  }

  private static final String[] urlWhiteList = {
      "/ams/v1/versionInfo",
      "/ams/v1/login",
      "/",
      "/overview",
      "/introduce",
      "/tables",
      "/optimizers",
      "/login",
      "/terminal",
      "/hive-tables/upgrade",
      "/hive-tables",
      "/index.html",
      "/favicon.ico",
      "/js/*",
      "/img/*",
      "/css/*"
  };

  private static boolean needLoginCheck(String uri) {
    for (String item : urlWhiteList) {
      if (item.endsWith("*")) {
        if (uri.startsWith(item.substring(0, item.length() - 1))) {
          return false;
        }
      } else {
        if (uri.equals(item)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean needApiKeyCheck(String uri) {
    return uri.startsWith("/api");
  }

  private static void checkApiToken(String requestMethod, String requestUrl, String apiKey, String signature,
                                    Map<String, List<String>> params) {
    String plainText;
    String encryptString;
    String signCal;
    LOG.debug("[{}] url: {}, ", requestMethod, requestUrl);

    long receive = System.currentTimeMillis();
    ApiTokenService apiTokenService = new ApiTokenService();
    try {
      //get secrect
      String secrete = apiTokenService.getSecretByKey(apiKey);

      if (secrete == null) {
        throw new SignatureCheckException();
      }

      if (apiKey == null || signature == null) {
        throw new SignatureCheckException();
      }

      params.remove("apiKey");
      params.remove("signature");

      String paramString = ParamSignatureCalculator.generateParamStringWithValueList(params);

      if (StringUtils.isBlank(paramString)) {
        encryptString = ParamSignatureCalculator.SIMPLE_DATE_FORMAT.format(new Date());
      } else {
        encryptString = paramString;
      }

      plainText = String.format("%s%s%s", apiKey, encryptString, secrete);
      signCal = ParamSignatureCalculator.getMD5(plainText);
      LOG.info("calculate:  plainText:{}, signCal:{}, signFromRequest: {}", plainText, signCal, signature);

      if (!signature.equals(signCal)) {
        LOG.error(String.format("Signature Check Failed!!, req:%s, cal:%s", signature, signCal));
        throw new SignatureCheckException();
      }
    } catch (Exception e) {
      LOG.error("api doFilter error. ex:{}", e);
      throw new SignatureCheckException();
    } finally {
      LOG.debug("[finish] in {} ms, [{}] {}", System.currentTimeMillis() - receive, requestMethod, requestUrl);
    }
  }
}
