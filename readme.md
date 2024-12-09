## GetProto Plugin for downloading proto files from reflection service

This plugin is used to download proto files from a reflection service. It is useful when you have a service that exposes
its proto files through reflection service.

### Usage

```kotlin
plugins {
    id("com.github.rahulrv.getproto") version "0.1.0"
}

getProto {
    serverHost.set("gupsup-mate.koyeb.app")
    serverPort.set(443)
}

```