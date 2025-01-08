## GetProto Plugin for downloading proto files from reflection service

This plugin is used to download proto files from a reflection service. It is useful when you have a service that exposes
its proto files through reflection service.

### Usage

```kotlin
plugins {
    id("io.github.kodysharma.getproto") version "0.1.0"
}

getProto {
    serverHost = "example.com" // or localhost if you are running locally
    serverPort = 443
}
```