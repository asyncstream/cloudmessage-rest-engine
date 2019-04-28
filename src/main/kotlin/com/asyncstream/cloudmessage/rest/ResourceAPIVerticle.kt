package com.asyncstream.cloudmessage.rest

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.Json
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.auth.oauth2.OAuth2FlowType
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.impl.HttpStatusException
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.authenticateAwait
import io.vertx.kotlin.ext.auth.oauth2.oAuth2ClientOptionsOf
import io.vertx.kotlin.ext.auth.oauth2.providers.KeycloakAuth
import kotlinx.coroutines.launch
import java.nio.file.Paths

class ResourceAPIVerticle : CoroutineVerticle() {

    private val userHome = System.getProperty("user.home")

    private val location = Paths.get(userHome, "asyncstream")

    override suspend fun start() {
        val router = createRouter()

        val server = vertx.createHttpServer(httpServerOptionsOf()
                .setSsl(true)
                .setUseAlpn(true)
                .setPemKeyCertOptions(
                        PemKeyCertOptions().setKeyPath(location.resolve("ca.key").toString())
                                .setCertPath(
                                        location.resolve("ca.crt").toString())))

        Json.mapper.registerModule(KotlinModule())
        Json.prettyMapper.registerModule(KotlinModule())

        server.requestHandler { router.handle(it) }
                .listenAwait(config.getInteger("https.port", 7443))
    }

    private suspend fun createRouter() = Router.router(vertx).apply {
        System.out.println(config.getJsonObject ("client").getString("secret"))
        val keycloak = KeycloakAuth.discoverAwait(
                vertx,
                oAuth2ClientOptionsOf()
                        .setFlow(OAuth2FlowType.PASSWORD)
                        .setSite("http://localhost:8080/auth/realms/master")
                        .setClientID("asyncstream")
                        .setClientSecret(config.getJsonObject ("client").getString("secret")))

        // To get the access token.
        post("/login").handler(BodyHandler.create()).coroutineHandler { rc ->
            val userJson = rc.bodyAsJson
            val user = keycloak.authenticateAwait(userJson)
            rc.response().end(user.principal().toString())
        }

        // Create.
        post("/association").consumes("application/json").coroutineHandler {
            Auth(keycloak, "asyncstream:create").handle(it)
            it.next()
        }.handler(BodyHandler.create()).coroutineHandler {
            it.response()
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily("894984"));
        }

        put("/association/:code").consumes("application/json").coroutineHandler {
            Auth(keycloak, "asyncstream:update").handle(it)
            it.next()
        }.handler(BodyHandler.create()).coroutineHandler {
            it.response()
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily("894984"));
        }

        get("/association").produces("application/json").coroutineHandler {
            Auth(keycloak, "view").handle(it)
            it.next()
        }.coroutineHandler {
            it.response()
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily("Test"));
        }


        // Exception with status code
        route().handler { ctx ->
            ctx.fail(HttpStatusException(HttpResponseStatus.NOT_FOUND.code()))
        }

        route().failureHandler { failureRoutingContext ->
            val statusCode = failureRoutingContext.statusCode()
            val failure = failureRoutingContext.failure()

            if (statusCode == -1) {
                if (failure is HttpStatusException)
                    response(failureRoutingContext.response(), failure.statusCode, failure.message)
                else
                    response(failureRoutingContext.response(), HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                            failure.message)
            } else {
                response(failureRoutingContext.response(), statusCode, failure?.message)
            }
        }
    }


    private fun response(response: HttpServerResponse, statusCode: Int, failureMessage: String?) {
        response.setStatusCode(statusCode).end("Failure calling the RESTful API: $failureMessage")
    }

    private fun Route.coroutineHandler(fn: suspend (RoutingContext) -> Unit): Route {
        return handler { ctx ->
            launch(ctx.vertx().dispatcher()) {
                try {
                    fn(ctx)
                } catch (e: Exception) {
                    ctx.fail(e)
                }
            }
        }
    }
}