package com.asyncstream.cloudmessage.rest

import io.vertx.core.Launcher

fun main() {
    Launcher.executeCommand("run", "-conf", "src/main/resources/application-conf.json", "com.asyncstream.cloudmessage.rest.ResourceAPIVerticle")
}